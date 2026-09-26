package com.serverpanel.system.service.oauth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthDingTalkRequest;
import me.zhyd.oauth.request.AuthGiteeRequest;
import me.zhyd.oauth.request.AuthGithubRequest;
import me.zhyd.oauth.request.AuthQqRequest;
import me.zhyd.oauth.request.AuthRequest;
import me.zhyd.oauth.request.AuthWeChatOpenRequest;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.serverpanel.common.constant.CacheConstants;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.security.LoginHelper;
import com.serverpanel.system.config.OAuthProperties;
import com.serverpanel.system.dto.oauth.OAuthCallbackBody;
import com.serverpanel.system.dto.oauth.OAuthVOs.AuthorizeVO;
import com.serverpanel.system.dto.oauth.OAuthVOs.BindingVO;
import com.serverpanel.system.dto.oauth.OAuthVOs.ProviderVO;
import com.serverpanel.system.entity.SysLoginLog;
import com.serverpanel.system.entity.SysUser;
import com.serverpanel.system.entity.SysUserOauth;
import com.serverpanel.system.mapper.SysLoginLogMapper;
import com.serverpanel.system.mapper.SysUserMapper;
import com.serverpanel.system.mapper.SysUserOauthMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 第三方登录服务（JustAuth）。
 *
 * <p>流程：前端回调方案 —— redirect-uri 统一指向前端回调页 /auth/oauth/callback，
 * 后端只负责「生成授权 URL（含 state）」与「code 换用户信息」两件事，
 * accessToken 仅经 POST 响应 JSON 返回，不落入 URL 与访问日志。
 *
 * <p>state 防伪：{intent}:{provider}:{random32}，完整串作为 Redis key 的一部分，
 * value 为 {intent}|{providerKey}|{userId}；消费用 GETDEL（getAndDelete）原子取值并
 * 删除，天然防重放。JustAuth 侧 ignoreCheckState(true)，state 校验完全由本服务承担。
 *
 * <p>账号策略：先绑定后登录 —— login 仅按 (provider, openId) 命中绑定表才放行，
 * 不做自动注册；绑定记录只能由已登录用户经 bind 接口主动创建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthService {

    private static final String INTENT_LOGIN = "login";
    private static final String INTENT_BIND = "bind";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final SysUserMapper userMapper;
    private final SysUserOauthMapper userOauthMapper;
    private final SysLoginLogMapper loginLogMapper;

    /** 已启用的第三方平台列表（配置了凭证才返回），顺序固定 */
    public List<ProviderVO> providers() {
        List<ProviderVO> result = new ArrayList<>();
        for (OAuthProvider provider : OAuthProvider.values()) {
            OAuthProperties.ProviderConfig config = configOf(provider);
            if (config.enabled()) {
                result.add(new ProviderVO(provider.key(), provider.displayName()));
            }
        }
        return result;
    }

    /**
     * 生成授权跳转地址。
     *
     * <p>intent=bind 时要求登录态（authorize 路径已在拦截器白名单内，须在此显式校验），
     * 且 state 中记录发起人 userId，回调 bind 时校验一致，防他人 state 冒用。
     */
    public AuthorizeVO authorize(String providerKey, String intent, HttpServletRequest request) {
        OAuthProvider provider = requireProvider(providerKey);
        if (!INTENT_LOGIN.equals(intent) && !INTENT_BIND.equals(intent)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "intent 仅允许 login / bind");
        }
        String userIdPart = "";
        if (INTENT_BIND.equals(intent)) {
            StpUtil.checkLogin();
            userIdPart = String.valueOf(LoginHelper.getUserId());
        }

        String random = HexFormat.of().formatHex(randomBytes());
        String state = intent + ":" + provider.key() + ":" + random;
        String value = intent + "|" + provider.key() + "|" + userIdPart;
        redisTemplate.opsForValue().set(CacheConstants.OAUTH_STATE_PREFIX + state, value,
                Duration.ofSeconds(Math.max(properties.getStateTtlSeconds(), 30)));

        return new AuthorizeVO(newRequest(provider).authorize(state));
    }

    /**
     * 第三方登录：code 换用户信息 → 查绑定表 → 命中才建立 Sa-Token 会话。
     *
     * <p>未绑定一律拒绝（1022）并落登录日志（status=0），不做任何自动注册。
     */
    public String login(String providerKey, OAuthCallbackBody body, HttpServletRequest request) {
        OAuthProvider provider = requireProvider(providerKey);
        // state 校验（原子 GETDEL，防重放），未抛异常即有效
        consumeState(body.state(), provider, INTENT_LOGIN);

        AuthUser authUser = fetchAuthUser(provider, body);
        SysUserOauth binding = userOauthMapper.selectOne(
                new LambdaQueryWrapper<SysUserOauth>()
                        .eq(SysUserOauth::getProvider, provider.name())
                        .eq(SysUserOauth::getOpenId, authUser.getUuid()));

        String ip = clientIp(request);
        if (binding == null) {
            recordLoginLog("oauth:" + provider.name(), ip, 0,
                    provider.displayName() + " 第三方登录失败：未绑定面板账号", request);
            throw new ServiceException(ErrorCode.AUTH_OAUTH_NOT_BOUND);
        }

        SysUser user = userMapper.selectById(binding.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            recordLoginLog(user == null ? "oauth:" + provider.name() : user.getUsername(), ip, 0,
                    provider.displayName() + " 第三方登录失败：账号不存在或已停用", request);
            throw new ServiceException(ErrorCode.AUTH_USER_DISABLED);
        }

        StpUtil.login(user.getId());
        StpUtil.getSession().set(LoginHelper.KEY_USERNAME, user.getUsername());
        StpUtil.getSession().set(LoginHelper.KEY_NICKNAME, user.getNickname());
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(ip);
        userMapper.updateById(user);
        recordLoginLog(user.getUsername(), ip, 1,
                provider.displayName() + " 第三方登录成功", request);
        return StpUtil.getTokenValue();
    }

    /**
     * 绑定当前登录用户的第三方身份（个人中心发起）。
     *
     * <p>冲突检查：同一第三方身份只允许绑一个面板账号（uk_provider_openid），
     * 一人一平台只绑一条（uk_user_provider）。
     */
    public void bind(String providerKey, OAuthCallbackBody body) {
        OAuthProvider provider = requireProvider(providerKey);
        long currentUserId = LoginHelper.getUserId();
        StatePayload payload = consumeState(body.state(), provider, INTENT_BIND);
        if (payload.userId() != currentUserId) {
            // state 发起人与当前登录用户不一致：可能 state 被冒用或已换账号登录
            throw new ServiceException(ErrorCode.AUTH_OAUTH_STATE_INVALID);
        }

        AuthUser authUser = fetchAuthUser(provider, body);
        if (userOauthMapper.selectCount(new LambdaQueryWrapper<SysUserOauth>()
                .eq(SysUserOauth::getProvider, provider.name())
                .eq(SysUserOauth::getOpenId, authUser.getUuid())) > 0) {
            throw new ServiceException(ErrorCode.AUTH_OAUTH_ALREADY_BOUND);
        }
        if (userOauthMapper.selectCount(new LambdaQueryWrapper<SysUserOauth>()
                .eq(SysUserOauth::getUserId, currentUserId)
                .eq(SysUserOauth::getProvider, provider.name())) > 0) {
            throw new ServiceException(ErrorCode.AUTH_OAUTH_PROVIDER_BOUND);
        }

        SysUserOauth binding = new SysUserOauth();
        binding.setUserId(currentUserId);
        binding.setProvider(provider.name());
        binding.setOpenId(authUser.getUuid());
        binding.setUnionId(authUser.getToken() == null ? null : authUser.getToken().getUnionId());
        binding.setNickname(authUser.getNickname());
        binding.setAvatar(authUser.getAvatar());
        userOauthMapper.insert(binding);
    }

    /** 当前用户已绑定的第三方身份列表 */
    public List<BindingVO> bindings() {
        long userId = LoginHelper.getUserId();
        List<SysUserOauth> list = userOauthMapper.selectList(
                new LambdaQueryWrapper<SysUserOauth>()
                        .eq(SysUserOauth::getUserId, userId)
                        .orderByAsc(SysUserOauth::getCreatedAt));
        List<BindingVO> result = new ArrayList<>();
        for (SysUserOauth binding : list) {
            OAuthProvider.ofName(binding.getProvider()).ifPresentOrElse(
                    p -> result.add(new BindingVO(p.key(), p.displayName(),
                            binding.getNickname(), binding.getAvatar(), binding.getCreatedAt())),
                    () -> result.add(new BindingVO(binding.getProvider().toLowerCase(),
                            binding.getProvider(), binding.getNickname(), binding.getAvatar(),
                            binding.getCreatedAt())));
        }
        return result;
    }

    /** 解绑当前用户在指定平台的第三方身份（密码登录始终可用，无失联风险） */
    public void unbind(String providerKey) {
        OAuthProvider provider = requireProvider(providerKey);
        long userId = LoginHelper.getUserId();
        int deleted = userOauthMapper.delete(new LambdaQueryWrapper<SysUserOauth>()
                .eq(SysUserOauth::getUserId, userId)
                .eq(SysUserOauth::getProvider, provider.name()));
        if (deleted == 0) {
            throw new ServiceException(ErrorCode.NOT_FOUND, "该平台尚未绑定");
        }
    }

    // ===== 内部方法 =====

    /** provider 校验：key 必须存在且已在配置中启用，否则 1020 */
    private OAuthProvider requireProvider(String providerKey) {
        OAuthProvider provider = OAuthProvider.ofKey(providerKey)
                .orElseThrow(() -> new ServiceException(ErrorCode.AUTH_OAUTH_UNSUPPORTED));
        if (!configOf(provider).enabled()) {
            throw new ServiceException(ErrorCode.AUTH_OAUTH_UNSUPPORTED);
        }
        return provider;
    }

    private OAuthProperties.ProviderConfig configOf(OAuthProvider provider) {
        return properties.getProviders().getOrDefault(provider.key(),
                new OAuthProperties.ProviderConfig());
    }

    /**
     * 按配置即时构造 AuthRequest（不用 JustAuth 静态工厂扫描）。
     * ignoreCheckState(true)：state 校验由本项目 Redis 承担。
     */
    private AuthRequest newRequest(OAuthProvider provider) {
        OAuthProperties.ProviderConfig config = configOf(provider);
        AuthConfig authConfig = AuthConfig.builder()
                .clientId(config.getClientId())
                .clientSecret(config.getClientSecret())
                .redirectUri(properties.getRedirectUri())
                .ignoreCheckState(true)
                .build();
        return switch (provider) {
            case GITEE -> new AuthGiteeRequest(authConfig);
            case GITHUB -> new AuthGithubRequest(authConfig);
            case DINGTALK -> new AuthDingTalkRequest(authConfig);
            case WECHAT_OPEN -> new AuthWeChatOpenRequest(authConfig);
            case QQ -> new AuthQqRequest(authConfig);
        };
    }

    /** code 换用户信息（getAccessToken + getUserInfo 一步完成），失败统一 1025 */
    private AuthUser fetchAuthUser(OAuthProvider provider, OAuthCallbackBody body) {
        try {
            AuthCallback callback = AuthCallback.builder()
                    .code(body.code()).state(body.state()).build();
            AuthResponse response = newRequest(provider).login(callback);
            if (!response.ok() || !(response.getData() instanceof AuthUser authUser)) {
                log.warn("OAuth login failed: provider={}, respCode={}, respMsg={}",
                        provider.key(), response.getCode(), response.getMsg());
                throw new ServiceException(ErrorCode.AUTH_OAUTH_CALLBACK_FAILED);
            }
            return authUser;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OAuth exchange error: provider={}, error={}", provider.key(),
                    e.getMessage());
            throw new ServiceException(ErrorCode.AUTH_OAUTH_CALLBACK_FAILED);
        }
    }

    /**
     * 消费 state：GETDEL 原子取值并删除（防重放），随后校验 intent / provider 匹配。
     */
    private StatePayload consumeState(String state, OAuthProvider expectedProvider,
            String expectedIntent) {
        String value = redisTemplate.opsForValue()
                .getAndDelete(CacheConstants.OAUTH_STATE_PREFIX + state);
        if (value == null) {
            throw new ServiceException(ErrorCode.AUTH_OAUTH_STATE_INVALID);
        }
        String[] parts = value.split("\\|");
        if (parts.length < 2 || !expectedIntent.equals(parts[0])
                || !expectedProvider.key().equals(parts[1])) {
            throw new ServiceException(ErrorCode.AUTH_OAUTH_STATE_INVALID);
        }
        long userId = parts.length > 2 && !parts[2].isBlank() ? Long.parseLong(parts[2]) : 0;
        return new StatePayload(parts[0], parts[1], userId);
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private void recordLoginLog(String username, String ip, int status, String message,
            HttpServletRequest request) {
        try {
            SysLoginLog entry = new SysLoginLog();
            entry.setUsername(username);
            entry.setIp(ip);
            entry.setStatus(status);
            entry.setMessage(message);
            entry.setUserAgent(request.getHeader("User-Agent"));
            loginLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("Failed to record oauth login log: {}", e.getMessage());
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

    /** state 的 Redis 载荷 */
    private record StatePayload(String intent, String providerKey, long userId) {}

    /** 供 Controller 包装 accessToken 返回体 */
    public Map<String, String> tokenPayload(String token) {
        return Map.of("accessToken", token);
    }
}
