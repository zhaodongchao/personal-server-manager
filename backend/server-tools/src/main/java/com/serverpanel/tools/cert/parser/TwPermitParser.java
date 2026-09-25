package com.serverpanel.tools.cert.parser;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.serverpanel.tools.cert.CertParser;
import com.serverpanel.tools.cert.CertType;
import com.serverpanel.tools.cert.dto.CertFieldVO;
import com.serverpanel.tools.cert.dto.CertParseResultVO;
import com.serverpanel.tools.cert.dto.RuleItemVO;
import com.serverpanel.tools.cert.dto.RuleSectionVO;
import org.springframework.stereotype.Component;

/**
 * 台湾居民来往大陆通行证解析器（台胞证 / 台湾居民居住证双格式）。
 *
 * <p>旧版（纸本）：8 位号码 + 2 位签注次数，共 10 位。
 * 台湾居民居住证（2018+）：18 位，地址码固定 830000（台湾省）+ 出生日期 8 位
 * + 顺序码 3 位 + 校验码 1 位，校验算法与身份证一致（GB 11643 同款 MOD 11-2）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Component
public class TwPermitParser implements CertParser {

    /** 台湾居民居住证地址码（台湾省） */
    private static final String TW_ADDR = "830000";

    private static final Pattern OLD = Pattern.compile("^(\\d{8})(\\d{2})$");

    /** MOD 11-2 权重与映射（与身份证同款） */
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final String CHECK_MAP = "10X98765432";

    @Override
    public CertType type() {
        return CertType.TW_PERMIT;
    }

    @Override
    public CertParseResultVO parse(String value) {
        String normalized = value.trim().replace(" ", "").toUpperCase();
        List<String> errors = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        List<CertFieldVO> fields = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();

        if (normalized.length() == 10) {
            var matcher = OLD.matcher(normalized);
            if (!matcher.matches()) {
                errors.add("10 位台胞证应为数字");
                return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
            }
            fields.add(new CertFieldVO("证件版本", "台胞证（旧版纸本）"));
            fields.add(new CertFieldVO("号码主体", matcher.group(1) + "（8 位）"));
            fields.add(new CertFieldVO("签注次数", matcher.group(2) + "（末 2 位）"));
            warns.add("旧版台胞证无公开校验算法，仅做结构解析");
            extra.put("twPermit", Map.of("version", "old", "body", matcher.group(1),
                    "endorsement", matcher.group(2)));
            return new CertParseResultVO(type().getKey(), true, "warn", errors, warns, fields, extra);
        }

        if (normalized.length() == 18) {
            // 居住证：地址码 830000 + 出生日期 + 顺序码 + 校验码
            if (!normalized.startsWith(TW_ADDR)) {
                errors.add("18 位应为台湾居民居住证（地址码 " + TW_ADDR + " 开头）；"
                        + "若为大陆身份证请使用「身份证」页签");
                extra.put("twPermit", Map.of("version", "unknown"));
                return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
            }
            String birth = normalized.substring(6, 14);
            String sequence = normalized.substring(14, 17);
            String check = normalized.substring(17);
            LocalDate birthDate = null;
            try {
                birthDate = LocalDate.parse(birth, DateTimeFormatter.BASIC_ISO_DATE);
            } catch (RuntimeException e) {
                errors.add("出生日期不是真实日历日期：" + birth);
            }
            String expected = checkDigit(normalized.substring(0, 17));
            boolean checkOk = check.equals(expected);
            if (!checkOk) {
                errors.add("校验码不符：第 18 位应为 " + expected + "，实际 " + check);
            }
            if (birthDate != null) {
                fields.add(new CertFieldVO("出生日期", birthDate.format(DateTimeFormatter.ISO_LOCAL_DATE)));
            }
            boolean male = Character.getNumericValue(sequence.charAt(2)) % 2 == 1;
            fields.add(0, new CertFieldVO("证件版本", "台湾居民居住证（2018+）"));
            fields.add(new CertFieldVO("地址码", TW_ADDR + "（台湾省）"));
            fields.add(new CertFieldVO("性别", male ? "男" : "女"));
            fields.add(new CertFieldVO("校验码", check + (checkOk ? "（校验通过）" : "（校验失败）")));
            extra.put("twPermit", Map.of("version", "residence", "birth", birth,
                    "sequence", sequence, "check", check, "checkOk", checkOk));
            boolean valid = errors.isEmpty();
            return new CertParseResultVO(type().getKey(), valid,
                    valid ? "ok" : "error", errors, warns, fields, extra);
        }

        errors.add("长度应为 10 位（旧版台胞证）或 18 位（台湾居民居住证），实际 "
                + normalized.length() + " 位");
        extra.put("twPermit", Map.of("length", normalized.length()));
        return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
    }

    private String checkDigit(String first17) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += Character.getNumericValue(first17.charAt(i)) * WEIGHTS[i];
        }
        return String.valueOf(CHECK_MAP.charAt(sum % 11));
    }

    @Override
    public List<RuleSectionVO> rules() {
        return List.of(
                new RuleSectionVO("旧版（纸本，10 位）", List.of(
                        new RuleItemVO("号码主体", "8 位数字"),
                        new RuleItemVO("签注次数", "末 2 位数字"),
                        new RuleItemVO("校验能力", "无公开校验算法，仅结构解析"))),
                new RuleSectionVO("台湾居民居住证（2018+，18 位）", List.of(
                        new RuleItemVO("地址码", "固定 830000（台湾省）"),
                        new RuleItemVO("出生日期码", "YYYYMMDD，校验真实日历日期"),
                        new RuleItemVO("顺序码", "3 位，奇数 = 男，偶数 = 女"),
                        new RuleItemVO("校验码", "与身份证同款 ISO 7064 MOD 11-2，做完整校验"))));
    }
}
