package com.serverpanel.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.serverpanel.common.constant.CacheConstants;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.system.dto.auth.LoginBody;
import com.serverpanel.system.dto.auth.PasswordBody;
import com.serverpanel.system.dto.auth.UserInfoVO;
import com.serverpanel.system.entity.SysUser;
import com.serverpanel.system.mapper.SysLoginLogMapper;
import com.serverpanel.system.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 认证服务：登录（防爆破）/ 登出 / 用户信息 / 改密。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final PermissionService permissionService;

    @Value("${serverpanel.login.max-fail:5}")
    private int maxFail;

    @Value("${serverpanel.login.lock-minutes:15}")
    private int lockMinutes;

    /** 登录：防爆破（按 用户名+IP 计数与锁定） + 审计登录日志 */
    public String login(LoginBody body, HttpServletRequest request) {
        String username = body.getUsername();
        String ip = clientIp(request);
        String failKey = CacheConstants.LOGIN_FAIL_PREFIX + username + ":" + ip;
        String lockKey = CacheConstants.LOGIN_LOCK_PREFIX + username + ":" + ip;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            long ttl = redisTemplate.getExpire(lockKey);
            throw new ServiceException(ErrorCode.AUTH_LOCKED.getCode(),
                "账号已锁定，请 " + Math.max(ttl / 60, 1) + " 分钟后重试");
        }

        SysUser user = userMapper.selectOne(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));

        boolean success = user != null
            && passwordEncoder.matches(body.getPassword(), user.getPassword());

        if (!success) {
            recordLoginLog(username, ip, 0, "用户名或密码错误", request);
            Long fails = redisTemplate.opsForValue().increment(failKey);
            redisTemplate.expire(failKey, Duration.ofMinutes(Math.max(lockMinutes, 1)));
            if (fails != null && fails >= maxFail) {
                redisTemplate.opsForValue().set(lockKey, "1",
                    Duration.ofMinutes(Math.max(lockMinutes, 1)));
                redisTemplate.delete(failKey);
                log.warn("Login locked: {} from {}", username, ip);
            }
            throw new ServiceException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        if (user.getStatus() != 1) {
            recordLoginLog(username, ip, 0, "账号已停用", request);
            throw new ServiceException(ErrorCode.AUTH_USER_DISABLED);
        }

        // 登录成功：清计数、写会话、记录日志
        redisTemplate.delete(failKey);
        StpUtil.login(user.getId());
        StpUtil.getSession().set(LoginHelper.KEY_USERNAME, user.getUsername());
        StpUtil.getSession().set(LoginHelper.KEY_NICKNAME, user.getNickname());

        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(ip);
        userMapper.updateById(user);
        recordLoginLog(username, ip, 1, "登录成功", request);
        return StpUtil.getTokenValue();
    }

    public void logout() {
        StpUtil.logout();
    }

    /** 当前用户信息（Vben 约定字段） */
    public UserInfoVO getUserInfo() {
        long userId = LoginHelper.getUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(String.valueOf(user.getId()));
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getNickname());
        vo.setAvatar(user.getAvatar() == null ? "" : user.getAvatar());
        vo.setDesc("");
        vo.setHomePath("/");
        vo.setRoles(permissionService.getUserRoleKeys(userId));
        return vo;
    }

    /** 修改当前用户密码 */
    public void changePassword(PasswordBody body) {
        long userId = LoginHelper.getUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(body.getOldPassword(), user.getPassword())) {
            throw new ServiceException(ErrorCode.AUTH_OLD_PASSWORD_ERROR);
        }
        user.setPassword(passwordEncoder.encode(body.getNewPassword()));
        userMapper.updateById(user);
        // 改密后全端下线
        StpUtil.logout(userId);
    }

    private void recordLoginLog(String username, String ip, int status, String message,
                                HttpServletRequest request) {
        try {
            com.serverpanel.system.entity.SysLoginLog entry = new com.serverpanel.system.entity.SysLoginLog();
            entry.setUsername(username);
            entry.setIp(ip);
            entry.setStatus(status);
            entry.setMessage(message);
            entry.setUserAgent(request.getHeader("User-Agent"));
            loginLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("Failed to record login log: {}", e.getMessage());
        }
    }

    /** 提取客户端 IP（优先 X-Forwarded-For，兼容反代部署） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 供 AccountController 返回 accessToken 的载体 */
    public Map<String, String> tokenPayload(String token) {
        return Map.of("accessToken", token);
    }
}
