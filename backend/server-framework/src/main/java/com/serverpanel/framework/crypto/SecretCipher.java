package com.serverpanel.framework.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;

import lombok.extern.slf4j.Slf4j;

/**
 * 面板代管凭据的落地加密器（AES-256-GCM）。
 *
 * <p><b>为什么需要它</b>：取号数据源必须保存数据库口令才能免人工输入地连库取号。
 * 口令若明文入库，那么「库隔离」「对象前缀白名单」这些防护就形同虚设 ——
 * 任何能读面板库的人都能直接连上目标数据库。故口令一律加密后落库，
 * 明文只在内存里短暂存在。
 *
 * <p><b>为什么用 GCM 而不是 CBC</b>：GCM 自带完整性校验。若用 CBC，
 * 密文被篡改后解密会得到一段看似合法的垃圾，进而被当成口令去连库；
 * GCM 则会直接认证失败。凭据这类「被改了必须立刻知道」的数据，GCM 是正确选择。
 *
 * <p><b>密钥来源（按优先级）</b>：
 * <ol>
 *   <li>{@code serverpanel.secret.key}（可用环境变量 {@code PANEL_SECRET_KEY} 覆盖）——
 *       生产环境应采用这一档，配置一个独立于其它系统的密钥；</li>
 *   <li>从宿主代理密钥文件派生（默认 {@code /etc/psm-hostagent/secret}，
 *       容器内以 0400 只读挂载）—— 开箱即用的兜底；</li>
 *   <li>都不可用则本组件进入「不可用」状态：读取与展示照常，
 *       但保存数据源会明确报错，而不是静默降级成明文存储。</li>
 * </ol>
 *
 * <p><b>第 2 档的代价（必须知道）</b>：与宿主代理通道共用密钥，意味着轮换
 * 宿主代理密钥会让已保存的全部数据源口令解密失败。所以它只是兜底，
 * 不建议在生产长期依赖。
 *
 * <p>密文格式：{@code v1:} + Base64(IV(12B) || 密文 || GCM Tag(16B))。
 * 前缀用于给未来的密钥轮换留出识别位。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Slf4j
@Component
public class SecretCipher {

    /** 密文前缀（版本位，便于将来轮换密钥时区分新旧密文） */
    public static final String PREFIX = "v1:";

    /** 展示用掩码 */
    public static final String MASK = "******";

    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    /** 附加认证数据：把密文绑定到「面板代管凭据」这一用途上 */
    private static final byte[] AAD =
            "ServerPanel::Credential::v1".getBytes(StandardCharsets.UTF_8);

    private static final String DERIVE_SALT = "ServerPanel::Credential::derive::v1";

    private static final int PBKDF2_ITERATIONS = 100_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 为 {@code null} 表示密钥不可用（见类注释第 3 档） */
    private final SecretKey key;

    public SecretCipher(
            @Value("${serverpanel.secret.key:}") String configuredKey,
            @Value("${serverpanel.secret.hostagent-secret-file:/etc/psm-hostagent/secret}")
            String fallbackFile) {
        this.key = resolve(configuredKey, fallbackFile);
    }

    /** 是否可用（不可用时保存数据源会被明确拒绝） */
    public boolean available() {
        return key != null;
    }

    /**
     * 加密口令。
     *
     * @param plain 明文（{@code null} 原样返回，便于「不修改口令」的编辑语义）
     * @return 密文（带 {@code v1:} 前缀）
     * @throws ServiceException 密钥不可用或加密失败
     */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        requireAvailable();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new ServiceException(ErrorCode.ERROR, "口令加密失败：" + e.getMessage());
        }
    }

    /**
     * 解密口令。
     *
     * @param stored 密文（可空）
     * @return 明文；{@code null} / 空串 返回 {@code null}
     * @throws ServiceException 密钥不可用，或密文无法解出（密钥已变更、数据损坏）
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        requireAvailable();
        String body = stored.startsWith(PREFIX) ? stored.substring(PREFIX.length()) : stored;
        try {
            byte[] raw = Base64.getDecoder().decode(body);
            if (raw.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度不足");
            }
            byte[] iv = Arrays.copyOfRange(raw, 0, IV_BYTES);
            byte[] cipherText = Arrays.copyOfRange(raw, IV_BYTES, raw.length);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 走到这里几乎都是「密钥变了」：给一句能行动的提示，
            // 而不是把 AEADBadTagException 这类底层异常原样抛给用户
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_KEY_MISSING,
                    "数据源口令解密失败（加密密钥已变更或数据损坏），请重新填写口令");
        }
    }

    private void requireAvailable() {
        if (key == null) {
            throw new ServiceException(ErrorCode.TOOLS_ID_SOURCE_KEY_MISSING);
        }
    }

    // ==========================================================================
    // 密钥解析
    // ==========================================================================

    private static SecretKey resolve(String configuredKey, String fallbackFile) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            log.info("取号数据源口令加密：使用显式配置的密钥（serverpanel.secret.key）");
            return aesKey(fromConfigured(configuredKey.trim()));
        }
        if (fallbackFile != null && !fallbackFile.isBlank()) {
            Path path = Path.of(fallbackFile);
            try {
                if (Files.isReadable(path)) {
                    byte[] secret = Files.readAllBytes(path);
                    if (secret.length > 0) {
                        log.warn("取号数据源口令加密：未配置 serverpanel.secret.key，"
                                + "改为从 {} 派生。生产环境建议配置独立密钥 —— "
                                + "共用密钥意味着轮换宿主代理密钥会让全部数据源口令失效",
                                fallbackFile);
                        return aesKey(sha256(concat(secret, DERIVE_SALT)));
                    }
                }
            } catch (Exception e) {
                log.warn("从 {} 派生加密密钥失败：{}", fallbackFile, e.getMessage());
            }
        }
        log.error("取号数据源口令加密密钥不可用：既未配置 serverpanel.secret.key，"
                + "也无法从 {} 派生 → 数据源保存功能将被拒绝（读取不受影响）", fallbackFile);
        return null;
    }

    /**
     * 显式配置的密钥：先当 Base64 试（运维给强密钥的常见形式），
     * 解得的字节数必须是 AES 支持的 16/24/32；否则按口令走 PBKDF2。
     */
    private static byte[] fromConfigured(String configured) {
        try {
            byte[] decoded = Base64.getDecoder().decode(configured);
            if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // 不是 Base64，按口令处理
        }
        return pbkdf2(configured, DERIVE_SALT);
    }

    /** 任意长度种子 → 固定 32 字节 AES-256 密钥 */
    private static SecretKey aesKey(byte[] seed) {
        byte[] material = seed.length == 32 ? seed : sha256(seed);
        return new SecretKeySpec(material, "AES");
    }

    private static byte[] sha256(byte[] input) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static byte[] pbkdf2(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                    salt.getBytes(StandardCharsets.UTF_8), PBKDF2_ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }

    private static byte[] concat(byte[] a, String b) {
        byte[] tail = b.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[a.length + tail.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(tail, 0, out, a.length, tail.length);
        return out;
    }
}
