package com.serverpanel.tools.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.dto.FieldVO;
import com.serverpanel.tools.dto.ObfuscateBody;
import com.serverpanel.tools.dto.ObfuscateMethodVO;
import com.serverpanel.tools.dto.ObfuscateOptionsVO;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 可逆混淆服务：XOR、凯撒位移、倒序、Base64 嵌套、零宽字符隐写。
 *
 * <p>与加密的区别必须说清：这里全部是<b>可逆混淆</b>，<b>不具备任何安全性</b>，
 * 只能防止明文被直接阅读（防偷窥、防误读、绕过简单的关键字扫描），
 * 不能抵御任何有意的破解。界面与 {@code note} 文案会同时提示这一点。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@Service
public class ObfuscateService {

    /** 单次处理文本上限（字符数），与加解密保持一致 */
    public static final int MAX_CHARS = 1 << 20;

    /** 零宽字符：0 */
    private static final char ZERO = '\u200B';

    /** 零宽字符：1 */
    private static final char ONE = '\u200C';

    /**
     * 执行混淆或反混淆。
     *
     * @param body 请求体
     * @return 结果文本
     */
    public String transform(ObfuscateBody body) {
        String text = requireText(body.getText());
        String method = normalize(body.getMethod());
        boolean obfuscate = !"DEOBFUSCATE".equals(normalize(body.getOp()));
        Map<String, String> params = body.getParams() == null ? Map.of() : body.getParams();

        return switch (method) {
            // XOR 对称的是「异或」这一步，但混淆产物是二进制、按 Base64 输出，
            // 因此反混淆必须先把 Base64 还原成字节再异或 —— 两个方向并不共用同一段代码
            case "XOR" -> obfuscate ? xorEncode(text, params.get("key"))
                : xorDecode(text, params.get("key"));
            case "CAESAR" -> caesar(text, params.get("shift"), obfuscate);
            case "REVERSE" -> new StringBuilder(text).reverse().toString();
            case "BASE64_NESTED" -> base64Nested(text, params.get("times"), obfuscate);
            case "ZERO_WIDTH" -> obfuscate
                ? zeroWidthEncode(text, params.get("carrier"))
                : zeroWidthDecode(text);
            default -> throw new ServiceException(ErrorCode.TOOLS_METHOD_UNSUPPORTED,
                "不支持的混淆方式：" + body.getMethod());
        };
    }

    /**
     * 下发混淆方式与参数定义。
     *
     * @return 可选清单
     */
    public ObfuscateOptionsVO options() {
        ObfuscateOptionsVO vo = new ObfuscateOptionsVO();
        List<ObfuscateMethodVO> methods = new ArrayList<>();

        methods.add(new ObfuscateMethodVO("XOR", "XOR 异或",
            "按密钥字节循环异或；正反同一操作，密钥即口令", true,
            List.of(new FieldVO("key", "密钥", "text", true, "panel", "与明文逐字节异或，最长不限", null, null))));

        methods.add(new ObfuscateMethodVO("CAESAR", "凯撒位移（ROT-N）",
            "字母按 N 位平移，非字母字符不变；ROT13 即 N=13", false,
            List.of(new FieldVO("shift", "位移量", "number", true, "13", "1~25，反向操作自动取负", 1, 25))));

        methods.add(new ObfuscateMethodVO("REVERSE", "字符倒序",
            "整串反转（按 Unicode 码点，正确处理 emoji 等代理对）", true, List.of()));

        methods.add(new ObfuscateMethodVO("BASE64_NESTED", "Base64 嵌套",
            "连续做 N 次 Base64 编码/解码，让结果看起来更长更乱", false,
            List.of(new FieldVO("times", "嵌套次数", "number", true, "3", "1~5", 1, 5))));

        methods.add(new ObfuscateMethodVO("ZERO_WIDTH", "零宽字符隐写",
            "把内容编成不可见的零宽字符，可藏在一段正常文字后面", false,
            List.of(new FieldVO("carrier", "载体文本（可选）", "text", false, null,
                "混淆时拼在载体文本之后；反混淆时会自动忽略所有可见字符", null, null))));

        vo.setMethods(methods);
        vo.setMaxChars(MAX_CHARS);
        return vo;
    }

    // ==================== 各方式实现 ====================

    /** 混淆：明文 UTF-8 字节 → 异或 → Base64（二进制结果不能直接当字符串输出） */
    private static String xorEncode(String text, String key) {
        byte[] k = xorKey(key);
        byte[] src = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = (byte) (src[i] ^ k[i % k.length]);
        }
        return Base64.getEncoder().encodeToString(out);
    }

    /** 反混淆：Base64 → 异或 → 明文（得到的是原字节，可逆回原字符串） */
    private static String xorDecode(String text, String key) {
        byte[] k = xorKey(key);
        byte[] src;
        try {
            src = Base64.getDecoder().decode(text.trim().replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.TOOLS_DEOBFUSCATE_FAILED,
                "输入不是合法的 Base64，XOR 反混淆的输入应为混淆输出的 Base64 串");
        }
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = (byte) (src[i] ^ k[i % k.length]);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private static byte[] xorKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new ServiceException(ErrorCode.TOOLS_PARAM_INVALID, "XOR 需要填写密钥");
        }
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private static String caesar(String text, String shiftRaw, boolean obfuscate) {
        int shift = parseInt(shiftRaw, 13);
        if (shift < 1 || shift > 25) {
            throw new ServiceException(ErrorCode.TOOLS_PARAM_INVALID, "位移量需在 1~25 之间");
        }
        int delta = obfuscate ? shift : -shift;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append((char) ('a' + Math.floorMod(c - 'a' + delta, 26)));
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) ('A' + Math.floorMod(c - 'A' + delta, 26)));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String base64Nested(String text, String timesRaw, boolean obfuscate) {
        int times = parseInt(timesRaw, 3);
        if (times < 1 || times > 5) {
            throw new ServiceException(ErrorCode.TOOLS_PARAM_INVALID, "嵌套次数需在 1~5 之间");
        }
        String cur = text.trim();
        for (int i = 0; i < times; i++) {
            try {
                cur = obfuscate
                    ? Base64.getEncoder().encodeToString(cur.getBytes(StandardCharsets.UTF_8))
                    : new String(Base64.getDecoder().decode(cur), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new ServiceException(ErrorCode.TOOLS_DEOBFUSCATE_FAILED,
                    "第 " + (i + 1) + " 次 Base64 解码失败：输入可能不是该方式生成的，或嵌套次数不一致");
            }
        }
        return cur;
    }

    private static String zeroWidthEncode(String text, String carrier) {
        byte[] src = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(src.length * 8);
        for (byte b : src) {
            for (int i = 7; i >= 0; i--) {
                sb.append(((b >> i) & 1) == 0 ? ZERO : ONE);
            }
        }
        String hidden = sb.toString();
        return carrier == null || carrier.isEmpty() ? hidden : carrier + hidden;
    }

    private static String zeroWidthDecode(String text) {
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ZERO) {
                bits.append('0');
            } else if (c == ONE) {
                bits.append('1');
            }
        }
        int n = bits.length();
        if (n == 0 || n % 8 != 0) {
            throw new ServiceException(ErrorCode.TOOLS_DEOBFUSCATE_FAILED,
                "未提取到完整的零宽字符序列（位数 " + n + "，必须为 8 的倍数）");
        }
        byte[] out = new byte[n / 8];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    // ==================== 内部工具 ====================

    private static int parseInt(String raw, int def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(ErrorCode.TOOLS_PARAM_INVALID, "不是合法数字：" + raw);
        }
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

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
