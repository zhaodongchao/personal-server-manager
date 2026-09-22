package com.serverpanel.tools.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.dto.CipherOptionVO;
import com.serverpanel.tools.dto.CryptoCodecBody;
import com.serverpanel.tools.dto.CryptoDigestBody;
import com.serverpanel.tools.dto.CryptoHmacBody;
import com.serverpanel.tools.dto.CryptoOptionsVO;
import com.serverpanel.tools.dto.CryptoSymmetricBody;
import com.serverpanel.tools.dto.OptionVO;
import org.springframework.stereotype.Service;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 字符串加解密服务：摘要、HMAC、编码、对称加解密。
 *
 * <p>能力边界：纯计算，<b>不触碰宿主任何资源</b>（不落盘、不调 hostagent、不访问
 * 数据库），因此本模块不依赖 server-system / server-ops 等业务模块，只依赖
 * server-framework —— 符合 AGENTS.md 的模块依赖方向。
 *
 * <p>算法一律走白名单映射（{@link #DIGEST_ALGS} 等），用户传入的字符串只作查表键，
 * 不参与 {@code MessageDigest.getInstance()} 的拼接，避免把算法名拼进 JCE 查询。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Service
public class CryptoService {

    /** 单次处理文本上限（字符数）：1 MiB。防止大文本打满堆内存 */
    public static final int MAX_CHARS = 1 << 20;

    /** 摘要算法白名单：前端取值 -> JCE 标准名 */
    private static final Map<String, String> DIGEST_ALGS = new LinkedHashMap<>();

    /** HMAC 算法白名单 */
    private static final Map<String, String> HMAC_ALGS = new LinkedHashMap<>();

    /** 对称算法白名单：前端取值 -> JCE 标准名 */
    private static final Map<String, String> CIPHER_ALGS = new LinkedHashMap<>();

    /** 对称算法允许的密钥字节长度（空数组 = 任意长度） */
    private static final Map<String, int[]> CIPHER_KEY_LENGTHS = new LinkedHashMap<>();

    /** 对称算法 CBC 所需 IV 字节长度（0 = 不需要） */
    private static final Map<String, Integer> CIPHER_IV_LENGTHS = new LinkedHashMap<>();

    /** 支持的分组模式 */
    private static final List<String> BLOCK_MODES = List.of("ECB", "CBC");

    /** 支持的填充 */
    private static final List<String> PADDINGS = List.of("PKCS5Padding", "NoPadding");

    static {
        DIGEST_ALGS.put("MD5", "MD5");
        DIGEST_ALGS.put("SHA1", "SHA-1");
        DIGEST_ALGS.put("SHA256", "SHA-256");
        DIGEST_ALGS.put("SHA512", "SHA-512");

        HMAC_ALGS.put("MD5", "HmacMD5");
        HMAC_ALGS.put("SHA1", "HmacSHA1");
        HMAC_ALGS.put("SHA256", "HmacSHA256");
        HMAC_ALGS.put("SHA512", "HmacSHA512");

        CIPHER_ALGS.put("AES", "AES");
        CIPHER_ALGS.put("DES", "DES");
        CIPHER_ALGS.put("3DES", "DESede");
        CIPHER_ALGS.put("RC4", "RC4");

        CIPHER_KEY_LENGTHS.put("AES", new int[]{16, 24, 32});
        CIPHER_KEY_LENGTHS.put("DES", new int[]{8});
        CIPHER_KEY_LENGTHS.put("3DES", new int[]{24});
        CIPHER_KEY_LENGTHS.put("RC4", new int[0]);

        CIPHER_IV_LENGTHS.put("AES", 16);
        CIPHER_IV_LENGTHS.put("DES", 8);
        CIPHER_IV_LENGTHS.put("3DES", 8);
        CIPHER_IV_LENGTHS.put("RC4", 0);
    }

    // ==================== 摘要 / HMAC ====================

    /**
     * 计算摘要。
     *
     * @param body 请求体
     * @return 十六进制摘要（默认小写）
     */
    public String digest(CryptoDigestBody body) {
        String text = requireText(body.getText());
        String std = DIGEST_ALGS.get(normalize(body.getAlgorithm()));
        if (std == null) {
            throw new ServiceException(ErrorCode.TOOLS_ALG_UNSUPPORTED,
                "不支持的摘要算法：" + body.getAlgorithm());
        }
        byte[] hash;
        try {
            hash = MessageDigest.getInstance(std).digest(bytes(text));
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException(ErrorCode.TOOLS_ALG_UNSUPPORTED,
                "当前 JVM 不支持该摘要算法：" + std);
        }
        String hex = java.util.HexFormat.of().formatHex(hash);
        return body.isUpper() ? hex.toUpperCase(Locale.ROOT) : hex;
    }

    /**
     * 计算 HMAC。
     *
     * @param body 请求体
     * @return 十六进制 MAC
     */
    public String hmac(CryptoHmacBody body) {
        String text = requireText(body.getText());
        byte[] key = decodeKey(body.getKey(), body.getKeyEncoding());
        String std = HMAC_ALGS.get(normalize(body.getAlgorithm()));
        if (std == null) {
            throw new ServiceException(ErrorCode.TOOLS_ALG_UNSUPPORTED,
                "不支持的 HMAC 算法：" + body.getAlgorithm());
        }
        try {
            Mac mac = Mac.getInstance(std);
            mac.init(new SecretKeySpec(key, std));
            String hex = java.util.HexFormat.of().formatHex(mac.doFinal(bytes(text)));
            return body.isUpper() ? hex.toUpperCase(Locale.ROOT) : hex;
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException(ErrorCode.TOOLS_ALG_UNSUPPORTED,
                "当前 JVM 不支持该 HMAC 算法：" + std);
        } catch (InvalidKeyException e) {
            throw new ServiceException(ErrorCode.TOOLS_KEY_INVALID,
                "密钥不合法：" + e.getMessage());
        }
    }

    // ==================== 编码 / 解码 ====================

    /**
     * Base64 / Hex 编解码。
     *
     * @param body 请求体
     * @return 编码或解码结果
     */
    public String codec(CryptoCodecBody body) {
        String text = requireText(body.getText());
        String alg = normalize(body.getAlgorithm());
        boolean encode = !"DECODE".equals(normalize(body.getOp()));
        if ("BASE64".equals(alg)) {
            if (encode) {
                return Base64.getEncoder().encodeToString(bytes(text));
            }
            return new String(decodeByEncoding(strip(text), "BASE64",
                ErrorCode.TOOLS_ENCODING_INVALID), StandardCharsets.UTF_8);
        }
        if ("HEX".equals(alg)) {
            if (encode) {
                return java.util.HexFormat.of().formatHex(bytes(text));
            }
            return new String(decodeByEncoding(strip(text), "HEX",
                ErrorCode.TOOLS_ENCODING_INVALID), StandardCharsets.UTF_8);
        }
        throw new ServiceException(ErrorCode.TOOLS_ALG_UNSUPPORTED,
            "不支持的编码方式：" + body.getAlgorithm());
    }

    // ==================== 对称加解密 ====================

    /**
     * 对称加解密。
     *
     * @param body 请求体
     * @return 加密时为编码后的密文；解密时为明文
     */
    public String symmetric(CryptoSymmetricBody body) {
        String text = requireText(body.getText());
        String alg = normalize(body.getAlgorithm());
        String std = CIPHER_ALGS.get(alg);
        if (std == null) {
            throw new ServiceException(ErrorCode.TOOLS_ALG_UNSUPPORTED,
                "不支持的对称算法：" + body.getAlgorithm());
        }
        boolean encrypt = !"DECRYPT".equals(normalize(body.getOp()));
        byte[] key = decodeKey(body.getKey(), body.getKeyEncoding());
        requireKeyLength(alg, key);

        boolean stream = "RC4".equals(alg);
        String transformation = std;
        byte[] ivBytes = null;
        if (!stream) {
            String mode = normalize(nvl(body.getMode(), "CBC"));
            String padding = nvl(body.getPadding(), "PKCS5Padding");
            if (!BLOCK_MODES.contains(mode)) {
                throw new ServiceException(ErrorCode.TOOLS_KEY_INVALID,
                    "不支持的分组模式：" + body.getMode() + "（可选 " + BLOCK_MODES + "）");
            }
            if (!PADDINGS.contains(padding)) {
                throw new ServiceException(ErrorCode.TOOLS_KEY_INVALID,
                    "不支持的填充方式：" + body.getPadding());
            }
            transformation = std + "/" + mode + "/" + padding;
            if ("CBC".equals(mode)) {
                ivBytes = decodeKey(body.getIv(), body.getIvEncoding());
                int needIv = CIPHER_IV_LENGTHS.getOrDefault(alg, 0);
                if (ivBytes.length != needIv) {
                    throw new ServiceException(ErrorCode.TOOLS_KEY_INVALID,
                        alg + " 的 IV 长度必须为 " + needIv + " 字节，当前 " + ivBytes.length + " 字节");
                }
            }
        }

        try {
            Cipher cipher = Cipher.getInstance(transformation);
            int mode = encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
            if (ivBytes != null) {
                cipher.init(mode, new SecretKeySpec(key, std), new IvParameterSpec(ivBytes));
            } else {
                cipher.init(mode, new SecretKeySpec(key, std));
            }
            byte[] input = encrypt ? bytes(text)
                : decodeByEncoding(strip(text), nvl(body.getInputEncoding(), "BASE64"),
                    ErrorCode.TOOLS_ENCODING_INVALID);
            byte[] out = cipher.doFinal(input);
            return encrypt ? encodeOutput(out, nvl(body.getOutputEncoding(), "BASE64"))
                : new String(out, StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new ServiceException(ErrorCode.TOOLS_ALG_UNSUPPORTED,
                "当前 JVM 不支持该算法：" + transformation);
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new ServiceException(ErrorCode.TOOLS_KEY_INVALID,
                "密钥或 IV 不合法：" + e.getMessage());
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new ServiceException(ErrorCode.TOOLS_CRYPTO_FAILED,
                "加解密失败：密钥、IV 或输入编码不匹配");
        }
    }

    // ==================== 可选清单 ====================

    /**
     * 下发页面可选清单（算法名与中文展示名以服务端为唯一真源）。
     *
     * @return 可选清单
     */
    public CryptoOptionsVO options() {
        CryptoOptionsVO vo = new CryptoOptionsVO();
        vo.setDigests(toOptions(DIGEST_ALGS.keySet(), Map.of(
            "MD5", "MD5（128 位，已不安全，仅兼容）",
            "SHA1", "SHA-1（160 位，已不安全，仅兼容）",
            "SHA256", "SHA-256",
            "SHA512", "SHA-512")));
        vo.setHmacs(toOptions(HMAC_ALGS.keySet(), Map.of(
            "MD5", "HmacMD5",
            "SHA1", "HmacSHA1",
            "SHA256", "HmacSHA256",
            "SHA512", "HmacSHA512")));
        vo.setEncodings(List.of(
            new OptionVO("BASE64", "Base64"),
            new OptionVO("HEX", "Hex（十六进制）")));
        List<CipherOptionVO> ciphers = new ArrayList<>();
        ciphers.add(new CipherOptionVO("AES", "AES",
            Arrays.asList(16, 24, 32), BLOCK_MODES, 16,
            "密钥 16/24/32 字节；CBC 需 16 字节 IV"));
        ciphers.add(new CipherOptionVO("DES", "DES",
            List.of(8), BLOCK_MODES, 8,
            "密钥 8 字节；已不安全，仅兼容旧系统"));
        ciphers.add(new CipherOptionVO("3DES", "3DES（DESede）",
            List.of(24), BLOCK_MODES, 8,
            "密钥 24 字节；已不安全，仅兼容旧系统"));
        ciphers.add(new CipherOptionVO("RC4", "RC4（流算法）",
            List.of(), List.of(), 0,
            "密钥任意长度；无分组模式与 IV；已不安全，仅兼容"));
        vo.setCiphers(ciphers);
        vo.setKeyEncodings(List.of(
            new OptionVO("TEXT", "明文字符串"),
            new OptionVO("HEX", "Hex"),
            new OptionVO("BASE64", "Base64")));
        vo.setCipherEncodings(List.of(
            new OptionVO("BASE64", "Base64"),
            new OptionVO("HEX", "Hex")));
        vo.setMaxChars(MAX_CHARS);
        return vo;
    }

    // ==================== 内部工具 ====================

    private static List<OptionVO> toOptions(java.util.Set<String> keys, Map<String, String> labels) {
        List<OptionVO> list = new ArrayList<>();
        for (String k : keys) {
            list.add(new OptionVO(k, labels.getOrDefault(k, k)));
        }
        return list;
    }

    private static String requireText(String text) {
        if (text == null || text.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "待处理文本不能为空");
        }
        if (text.length() > MAX_CHARS) {
            throw new ServiceException(ErrorCode.TOOLS_TEXT_TOO_LARGE,
                "文本过长：" + text.length() + " 字符，单次上限 " + MAX_CHARS + " 字符");
        }
        return text;
    }

    private static byte[] decodeKey(String raw, String encoding) {
        if (raw == null || raw.isBlank()) {
            throw new ServiceException(ErrorCode.TOOLS_KEY_INVALID, "密钥不能为空");
        }
        return decodeByEncoding(raw.trim(), encoding, ErrorCode.TOOLS_KEY_INVALID);
    }

    private static byte[] decodeByEncoding(String raw, String encoding, ErrorCode err) {
        String enc = normalize(nvl(encoding, "TEXT"));
        try {
            return switch (enc) {
                case "HEX" -> java.util.HexFormat.of().parseHex(raw);
                case "BASE64" -> Base64.getDecoder().decode(raw);
                default -> raw.getBytes(StandardCharsets.UTF_8);
            };
        } catch (IllegalArgumentException e) {
            throw new ServiceException(err, "不是合法的 " + enc + " 编码：" + e.getMessage());
        }
    }

    private static String encodeOutput(byte[] out, String encoding) {
        return "HEX".equals(normalize(nvl(encoding, "BASE64")))
            ? java.util.HexFormat.of().formatHex(out)
            : Base64.getEncoder().encodeToString(out);
    }

    private static void requireKeyLength(String alg, byte[] key) {
        int[] allowed = CIPHER_KEY_LENGTHS.get(alg);
        if (allowed == null || allowed.length == 0) {
            return;
        }
        for (int len : allowed) {
            if (key.length == len) {
                return;
            }
        }
        throw new ServiceException(ErrorCode.TOOLS_KEY_INVALID,
            alg + " 密钥长度必须为 " + Arrays.toString(allowed) + " 字节，当前 " + key.length + " 字节");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 去掉空白字符，便于粘贴带换行的 Base64/Hex */
    private static String strip(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static String nvl(String s, String def) {
        return s == null || s.isBlank() ? def : s.trim();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
