package com.serverpanel.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.serverpanel.common.constant.CacheConstants;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.system.dto.auth.LoginBody;
import com.serverpanel.system.dto.auth.MailCodeBody;
import com.serverpanel.system.dto.auth.MailLoginBody;
import com.serverpanel.system.dto.auth.PasswordBody;
import com.serverpanel.system.dto.auth.RegisterBody;
import com.serverpanel.system.dto.auth.UserInfoVO;
import com.serverpanel.system.dto.auth.UserProfileBody;
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
 * 认证服务：登录（防爆破）/ 邮箱验证码登录 / 自助注册 / 登出 / 用户信息 / 改密。
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
    private final AvatarSupport avatarSupport;
    private final MailCodeService mailCodeService;

    @Value("${serverpanel.login.max-fail:5}")
    private int maxFail;

    @Value("${serverpanel.login.lock-minutes:15}")
    private int lockMinutes;

    /** 自助注册总开关（默认关闭；新注册账号不分配角色，仅个人中心） */
    @Value("${serverpanel.register.enabled:false}")
    private boolean registerEnabled;

    /** 二级认证安全窗口（秒）；下限 30 秒在 openSafe 内钳制 */
    @Value("${serverpanel.audit.safe-timeout-seconds:300}")
    private long safeTimeoutSeconds;

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

    /**
     * 发送邮箱验证码（发码前场景校验）：
     * login 要求邮箱已绑定面板账号（未绑定的邮箱收不到码，也不向其泄露账号存在性）；
     * register 要求注册开关开启且邮箱未被占用。
     */
    public void sendMailCode(MailCodeBody body) {
        String email = body.getEmail();
        boolean exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email)) > 0;
        if ("login".equals(body.getPurpose())) {
            if (!exists) {
                throw new ServiceException(ErrorCode.AUTH_USER_NOT_FOUND, "该邮箱未绑定面板账号");
            }
        } else {
            if (!registerEnabled) {
                throw new ServiceException(ErrorCode.REGISTER_DISABLED);
            }
            if (exists) {
                throw new ServiceException(ErrorCode.EMAIL_OR_USERNAME_EXISTS, "该邮箱已被注册");
            }
        }
        mailCodeService.send(email, body.getPurpose());
    }

    /**
     * 邮箱验证码登录：校验验证码（purpose=login，一次性消费）→ 按邮箱定位用户 → 建立会话。
     *
     * <p>发码环节（MailAuthController /mail/code）已校验邮箱必须绑定面板账号，
     * 此处用户缺失属边界情况（发码后被删除），按用户不存在处理。
     * 验证码自带「5 次失败作废 + 60s 冷却 + 日限 10」约束，无需再叠加防爆破计数。
     */
    public String mailLogin(MailLoginBody body, HttpServletRequest request) {
        String email = body.getEmail();
        mailCodeService.verify(email, "login", body.getCode());

        SysUser user = userMapper.selectOne(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email));
        String ip = clientIp(request);
        if (user == null) {
            recordLoginLog("mail:" + email, ip, 0, "邮箱验证码登录失败：邮箱未绑定面板账号", request);
            throw new ServiceException(ErrorCode.AUTH_USER_NOT_FOUND,
                "该邮箱未绑定面板账号");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            recordLoginLog(user.getUsername(), ip, 0, "邮箱验证码登录失败：账号已停用", request);
            throw new ServiceException(ErrorCode.AUTH_USER_DISABLED);
        }

        StpUtil.login(user.getId());
        StpUtil.getSession().set(LoginHelper.KEY_USERNAME, user.getUsername());
        StpUtil.getSession().set(LoginHelper.KEY_NICKNAME, user.getNickname());
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(ip);
        userMapper.updateById(user);
        recordLoginLog(user.getUsername(), ip, 1, "邮箱验证码登录成功", request);
        return StpUtil.getTokenValue();
    }

    /**
     * 自助注册（邮箱验证码方式）：开关校验 → 唯一性前置校验 → 校验验证码（一次性消费）→ 落库。
     *
     * <p>新账号不分配任何角色（登录后仅个人中心），业务权限由管理员在用户管理中分配；
     * 并发注册穿透由 uk_username / uk_email 唯一索引兜底（DuplicateKeyException 转 1035）。
     */
    public void register(RegisterBody body, HttpServletRequest request) {
        if (!registerEnabled) {
            throw new ServiceException(ErrorCode.REGISTER_DISABLED);
        }
        String email = body.getEmail();
        String ip = clientIp(request);

        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, body.getUsername())) > 0) {
            throw new ServiceException(ErrorCode.EMAIL_OR_USERNAME_EXISTS, "用户名已被使用");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email)) > 0) {
            throw new ServiceException(ErrorCode.EMAIL_OR_USERNAME_EXISTS, "该邮箱已被注册");
        }
        mailCodeService.verify(email, "register", body.getCode());

        SysUser user = new SysUser();
        user.setUsername(body.getUsername());
        user.setNickname(body.getUsername());
        user.setPassword(passwordEncoder.encode(body.getPassword()));
        user.setEmail(email);
        user.setStatus(1);
        try {
            userMapper.insert(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发注册同一用户名/邮箱的极端竞争：唯一索引兜底
            throw new ServiceException(ErrorCode.EMAIL_OR_USERNAME_EXISTS);
        }
        recordLoginLog(body.getUsername(), ip, 1, "自助注册成功（邮箱验证码，未分配角色）", request);
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
        // 归一化为可直接渲染的 src：预设映射站内静态资源、自定义保留 data URL、
        // 空值回落默认头像（原先返回空串会让头像渲染为空白）
        vo.setAvatar(avatarSupport.toRenderable(user.getAvatar()));
        vo.setAvatarRaw(user.getAvatar());
        vo.setGender(user.getGender());
        vo.setDesc(user.getDesc() == null ? "" : user.getDesc());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setHomePath("/");
        vo.setRoles(permissionService.getUserRoleKeys(userId));
        return vo;
    }

    /** 更新当前用户基本资料（个人中心） */
    public void updateProfile(UserProfileBody body) {
        long userId = LoginHelper.getUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        if (body.getRealName() != null) {
            user.setNickname(body.getRealName());
        }
        if (body.getEmail() != null) {
            // V23 起邮箱全表唯一（uk_email）：改绑前校验占用，避免直撞唯一索引报 500
            if (!body.getEmail().equals(user.getEmail())
                    && userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getEmail, body.getEmail())) > 0) {
                throw new ServiceException(ErrorCode.EMAIL_OR_USERNAME_EXISTS, "该邮箱已被其他账号使用");
            }
            user.setEmail(body.getEmail());
        }
        if (body.getPhone() != null) {
            user.setPhone(body.getPhone());
        }
        if (body.getGender() != null) {
            user.setGender(body.getGender());
        }
        // 头像三态：null=不修改；非 null 才处理（空串表示清除，恢复默认）
        String avatar = body.getAvatar() == null
            ? null : avatarSupport.parseAndValidate(body.getAvatar());
        boolean clearAvatar = avatar == null && body.getAvatar() != null;
        if (avatar != null) {
            user.setAvatar(avatar);
            user.setAvatarUpdatedAt(LocalDateTime.now());
        }
        if (body.getDesc() != null) {
            user.setDesc(body.getDesc());
        }
        userMapper.updateById(user);
        if (clearAvatar) {
            // 清除头像必须显式 set：updateById 默认 NOT_NULL 策略会忽略 null 字段
            userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getAvatar, null)
                .set(SysUser::getAvatarUpdatedAt, LocalDateTime.now()));
        }
        // 同步会话中的昵称，供后续接口直接读取
        StpUtil.getSession().set(LoginHelper.KEY_NICKNAME, user.getNickname());
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

    /**
     * 二级认证（step-up）：校验当前登录用户的密码，通过后开启安全窗口。
     *
     * <p>窗口内 {@code StpUtil.checkSafe()} 不再抛 NotSafeException，
     * {@code @Audit(safe = true)} 的高危接口才允许执行。窗口按会话（token）维度存储。
     *
     * <p>失败计数复用登录防爆破的 Redis 键（用户名+IP），使本接口不能成为
     * 持 token 者爆破口令的旁路 —— 只有密码校验通过才放行。
     *
     * @param password 当前登录用户的登录密码
     * @param request  仅用于取客户端 IP（与登录防爆破同口径）
     * @return 本次安全窗口的有效秒数
     */
    public long openSafe(String password, HttpServletRequest request) {
        long userId = LoginHelper.getUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        String ip = clientIp(request);
        String failKey = CacheConstants.LOGIN_FAIL_PREFIX + user.getUsername() + ":" + ip;
        String lockKey = CacheConstants.LOGIN_LOCK_PREFIX + user.getUsername() + ":" + ip;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            long ttl = redisTemplate.getExpire(lockKey);
            throw new ServiceException(ErrorCode.AUTH_LOCKED,
                "账号已锁定，请 " + Math.max(ttl / 60, 1) + " 分钟后重试");
        }

        if (password == null || !passwordEncoder.matches(password, user.getPassword())) {
            recordLoginLog(user.getUsername(), ip, 0, "二级认证密码错误", request);
            Long fails = redisTemplate.opsForValue().increment(failKey);
            redisTemplate.expire(failKey, Duration.ofMinutes(Math.max(lockMinutes, 1)));
            if (fails != null && fails >= maxFail) {
                redisTemplate.opsForValue().set(lockKey, "1",
                    Duration.ofMinutes(Math.max(lockMinutes, 1)));
                redisTemplate.delete(failKey);
                log.warn("Safe-auth locked: {} from {}", user.getUsername(), ip);
            }
            throw new ServiceException(ErrorCode.AUTH_LOGIN_FAILED, "密码不正确，二级认证未通过");
        }

        redisTemplate.delete(failKey);
        long timeout = Math.max(safeTimeoutSeconds, 30);
        StpUtil.openSafe(timeout);
        recordLoginLog(user.getUsername(), ip, 1, "二级认证通过", request);
        return timeout;
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
