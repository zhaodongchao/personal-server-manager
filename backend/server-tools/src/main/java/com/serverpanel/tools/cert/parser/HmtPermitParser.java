package com.serverpanel.tools.cert.parser;

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
 * 港澳居民来往内地通行证（回乡证）解析器。
 *
 * <p>首位字母 H = 香港签发 / M = 澳门签发 + 8~10 位数字（末 2 位为换发次数）。
 * 官方未公开校验算法，本工具做结构级解析（签发地、位数、号码段拆解），不做强校验。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Component
public class HmtPermitParser implements CertParser {

    private static final Pattern PATTERN = Pattern.compile("^([HM])(\\d{8,10})$");

    @Override
    public CertType type() {
        return CertType.HMT_PERMIT;
    }

    @Override
    public CertParseResultVO parse(String value) {
        String normalized = value.trim().replace(" ", "").toUpperCase();
        List<String> errors = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        List<CertFieldVO> fields = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();

        var matcher = PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            errors.add("不符合回乡证结构：H/M 开头 + 8~10 位数字");
            extra.put("hmtPermit", Map.of("length", normalized.length()));
            return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
        }
        String prefix = matcher.group(1);
        String digits = matcher.group(2);
        String body = digits.substring(0, digits.length() - 2);
        String issueCount = digits.substring(digits.length() - 2);

        fields.add(new CertFieldVO("签发地", "H".equals(prefix) ? "H（香港特别行政区）" : "M（澳门特别行政区）"));
        fields.add(new CertFieldVO("号码主体", body + "（" + body.length() + " 位）"));
        fields.add(new CertFieldVO("换发次数", issueCount + "（末 2 位）"));
        warns.add("官方未公开校验算法，本工具仅做结构级解析，不做强校验");

        extra.put("hmtPermit", Map.of(
                "prefix", prefix,
                "body", body,
                "issueCount", issueCount));
        return new CertParseResultVO(type().getKey(), true, "warn", errors, warns, fields, extra);
    }

    @Override
    public List<RuleSectionVO> rules() {
        return List.of(
                new RuleSectionVO("结构", List.of(
                        new RuleItemVO("首位字母", "H = 香港签发，M = 澳门签发"),
                        new RuleItemVO("数字位", "8~10 位；末 2 位为换发次数（00 表示未换发）"),
                        new RuleItemVO("位数差异", "常见 8 位主体 + 2 位换发；早期证件位数不同"))),
                new RuleSectionVO("限制声明", List.of(
                        new RuleItemVO("校验能力", "官方未公开校验算法，本工具做结构级解析（签发地、位数、号码段拆解），不做强校验"))));
    }
}
