package com.serverpanel.tools.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.dto.JwtAlgorithmVO;
import com.serverpanel.tools.dto.JwtOptionsVO;
import com.serverpanel.tools.dto.JwtSignBody;
import com.serverpanel.tools.dto.JwtSignResultVO;
import com.serverpanel.tools.dto.JwtVerifyBody;
import com.serverpanel.tools.dto.JwtVerifyResultVO;
import com.serverpanel.tools.dto.OptionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JWT 服务：解码后的签名验证与签发。
 *
 * <p>职责边界：<b>只做需要密钥的两件事</b> —— 验签与签名。Header/Payload 的
 * Base64URL 解码、声明展示、{@code exp}/{@code nbf} 时间校验全部由前端完成，
 * 这样可以避免同一套解析逻辑维护两份。
 *
 * <p>安全设计：
 * <ul>
 *   <li>算法一律走白名单映射（{@link #ALGS}），用户传入的字符串只作查表键，
 *       不参与 {@code Signature.getInstance()} 的拼接。</li>
 *   <li><b>算法混淆防护</b>：Header 声明的 alg 所需的密钥类型必须与实际提供的
 *       密钥类型一致，否则直接判定不通过 —— 阻断「把 RSA 公钥当 HMAC 密钥用」
 *       这类经典绕过（HS256 + 公钥）。</li>
 *   <li>{@code alg=none} 恒判不通过，且不提供 none 的签发能力。</li>
 *   <li>HMAC 比对使用 {@link MessageDigest#isEqual} 做常量时间比较。</li>
 *   <li>不访问网络、不落盘、不调用 hostagent，因此本模块只依赖 server-framework。</li>
 * </ul>
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    /** 单次处理 token 上限（字符数）：1 MiB */
    public static final int MAX_CHARS = 1 << 20;

    /** 密钥文本上限（字符数）：64 KiB */
    public static final int MAX_KEY_CHARS = 64 * 1024;

    /** 密钥类型常量 */
    private static final String KT_HMAC = "HMAC";

    private static final String KT_RSA = "RSA";

    private static final String KT_EC = "EC";

    private static final String KT_OKP = "OKP";

    /** 算法白名单：JOSE 算法名 -> 规格 */
    private static final Map<String, AlgSpec> ALGS = new LinkedHashMap<>();

    static {
        // ---- HMAC（对称） ----
        ALGS.put("HS256", new AlgSpec("HS256", "HMAC", KT_HMAC, "HmacSHA256", null, 0, 0,
            "HMAC-SHA256，密钥为任意长度字节串（建议 ≥ 32 字节）"));
        ALGS.put("HS384", new AlgSpec("HS384", "HMAC", KT_HMAC, "HmacSHA384", null, 0, 0,
            "HMAC-SHA384，密钥为任意长度字节串（建议 ≥ 48 字节）"));
        ALGS.put("HS512", new AlgSpec("HS512", "HMAC", KT_HMAC, "HmacSHA512", null, 0, 0,
            "HMAC-SHA512，密钥为任意长度字节串（建议 ≥ 64 字节）"));
        // ---- RSA PKCS#1 v1.5 ----
        ALGS.put("RS256", new AlgSpec("RS256", "RSA", KT_RSA, "SHA256withRSA", null, 0, 0,
            "RSASSA-PKCS1-v1_5 + SHA-256，密钥为 RSA 公私钥"));
        ALGS.put("RS384", new AlgSpec("RS384", "RSA", KT_RSA, "SHA384withRSA", null, 0, 0,
            "RSASSA-PKCS1-v1_5 + SHA-384，密钥为 RSA 公私钥"));
        ALGS.put("RS512", new AlgSpec("RS512", "RSA", KT_RSA, "SHA512withRSA", null, 0, 0,
            "RSASSA-PKCS1-v1_5 + SHA-512，密钥为 RSA 公私钥"));
        // ---- RSA-PSS ----
        ALGS.put("PS256", new AlgSpec("PS256", "RSA-PSS", KT_RSA, "RSASSA-PSS", "SHA-256", 32, 0,
            "RSASSA-PSS + SHA-256，盐长度 32，密钥为 RSA 公私钥"));
        ALGS.put("PS384", new AlgSpec("PS384", "RSA-PSS", KT_RSA, "RSASSA-PSS", "SHA-384", 48, 0,
            "RSASSA-PSS + SHA-384，盐长度 48，密钥为 RSA 公私钥"));
        ALGS.put("PS512", new AlgSpec("PS512", "RSA-PSS", KT_RSA, "RSASSA-PSS", "SHA-512", 64, 0,
            "RSASSA-PSS + SHA-512，盐长度 64，密钥为 RSA 公私钥"));
        // ---- ECDSA（rawSigLen 为 r/s 单分量字节数，JOSE 要求定长拼接） ----
        ALGS.put("ES256", new AlgSpec("ES256", "ECDSA", KT_EC, "SHA256withECDSA", null, 0, 32,
            "ECDSA P-256 + SHA-256，公钥曲线必须为 secp256r1"));
        ALGS.put("ES384", new AlgSpec("ES384", "ECDSA", KT_EC, "SHA384withECDSA", null, 0, 48,
            "ECDSA P-384 + SHA-384，公钥曲线必须为 secp384r1"));
        ALGS.put("ES512", new AlgSpec("ES512", "ECDSA", KT_EC, "SHA512withECDSA", null, 0, 66,
            "ECDSA P-521 + SHA-512，公钥曲线必须为 secp521r1"));
        // ---- EdDSA ----
        ALGS.put("EdDSA", new AlgSpec("EdDSA", "EdDSA", KT_OKP, "Ed25519", null, 0, 0,
            "Ed25519（RFC 8037），密钥为 OKP 公私钥"));
    }

    private final ObjectMapper objectMapper;

    /**
     * 可选清单。
     *
     * @return 算法白名单与密钥录入方式
     */
    public JwtOptionsVO options() {
        JwtOptionsVO vo = new JwtOptionsVO();
        List<JwtAlgorithmVO> algorithms = new ArrayList<>();
        for (AlgSpec spec : ALGS.values()) {
            algorithms.add(new JwtAlgorithmVO(spec.jose(), spec.jose() + " · " + spec.family(),
                spec.family(), spec.keyType(), true, spec.note()));
        }
        vo.setAlgorithms(algorithms);

        vo.setKeyFormats(List.of(
            new OptionVO("SECRET", "对称密钥（文本）"),
            new OptionVO("PEM", "PEM 公钥 / 私钥"),
            new OptionVO("JWK", "JWK / JWKS JSON")));
        vo.setSecretEncodings(List.of(
            new OptionVO("TEXT", "纯文本"),
            new OptionVO("BASE64", "Base64"),
            new OptionVO("HEX", "Hex")));
        vo.setMaxChars(MAX_CHARS);
        return vo;
    }

    /**
     * 验证签名。
     *
     * <p>注意：验证不通过<b>不是</b>异常，而是 {@code verified=false} + 原因说明；
     * 只有输入本身不合法（段数不对、Header 不是 JSON、密钥解析失败）才抛业务异常。
     *
     * @param body 请求体
     * @return 验签结果
     */
    public JwtVerifyResultVO verify(JwtVerifyBody body) {
        String token = strip(body.getToken());
        if (token.length() > MAX_CHARS) {
            throw new ServiceException(ErrorCode.TOOLS_TEXT_TOO_LARGE,
                "token 过长：" + token.length() + " 字符，单次上限 " + MAX_CHARS + " 字符");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_INVALID_TOKEN,
                "JWT 应为 header.payload.signature 三段，实际 " + parts.length + " 段");
        }

        Map<String, Object> header = readJsonObject(parts[0], ErrorCode.TOOLS_JWT_HEADER_INVALID,
            "Header 不是合法的 Base64URL 编码 JSON");
        String alg = asString(header.get("alg"));
        if (isBlank(alg)) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_HEADER_INVALID, "Header 缺少 alg 声明");
        }
        String headerKid = asString(header.get("kid"));

        JwtVerifyResultVO vo = new JwtVerifyResultVO();
        vo.setAlgorithm(alg);
        vo.setKid(headerKid);

        AlgSpec spec = ALGS.get(alg);
        if (spec == null) {
            if ("none".equalsIgnoreCase(alg)) {
                vo.setFamily("none");
                vo.setReason("Header 声明 alg=none，未做任何签名校验（不视为验证通过）");
                return vo;
            }
            vo.setFamily("unknown");
            vo.setReason("不支持的签名算法：" + alg + "（不在白名单 " + String.join("/", ALGS.keySet()) + " 内）");
            return vo;
        }
        vo.setFamily(spec.family());

        if (parts[2].isEmpty()) {
            vo.setReason("signature 段为空，该 token 未携带签名");
            return vo;
        }

        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] signature = base64UrlDecode(parts[2], ErrorCode.TOOLS_JWT_INVALID_TOKEN,
            "signature 段不是合法的 Base64URL 编码");

        KeyMaterial material = loadKey(body.getKeyFormat(), body.getKey(), body.getSecretEncoding(), headerKid);
        vo.setKeyType(material.type());
        if (material.kid() != null) {
            vo.setKid(material.kid());
        }

        if (!spec.keyType().equals(material.type())) {
            vo.setVerified(false);
            vo.setReason("算法与密钥类型不匹配：" + alg + " 需要 " + spec.keyType() + " 类型的密钥，"
                + "实际提供的是 " + material.type() + " 类型。已拒绝验证"
                + "（防止「把非对称公钥当作 HMAC 密钥」这类算法混淆攻击）");
            return vo;
        }

        boolean passed = verifySignature(spec, signingInput, signature, material);
        vo.setVerified(passed);
        vo.setReason(passed ? "签名验证通过" : "签名验证不通过：密钥与该 token 的签名不匹配");
        return vo;
    }

    /**
     * 签发（生成签名后的）JWT。
     *
     * @param body 请求体
     * @return token 与服务端最终写入的 Header
     */
    public JwtSignResultVO sign(JwtSignBody body) {
        String algName = body.getAlgorithm() == null ? "" : body.getAlgorithm().trim();
        AlgSpec spec = ALGS.get(algName);
        if (spec == null) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_ALG_UNSUPPORTED,
                "不支持的签名算法：" + algName + "（本工具不提供 alg=none 的签发能力）");
        }

        String payload = strip(body.getPayload());
        if (payload.length() > MAX_CHARS) {
            throw new ServiceException(ErrorCode.TOOLS_TEXT_TOO_LARGE,
                "Payload 过长：" + payload.length() + " 字符，单次上限 " + MAX_CHARS + " 字符");
        }
        readJsonObject(payload, ErrorCode.TOOLS_JWT_PAYLOAD_INVALID, "Payload 必须是合法的 JSON 对象");

        Map<String, Object> headerMap = new LinkedHashMap<>();
        headerMap.put("alg", spec.jose());
        headerMap.put("typ", "JWT");
        if (!isBlank(body.getHeader())) {
            Map<String, Object> extra = readJsonObject(body.getHeader(), ErrorCode.TOOLS_JWT_HEADER_INVALID,
                "Header 附加字段必须是合法的 JSON 对象");
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                // alg 由服务端按白名单写入，禁止被覆盖
                if ("alg".equalsIgnoreCase(entry.getKey())) {
                    continue;
                }
                headerMap.put(entry.getKey(), entry.getValue());
            }
        }

        String headerJson;
        try {
            headerJson = objectMapper.writeValueAsString(headerMap);
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_HEADER_INVALID, "Header 序列化失败：" + e.getMessage());
        }

        String segment1 = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
        String segment2 = base64UrlEncode(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = segment1 + "." + segment2;

        KeyMaterial material = loadKey(body.getKeyFormat(), body.getKey(), body.getSecretEncoding(), null);
        if (!spec.keyType().equals(material.type())) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                "算法与密钥类型不匹配：" + algName + " 需要 " + spec.keyType() + " 类型的密钥，"
                    + "实际提供的是 " + material.type() + " 类型的密钥");
        }

        byte[] signature = sign(spec, signingInput.getBytes(StandardCharsets.US_ASCII), material);

        JwtSignResultVO vo = new JwtSignResultVO();
        vo.setToken(signingInput + "." + base64UrlEncode(signature));
        vo.setHeader(headerJson);
        vo.setAlgorithm(spec.jose());
        return vo;
    }

    // ==================== 签名与验签 ====================

    private boolean verifySignature(AlgSpec spec, byte[] data, byte[] signature, KeyMaterial material) {
        try {
            if (KT_HMAC.equals(spec.keyType())) {
                Mac mac = Mac.getInstance(spec.jce());
                mac.init(new SecretKeySpec(material.secret(), spec.jce()));
                return MessageDigest.isEqual(mac.doFinal(data), signature);
            }
            PublicKey publicKey = material.publicKey();
            if (publicKey == null) {
                throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                    "验签需要公钥，请提供 PEM 公钥或 JWK 公钥（不要提供私钥）");
            }
            Signature verifier = newSignature(spec);
            verifier.initVerify(publicKey);
            verifier.update(data);
            byte[] der = spec.rawSigLen() > 0 ? ecdsaRawToDer(signature, spec.rawSigLen()) : signature;
            return verifier.verify(der);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.TOOLS_CRYPTO_FAILED, "验签失败：" + e.getMessage());
        }
    }

    private byte[] sign(AlgSpec spec, byte[] data, KeyMaterial material) {
        try {
            if (KT_HMAC.equals(spec.keyType())) {
                Mac mac = Mac.getInstance(spec.jce());
                mac.init(new SecretKeySpec(material.secret(), spec.jce()));
                return mac.doFinal(data);
            }
            PrivateKey privateKey = material.privateKey();
            if (privateKey == null) {
                throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                    "签发需要私钥，请提供 PEM 私钥或带 d 的 JWK（公钥只能用于验签）");
            }
            Signature signer = newSignature(spec);
            signer.initSign(privateKey);
            signer.update(data);
            byte[] raw = signer.sign();
            return spec.rawSigLen() > 0 ? ecdsaDerToRaw(raw, spec.rawSigLen()) : raw;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_SIGN_FAILED, "签名失败：" + e.getMessage());
        }
    }

    private static Signature newSignature(AlgSpec spec) throws Exception {
        Signature signature = Signature.getInstance(spec.jce());
        if (spec.pssHash() != null) {
            MGF1ParameterSpec mgf1 = switch (spec.pssHash()) {
                case "SHA-384" -> MGF1ParameterSpec.SHA384;
                case "SHA-512" -> MGF1ParameterSpec.SHA512;
                default -> MGF1ParameterSpec.SHA256;
            };
            signature.setParameter(new PSSParameterSpec(spec.pssHash(), "MGF1", mgf1, spec.pssSaltLen(), 1));
        }
        return signature;
    }

    // ==================== 密钥装载 ====================

    private KeyMaterial loadKey(String keyFormat, String rawKey, String secretEncoding, String kid) {
        String key = rawKey == null ? "" : rawKey;
        if (key.length() > MAX_KEY_CHARS) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                "密钥过长：" + key.length() + " 字符，上限 " + MAX_KEY_CHARS);
        }
        String format = isBlank(keyFormat) ? "SECRET" : keyFormat.trim().toUpperCase(Locale.ROOT);
        return switch (format) {
            case "SECRET" -> new KeyMaterial(KT_HMAC, decodeSecret(key, secretEncoding), null, null, null);
            case "PEM" -> fromPem(key);
            case "JWK" -> fromJwk(key, kid);
            default -> throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                "不支持的密钥录入方式：" + keyFormat + "（可选 SECRET / PEM / JWK）");
        };
    }

    private byte[] decodeSecret(String key, String encoding) {
        String enc = isBlank(encoding) ? "TEXT" : encoding.trim().toUpperCase(Locale.ROOT);
        byte[] out = switch (enc) {
            case "TEXT" -> key.getBytes(StandardCharsets.UTF_8);
            case "HEX" -> fromHex(strip(key));
            case "BASE64" -> base64Loose(strip(key));
            default -> throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "不支持的密钥编码：" + encoding);
        };
        if (out.length == 0) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "对称密钥不能为空");
        }
        return out;
    }

    private KeyMaterial fromPem(String pem) {
        boolean privateKey = pem.toUpperCase(Locale.ROOT).contains("PRIVATE KEY");
        boolean publicKey = pem.toUpperCase(Locale.ROOT).contains("PUBLIC KEY");
        if (!privateKey && !publicKey) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                "不是合法的 PEM：缺少 -----BEGIN PUBLIC KEY----- 或 -----BEGIN PRIVATE KEY----- 头");
        }
        String body = pemBody(pem);
        if (isBlank(body)) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "PEM 主体为空");
        }
        byte[] der = base64Loose(body);
        if (privateKey && pem.toUpperCase(Locale.ROOT).contains("RSA PRIVATE KEY")) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                "不支持 PKCS#1 格式的 RSA 私钥（-----BEGIN RSA PRIVATE KEY-----），"
                    + "请先转换为 PKCS#8：openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem");
        }
        if (!privateKey && pem.toUpperCase(Locale.ROOT).contains("RSA PUBLIC KEY")) {
            // PKCS#1 公钥：包一层 X.509 SubjectPublicKeyInfo 头即可被 JDK 识别
            der = pkcs1PublicToSpki(der);
        }
        try {
            if (privateKey) {
                PrivateKey key = tryPrivateKey(der);
                if (key == null) {
                    throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                        "PEM 私钥解析失败：仅支持 PKCS#8（-----BEGIN PRIVATE KEY-----）的 RSA / EC / Ed25519 私钥");
                }
                String type = keyTypeOf(key.getAlgorithm());
                if (type == null) {
                    throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                        "不支持的私钥类型：" + key.getAlgorithm());
                }
                return new KeyMaterial(type, null, null, key, null);
            }
            PublicKey key = tryPublicKey(der);
            if (key == null) {
                throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                    "PEM 公钥解析失败：仅支持 X.509 SubjectPublicKeyInfo 的 RSA / EC / Ed25519 公钥");
            }
            String type = keyTypeOf(key.getAlgorithm());
            if (type == null) {
                throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                    "不支持的公钥类型：" + key.getAlgorithm());
            }
            return new KeyMaterial(type, null, key, null, null);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "PEM 解析失败：" + e.getMessage());
        }
    }

    private KeyMaterial fromJwk(String json, String kid) {
        Map<String, Object> root = readJsonObject(strip(json), ErrorCode.TOOLS_JWT_KEY_INVALID,
            "JWK 不是合法的 JSON 对象");
        Map<String, Object> jwk = root;
        if (root.get("keys") instanceof List<?> keys) {
            Map<String, Object> matched = null;
            for (Object item : keys) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> candidate = copyMap(map);
                if (!isBlank(kid) && kid.equals(asString(candidate.get("kid")))) {
                    matched = candidate;
                    break;
                }
                if (matched == null) {
                    matched = candidate;
                }
            }
            if (matched == null) {
                throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "JWKS 中不包含任何密钥");
            }
            jwk = matched;
        }

        String kty = asString(jwk.get("kty"));
        String jwkKid = asString(jwk.get("kid"));
        if (isBlank(kty)) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "JWK 缺少 kty 字段");
        }
        try {
            return switch (kty.toUpperCase(Locale.ROOT)) {
                case "OCT" -> {
                    byte[] secret = base64UrlDecode(require(jwk, "k"), ErrorCode.TOOLS_JWT_KEY_INVALID,
                        "JWK 的 k 不是合法的 Base64URL");
                    if (secret.length == 0) {
                        throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "JWK 的 k 为空");
                    }
                    yield new KeyMaterial(KT_HMAC, secret, null, null, jwkKid);
                }
                case "RSA" -> rsaFromJwk(jwk, jwkKid);
                case "EC" -> ecFromJwk(jwk, jwkKid);
                case "OKP" -> okpFromJwk(jwk, jwkKid);
                default -> throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                    "不支持的 JWK 密钥类型 kty=" + kty + "（支持 oct / RSA / EC / OKP）");
            };
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "JWK 解析失败：" + e.getMessage());
        }
    }

    private KeyMaterial rsaFromJwk(Map<String, Object> jwk, String kid) throws Exception {
        BigInteger n = new BigInteger(1, base64UrlDecode(require(jwk, "n"), ErrorCode.TOOLS_JWT_KEY_INVALID,
            "JWK 的 n 不是合法的 Base64URL"));
        BigInteger e = new BigInteger(1, base64UrlDecode(require(jwk, "e"), ErrorCode.TOOLS_JWT_KEY_INVALID,
            "JWK 的 e 不是合法的 Base64URL"));
        KeyFactory factory = KeyFactory.getInstance("RSA");
        String d = asString(jwk.get("d"));
        if (!isBlank(d)) {
            BigInteger dd = new BigInteger(1, base64UrlDecode(strip(d), ErrorCode.TOOLS_JWT_KEY_INVALID,
                "JWK 的 d 不是合法的 Base64URL"));
            String p = asString(jwk.get("p"));
            String q = asString(jwk.get("q"));
            if (!isBlank(p) && !isBlank(q)) {
                BigInteger dp = new BigInteger(1, base64UrlDecode(strip(asString(jwk.get("dp"))),
                    ErrorCode.TOOLS_JWT_KEY_INVALID, "JWK 的 dp 不是合法的 Base64URL"));
                BigInteger dq = new BigInteger(1, base64UrlDecode(strip(asString(jwk.get("dq"))),
                    ErrorCode.TOOLS_JWT_KEY_INVALID, "JWK 的 dq 不是合法的 Base64URL"));
                BigInteger qi = new BigInteger(1, base64UrlDecode(strip(asString(jwk.get("qi"))),
                    ErrorCode.TOOLS_JWT_KEY_INVALID, "JWK 的 qi 不是合法的 Base64URL"));
                PrivateKey key = factory.generatePrivate(new RSAPrivateCrtKeySpec(n, e, dd,
                    new BigInteger(1, base64UrlDecode(strip(p), ErrorCode.TOOLS_JWT_KEY_INVALID, "JWK 的 p 不合法")),
                    new BigInteger(1, base64UrlDecode(strip(q), ErrorCode.TOOLS_JWT_KEY_INVALID, "JWK 的 q 不合法")),
                    dp, dq, qi));
                return new KeyMaterial(KT_RSA, null, null, key, kid);
            }
            PrivateKey key = factory.generatePrivate(new RSAPrivateKeySpec(n, dd));
            return new KeyMaterial(KT_RSA, null, null, key, kid);
        }
        PublicKey key = factory.generatePublic(new RSAPublicKeySpec(n, e));
        return new KeyMaterial(KT_RSA, null, key, null, kid);
    }

    private KeyMaterial ecFromJwk(Map<String, Object> jwk, String kid) throws Exception {
        String crv = asString(jwk.get("crv"));
        String curveName = switch (crv == null ? "" : crv) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                "不支持的 EC 曲线 crv=" + crv + "（支持 P-256 / P-384 / P-521）");
        };
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(curveName));
        ECParameterSpec parameterSpec = parameters.getParameterSpec(ECParameterSpec.class);
        KeyFactory factory = KeyFactory.getInstance("EC");

        String d = asString(jwk.get("d"));
        if (!isBlank(d)) {
            BigInteger dd = new BigInteger(1, base64UrlDecode(strip(d), ErrorCode.TOOLS_JWT_KEY_INVALID,
                "JWK 的 d 不是合法的 Base64URL"));
            return new KeyMaterial(KT_EC, null, null,
                factory.generatePrivate(new ECPrivateKeySpec(dd, parameterSpec)), kid);
        }
        BigInteger x = new BigInteger(1, base64UrlDecode(require(jwk, "x"), ErrorCode.TOOLS_JWT_KEY_INVALID,
            "JWK 的 x 不是合法的 Base64URL"));
        BigInteger y = new BigInteger(1, base64UrlDecode(require(jwk, "y"), ErrorCode.TOOLS_JWT_KEY_INVALID,
            "JWK 的 y 不是合法的 Base64URL"));
        PublicKey key = factory.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), parameterSpec));
        return new KeyMaterial(KT_EC, null, key, null, kid);
    }

    private KeyMaterial okpFromJwk(Map<String, Object> jwk, String kid) throws Exception {
        String crv = asString(jwk.get("crv"));
        if (!"Ed25519".equalsIgnoreCase(crv == null ? "" : crv)) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                "不支持的 OKP 曲线 crv=" + crv + "（支持 Ed25519）");
        }
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        String d = asString(jwk.get("d"));
        if (!isBlank(d)) {
            byte[] seed = base64UrlDecode(strip(d), ErrorCode.TOOLS_JWT_KEY_INVALID,
                "JWK 的 d 不是合法的 Base64URL");
            return new KeyMaterial(KT_OKP, null, null,
                factory.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed)), kid);
        }
        byte[] raw = base64UrlDecode(require(jwk, "x"), ErrorCode.TOOLS_JWT_KEY_INVALID,
            "JWK 的 x 不是合法的 Base64URL");
        if (raw.length != 32) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID,
                "Ed25519 公钥应为 32 字节，实际 " + raw.length + " 字节");
        }
        // RFC 8037：x 为 32 字节小端，最高位（最后一字节的 bit7）是 x 坐标的符号位
        boolean xOdd = (raw[31] & 0x80) != 0;
        byte[] yBytes = raw.clone();
        yBytes[31] &= 0x7F;
        reverse(yBytes);
        EdECPoint point = new EdECPoint(xOdd, new BigInteger(1, yBytes));
        PublicKey key = factory.generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
        return new KeyMaterial(KT_OKP, null, key, null, kid);
    }

    private static PrivateKey tryPrivateKey(byte[] der) {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception ignored) {
                // 换下一个算法继续尝试
            }
        }
        return null;
    }

    private static PublicKey tryPublicKey(byte[] der) {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(spec);
            } catch (Exception ignored) {
                // 换下一个算法继续尝试
            }
        }
        return null;
    }

    private static String keyTypeOf(String jcaAlgorithm) {
        if (jcaAlgorithm == null) {
            return null;
        }
        return switch (jcaAlgorithm.toUpperCase(Locale.ROOT)) {
            case "RSA" -> KT_RSA;
            case "EC", "ECDSA" -> KT_EC;
            case "ED25519", "EDDSA", "ED448" -> KT_OKP;
            default -> null;
        };
    }

    // ==================== ECDSA: JOSE(raw r||s) <-> JDK(DER) ====================

    private static byte[] ecdsaRawToDer(byte[] raw, int componentLength) {
        if (raw.length != componentLength * 2) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_INVALID_TOKEN,
                "ECDSA 签名长度应为 " + (componentLength * 2) + " 字节，实际 " + raw.length + " 字节");
        }
        byte[] r = toDerInteger(Arrays.copyOfRange(raw, 0, componentLength));
        byte[] s = toDerInteger(Arrays.copyOfRange(raw, componentLength, componentLength * 2));
        return derWrap(0x30, concat(r, s));
    }

    private static byte[] ecdsaDerToRaw(byte[] der, int componentLength) {
        DerCursor cursor = new DerCursor(der);
        cursor.expect(0x30);
        cursor.readLength();
        byte[] r = cursor.readInteger();
        byte[] s = cursor.readInteger();
        byte[] out = new byte[componentLength * 2];
        writeFixed(r, out, 0, componentLength);
        writeFixed(s, out, componentLength, componentLength);
        return out;
    }

    private static byte[] toDerInteger(byte[] value) {
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) {
            start++;
        }
        byte[] trimmed = Arrays.copyOfRange(value, start, value.length);
        byte[] body = (trimmed[0] & 0x80) != 0 ? concat(new byte[]{0}, trimmed) : trimmed;
        return derWrap(0x02, body);
    }

    private static void writeFixed(byte[] source, byte[] target, int offset, int length) {
        if (source.length > length) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_INVALID_TOKEN, "ECDSA 签名分量长度超出预期");
        }
        System.arraycopy(source, 0, target, offset + length - source.length, source.length);
    }

    private static byte[] derWrap(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeDerLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
        out.write(0x80 | bytes);
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((length >>> (i * 8)) & 0xFF);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static void reverse(byte[] value) {
        for (int i = 0, j = value.length - 1; i < j; i++, j--) {
            byte tmp = value[i];
            value[i] = value[j];
            value[j] = tmp;
        }
    }

    /** 极简 DER 读取游标（只用于解析 ECDSA 的 {@code SEQUENCE{INTEGER,INTEGER}}） */
    private static final class DerCursor {

        private final byte[] buffer;

        private int position;

        DerCursor(byte[] buffer) {
            this.buffer = buffer;
        }

        void expect(int tag) {
            if (position >= buffer.length || (buffer[position] & 0xFF) != tag) {
                throw malformed();
            }
            position++;
        }

        int readLength() {
            if (position >= buffer.length) {
                throw malformed();
            }
            int first = buffer[position++] & 0xFF;
            if (first < 0x80) {
                return first;
            }
            int count = first & 0x7F;
            if (count == 0 || count > 4 || position + count > buffer.length) {
                throw malformed();
            }
            int length = 0;
            for (int i = 0; i < count; i++) {
                length = (length << 8) | (buffer[position++] & 0xFF);
            }
            return length;
        }

        byte[] readInteger() {
            expect(0x02);
            int length = readLength();
            if (length <= 0 || position + length > buffer.length) {
                throw malformed();
            }
            byte[] value = Arrays.copyOfRange(buffer, position, position + length);
            position += length;
            return value;
        }

        private static ServiceException malformed() {
            return new ServiceException(ErrorCode.TOOLS_JWT_INVALID_TOKEN, "签名段不是合法的 DER 编码 ECDSA 签名");
        }
    }

    // ==================== 编解码辅助 ====================

    private static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] base64UrlDecode(String value, ErrorCode code, String message) {
        try {
            return Base64.getUrlDecoder().decode(padBase64(strip(value)));
        } catch (Exception e) {
            throw new ServiceException(code, message);
        }
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }

    /** Base64 宽松解码：标准字母表与 URL 字母表都试一遍（粘贴来源不固定） */
    private static byte[] base64Loose(String value) {
        String normalized = strip(value);
        if (normalized.isEmpty()) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "Base64 内容为空");
        }
        String padded = padBase64(normalized);
        try {
            return Base64.getDecoder().decode(padded);
        } catch (Exception ignored) {
            // 继续尝试 URL 字母表
        }
        try {
            return Base64.getUrlDecoder().decode(padded);
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.TOOLS_ENCODING_INVALID, "不是合法的 Base64 内容");
        }
    }

    private static byte[] fromHex(String value) {
        String hex = strip(value);
        if (hex.length() % 2 != 0) {
            throw new ServiceException(ErrorCode.TOOLS_ENCODING_INVALID, "Hex 长度必须为偶数");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new ServiceException(ErrorCode.TOOLS_ENCODING_INVALID, "不是合法的 Hex 内容");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private Map<String, Object> readJsonObject(String json, ErrorCode code, String message) {
        Object parsed;
        try {
            parsed = objectMapper.readValue(strip(json), Map.class);
        } catch (Exception e) {
            throw new ServiceException(code, message);
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new ServiceException(code, message);
        }
        return copyMap(map);
    }

    private static Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static String require(Map<String, Object> jwk, String field) {
        String value = asString(jwk.get(field));
        if (isBlank(value)) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "JWK 缺少必需字段 " + field);
        }
        return strip(value);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 去掉全部空白字符（token 与 Base64 粘贴时常带换行） */
    private static String strip(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 提取 PEM 的 Base64 主体（去掉 BEGIN/END 行与所有换行） */
    private static String pemBody(String pem) {
        StringBuilder out = new StringBuilder();
        boolean inside = false;
        for (String line : pem.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-----BEGIN")) {
                inside = true;
                continue;
            }
            if (trimmed.startsWith("-----END")) {
                break;
            }
            if (inside) {
                out.append(trimmed);
            }
        }
        if (out.length() == 0) {
            // 容错：单行 PEM
            return strip(pem.replaceAll("-----[^-]+-----", ""));
        }
        return out.toString();
    }

    /**
     * PKCS#1 RSA 公钥（{@code SEQUENCE{INTEGER n, INTEGER e}}）包装成
     * X.509 SubjectPublicKeyInfo，这样 JDK 的 X509EncodedKeySpec 才能识别。
     */
    private static byte[] pkcs1PublicToSpki(byte[] pkcs1) {
        DerCursor cursor = new DerCursor(pkcs1);
        cursor.expect(0x30);
        int length = cursor.readLength();
        int contentStart = cursor.position;
        if (contentStart + length > pkcs1.length) {
            throw new ServiceException(ErrorCode.TOOLS_JWT_KEY_INVALID, "PKCS#1 公钥 DER 长度异常");
        }
        byte[] inner = Arrays.copyOfRange(pkcs1, contentStart, contentStart + length);
        // AlgorithmIdentifier: OID 1.2.840.113549.1.1.1 + NULL
        byte[] algorithmId = new byte[]{
            0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] bitString = derWrap(0x03, concat(new byte[]{0}, inner));
        return derWrap(0x30, concat(algorithmId, bitString));
    }

    /**
     * 算法规格。
     *
     * @param jose      JOSE 算法名
     * @param family    算法族（界面展示）
     * @param keyType   所需密钥类型：HMAC / RSA / EC / OKP
     * @param jce       JCE 算法名
     * @param pssHash   RSASSA-PSS 的摘要名（仅 PS*，其余为 null）
     * @param pssSaltLen RSASSA-PSS 的盐长度（仅 PS*）
     * @param rawSigLen ECDSA 原始签名单分量字节数（仅 ES*，其余为 0）
     * @param note      说明
     */
    private record AlgSpec(String jose, String family, String keyType, String jce,
                           String pssHash, int pssSaltLen, int rawSigLen, String note) {
    }

    /**
     * 密钥材料。
     *
     * @param type       HMAC / RSA / EC / OKP
     * @param secret     对称密钥（仅 HMAC）
     * @param publicKey  公钥（非对称）
     * @param privateKey 私钥（非对称，仅签发时存在）
     * @param kid        JWK 中的密钥 id
     */
    private record KeyMaterial(String type, byte[] secret, PublicKey publicKey, PrivateKey privateKey, String kid) {
    }
}
