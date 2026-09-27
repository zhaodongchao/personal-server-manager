package com.serverpanel.system.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.serverpanel.common.constant.CacheConstants;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.system.dto.auth.ClickCaptchaVO;
import com.serverpanel.system.dto.auth.ClickCaptchaVerifyBody;

/**
 * 点选人机校验服务。
 *
 * <p>设计要点（与 OAuth state、登录防爆破同体系的「服务端签发 + 单次消费」令牌）：
 * <ul>
 *   <li>{@code POST /auth/captcha/click} 生成一张随机字符图片，把<b>目标字符的中心坐标</b>
 *       写入 Redis（{@code captcha:click:{token}}），响应只回图片与「要依次点击哪些字符」；</li>
 *   <li>{@code POST /auth/captcha/click/verify} 收前端上报的点击相对坐标，换算成像素后与
 *       挑战中的坐标逐个比对：<b>顺序一致 + 每次点击都落在命中半径内</b>才算通过；</li>
 *   <li>通过后按 purpose 签发一次性令牌（{@code captcha:send:} / {@code captcha:login:}），
 *       由 {@code /mail/code} 或 {@code /auth/login} 携带并原子消费（GETDEL）。</li>
 * </ul>
 *
 * <p>安全取舍：
 * <ul>
 *   <li>答案只在服务端 —— 图片随响应下发，但坐标不下发，因此脚本即便解析响应也拿不到答案，
 *       必须真的识别出图片中哪个字是目标字；</li>
 *   <li>挑战限次 —— 同一张图最多允许 {@code click-max-attempts} 次失败，超限即作废，
 *       避免在固定图片上反复试坐标；</li>
 *   <li>挑战单次使用 —— 通过后立即删除，杜绝同一挑战被反复兑换令牌。</li>
 * </ul>
 *
 * <p>点选本身是交互式人机校验（抬高自动化门槛）；真正的抗爆破 / 防轰炸由
 * {@link SendGuardService} 的 IP 限流与封锁承担。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Slf4j
@Service
public class CaptchaService {

    private final StringRedisTemplate redisTemplate;

    /** 挑战有效期（秒）：过期需换一张新图 */
    @Value("${serverpanel.captcha.click-ttl-seconds:120}")
    private int clickTtlSeconds;

    /** 一次性令牌有效期（秒），发信令牌与登录令牌共用 */
    @Value("${serverpanel.captcha.send-token-ttl-seconds:60}")
    private int tokenTtlSeconds;

    /**
     * 命中半径上限（像素）：点击点与目标字符中心距离不超过该值算命中。
     * 实际生效值再与渲染器给出的「安全半径」取小 —— 字符越多格子越小，半径自动收紧，
     * 避免命中圆覆盖相邻字符导致点错也算过。
     */
    @Value("${serverpanel.captcha.click-hit-radius:42}")
    private int maxHitRadius;

    /** 单张验证码允许的最大失败次数，超过作废（需换一张） */
    @Value("${serverpanel.captcha.click-max-attempts:3}")
    private int maxAttempts;

    @Value("${serverpanel.captcha.image-width:300}")
    private int imageWidth;

    @Value("${serverpanel.captcha.image-height:160}")
    private int imageHeight;

    /** 目标字符个数（需按顺序点击） */
    @Value("${serverpanel.captcha.target-count:3}")
    private int targetCount;

    /** 干扰字符个数 */
    @Value("${serverpanel.captcha.decoy-count:3}")
    private int decoyCount;

    public CaptchaService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 生成点选验证码挑战。
     *
     * <p>图片随响应下发，目标字符坐标仅存服务端 Redis（TTL = click-ttl-seconds）。
     *
     * @return 挑战令牌 + 图片 + 需按序点击的目标字符
     */
    public ClickCaptchaVO issueClickCaptcha() {
        ClickCaptchaRenderer.Result rendered;
        try {
            rendered = ClickCaptchaRenderer.render(imageWidth, imageHeight, targetCount, decoyCount);
        } catch (RuntimeException e) {
            log.error("点选验证码渲染失败，请检查服务器字体环境（Debian 可安装 fonts-dejavu-core）", e);
            throw new ServiceException(ErrorCode.CAPTCHA_RENDER_FAILED);
        }

        // 实际命中半径 = min(配置上限, 渲染器安全半径)：字符越密半径越小，防点错也算过
        int radius = Math.min(maxHitRadius, rendered.safeRadius());
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                CacheConstants.CAPTCHA_CLICK_PREFIX + token, encodeChallenge(rendered, radius),
                Duration.ofSeconds(clickTtlSeconds));

        ClickCaptchaVO vo = new ClickCaptchaVO();
        vo.setCaptchaToken(token);
        vo.setImage(toDataUri(rendered.image()));
        vo.setPrompt(rendered.targets().stream().map(ClickCaptchaRenderer.Target::ch).toList());
        vo.setWidth(rendered.width());
        vo.setHeight(rendered.height());
        return vo;
    }

    /**
     * 校验点选坐标并签发一次性令牌。
     *
     * @param token   挑战令牌
     * @param clicks  按点击顺序上报的相对坐标（0~1）
     * @param purpose 用途：{@code send}=发邮件验证码（签 captcha:send:），
     *                {@code login}=密码登录（签 captcha:login:）。两者隔离，
     *                防止某一场景签发的令牌被重放到另一场景。
     * @return 一次性令牌（TTL = send-token-ttl-seconds）
     */
    public String verifyClick(String token, List<ClickCaptchaVerifyBody.ClickPoint> clicks,
            String purpose) {
        if (token == null || token.isBlank()) {
            throw new ServiceException(ErrorCode.CAPTCHA_REQUIRED);
        }
        String key = CacheConstants.CAPTCHA_CLICK_PREFIX + token;
        String payload = redisTemplate.opsForValue().get(key);
        if (payload == null) {
            throw new ServiceException(ErrorCode.CAPTCHA_INVALID);
        }
        if (!matches(payload, clicks)) {
            recordFailure(key, token);
            throw new ServiceException(ErrorCode.CAPTCHA_INVALID);
        }
        // 通过：挑战单次使用，连同失败计数一并清理
        redisTemplate.delete(key);
        redisTemplate.delete(CacheConstants.CAPTCHA_CLICK_FAIL_PREFIX + token);
        return issueToken(purpose);
    }

    /**
     * 消费一次性发信令牌。缺失或已失效（过期 / 已用过）均抛错。
     * 使用 GETDEL 保证原子消费，杜绝并发重放。
     */
    public void consumeSendToken(String sendToken) {
        if (sendToken == null || sendToken.isBlank()) {
            throw new ServiceException(ErrorCode.CAPTCHA_REQUIRED);
        }
        String key = CacheConstants.CAPTCHA_SEND_PREFIX + sendToken;
        String value = redisTemplate.opsForValue().getAndDelete(key);
        if (value == null) {
            throw new ServiceException(ErrorCode.CAPTCHA_INVALID);
        }
    }

    /**
     * 消费一次性登录令牌（密码登录场景）。缺失或已失效（过期 / 已用过）均抛错。
     * 使用 GETDEL 保证原子消费，杜绝并发重放；与 {@link #consumeSendToken}
     * 各自消费不同前缀的令牌，跨场景不可混用。
     */
    public void consumeLoginToken(String loginToken) {
        if (loginToken == null || loginToken.isBlank()) {
            throw new ServiceException(ErrorCode.CAPTCHA_REQUIRED);
        }
        String key = CacheConstants.CAPTCHA_LOGIN_PREFIX + loginToken;
        String value = redisTemplate.opsForValue().getAndDelete(key);
        if (value == null) {
            throw new ServiceException(ErrorCode.CAPTCHA_INVALID);
        }
    }

    /** 挑战载荷编码：{@code 宽x高x命中半径|字符:中心x:中心y|...}（字符按点击顺序排列） */
    private static String encodeChallenge(ClickCaptchaRenderer.Result rendered, int radius) {
        StringBuilder sb = new StringBuilder()
                .append(rendered.width()).append('x').append(rendered.height())
                .append('x').append(radius);
        for (ClickCaptchaRenderer.Target t : rendered.targets()) {
            sb.append('|').append(t.ch()).append(':').append(t.x()).append(':').append(t.y());
        }
        return sb.toString();
    }

    /** 图片转 data URI（前端可直接放进 img src） */
    private static String toDataUri(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            log.error("验证码图片编码失败", e);
            throw new ServiceException(ErrorCode.CAPTCHA_RENDER_FAILED);
        }
    }

    /** 逐个比对：数量一致、顺序一致、每次点击都落在对应目标字符的命中半径内 */
    private boolean matches(String payload, List<ClickCaptchaVerifyBody.ClickPoint> clicks) {
        if (clicks == null || clicks.isEmpty()) {
            return false;
        }
        String[] parts = payload.split("\\|");
        String[] dims = parts[0].split("x");
        int width = Integer.parseInt(dims[0]);
        int height = Integer.parseInt(dims[1]);
        int radius = Integer.parseInt(dims[2]);

        int count = parts.length - 1;
        if (clicks.size() != count) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            String[] target = parts[i + 1].split(":");
            double targetX = Double.parseDouble(target[1]);
            double targetY = Double.parseDouble(target[2]);
            ClickCaptchaVerifyBody.ClickPoint point = clicks.get(i);
            double clickX = clamp01(point.getX()) * width;
            double clickY = clamp01(point.getY()) * height;
            if (Math.hypot(clickX - targetX, clickY - targetY) > radius) {
                return false;
            }
        }
        return true;
    }

    /** 记一次失败；达到上限即作废该挑战，强制换图（防在同一张图上反复试坐标） */
    private void recordFailure(String key, String token) {
        String failKey = CacheConstants.CAPTCHA_CLICK_FAIL_PREFIX + token;
        Long fails = redisTemplate.opsForValue().increment(failKey);
        redisTemplate.expire(failKey, Duration.ofSeconds(clickTtlSeconds));
        if (fails != null && fails >= maxAttempts) {
            redisTemplate.delete(key);
            redisTemplate.delete(failKey);
            log.debug("点选验证码失败次数超限，挑战已作废：token={}", token);
        }
    }

    /** 按用途签发一次性令牌（两场景前缀隔离） */
    private String issueToken(String purpose) {
        String issued = UUID.randomUUID().toString().replace("-", "");
        String prefix = "login".equals(purpose)
                ? CacheConstants.CAPTCHA_LOGIN_PREFIX
                : CacheConstants.CAPTCHA_SEND_PREFIX;
        redisTemplate.opsForValue().set(
                prefix + issued, "1", Duration.ofSeconds(tokenTtlSeconds));
        return issued;
    }

    /** 归一化坐标钳制到 0~1；NaN / 非法值返回 -1，使其必然落在命中半径之外 */
    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return -1d;
        }
        return Math.max(0d, Math.min(1d, value));
    }
}
