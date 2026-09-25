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
 * 车牌号解析器（GA 36-2018）。
 *
 * <p>支持普通 5 位序号与新能源 6 位序号（D=纯电动 / F=插电式混动；
 * 小型车序号首位 D/F，大型车序号末位 D/F），以及使领馆牌照的说明性解析。
 * 序号字符集 0-9 A-Z 无 I/O。发牌机关字母不做城市级反查（公开对照表不全）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Component
public class PlateParser implements CertParser {

    /** 省份简称 → 大区（顺序与 GA 36 简称表一致） */
    private static final String[][] PROVINCES = {
            {"京", "华北"}, {"沪", "华东"}, {"津", "华北"}, {"渝", "西南"},
            {"冀", "华北"}, {"晋", "华北"}, {"蒙", "华北"}, {"辽", "东北"},
            {"吉", "东北"}, {"黑", "东北"}, {"苏", "华东"}, {"浙", "华东"},
            {"皖", "华东"}, {"闽", "华东"}, {"赣", "华东"}, {"鲁", "华东"},
            {"豫", "中南"}, {"鄂", "中南"}, {"湘", "中南"}, {"粤", "中南"},
            {"桂", "中南"}, {"琼", "中南"}, {"川", "西南"}, {"贵", "西南"},
            {"云", "西南"}, {"藏", "西南"}, {"陕", "西北"}, {"甘", "西北"},
            {"青", "西北"}, {"宁", "西北"}, {"新", "西北"}};

    /** 普通车牌：省 + 机关字母 + 5 位序号（序号无 I/O） */
    private static final Pattern NORMAL = Pattern.compile("^([\\u4e00-\\u9fa5])([A-Z])([A-HJ-NP-Z0-9]{5})$");

    /** 新能源车牌：省 + 机关字母 + 6 位序号（小型首位 D/F，大型末位 D/F） */
    private static final Pattern NEV = Pattern.compile("^([\\u4e00-\\u9fa5])([A-Z])(?:([DF][A-HJ-NP-Z0-9]{5})|([A-HJ-NP-Z0-9]{5}[DF]))$");

    /** 使领馆牌照：使 + 6 位数字，或「省+字母+4位数字+领」 */
    private static final Pattern EMBASSY = Pattern.compile("^使(\\d{6})$");

    @Override
    public CertType type() {
        return CertType.PLATE;
    }

    @Override
    public CertParseResultVO parse(String value) {
        String normalized = value.trim().replace(" ", "").replace("·", "").replace(".", "").toUpperCase();
        List<String> errors = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        List<CertFieldVO> fields = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();

        // 使领馆牌照（结构说明性解析）
        var embassy = EMBASSY.matcher(normalized);
        if (embassy.matches()) {
            fields.add(new CertFieldVO("车牌类型", "使领馆牌照"));
            fields.add(new CertFieldVO("编号", embassy.group(1)));
            extra.put("plate", Map.of("kind", "embassy"));
            return new CertParseResultVO(type().getKey(), true, "ok", errors, warns, fields, extra);
        }
        if (normalized.length() == 7 && normalized.endsWith("领")) {
            fields.add(new CertFieldVO("车牌类型", "使领馆牌照（领事馆）"));
            fields.add(new CertFieldVO("结构", normalized.substring(0, 3) + " · " + normalized.substring(3, 6) + " · 领"));
            extra.put("plate", Map.of("kind", "consulate"));
            return new CertParseResultVO(type().getKey(), true, "ok", errors, warns, fields, extra);
        }

        var nev = NEV.matcher(normalized);
        var normal = NORMAL.matcher(normalized);
        if (!nev.matches() && !normal.matches()) {
            errors.add("不符合车牌结构：应为「省份简称 + 发牌机关字母 + 5 位序号（普通）」"
                    + "或「… + 6 位序号（新能源）」，序号不允许 I/O");
            extra.put("plate", Map.of("length", normalized.length()));
            return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
        }
        boolean isNev = nev.matches();
        String province = normalized.substring(0, 1);
        String office = normalized.substring(1, 2);
        String serial = normalized.substring(2);

        String region = null;
        for (String[] p : PROVINCES) {
            if (p[0].equals(province)) {
                region = p[1];
                break;
            }
        }
        if (region == null) {
            errors.add("省份简称不合法：" + province);
            return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
        }

        String kindText;
        if (isNev) {
            char flag = serial.charAt(0);
            boolean small = flag == 'D' || flag == 'F';
            kindText = small ? "新能源小型车" : "新能源大型车";
            fields.add(new CertFieldVO("能源类型",
                    flag == 'D' ? "D = 纯电动" : "F = 插电式混动"));
        } else {
            kindText = "普通车牌（蓝牌）";
        }
        fields.add(0, new CertFieldVO("省份", province + "（" + region + "大区）"));
        fields.add(new CertFieldVO("车牌类型", kindText));
        fields.add(new CertFieldVO("发牌机关字母", office
                + (office.equals("O") ? "（O 多为警用/特殊号牌相关）" : "（A 常为省会城市，具体城市不做反查）")));
        fields.add(new CertFieldVO("序号", serial));

        extra.put("plate", Map.of(
                "province", province,
                "region", region,
                "kind", isNev ? "nev" : "normal",
                "serial", serial));
        return new CertParseResultVO(type().getKey(), true, "ok", errors, warns, fields, extra);
    }

    @Override
    public List<RuleSectionVO> rules() {
        return List.of(
                new RuleSectionVO("结构", List.of(
                        new RuleItemVO("省份简称（第 1 位）", "京 沪 津 渝 冀 晋 蒙 辽 吉 黑 苏 浙 皖 闽 赣 鲁 豫 鄂 湘 粤 桂 琼 川 贵 云 藏 陕 甘 青 宁 新"),
                        new RuleItemVO("发牌机关字母（第 2 位）", "A~Z；A 常为省会城市，O 多与警用/特殊号牌相关；不做城市级反查（公开对照表不全）"),
                        new RuleItemVO("序号字符集", "0-9 A-Z（无 I O，防与 1/0 混淆）"))),
                new RuleSectionVO("普通车牌（5 位序号）", List.of(
                        new RuleItemVO("结构", "省份 + 机关字母 + 5 位序号，如 京A·12345"))),
                new RuleSectionVO("新能源车牌（6 位序号）", List.of(
                        new RuleItemVO("能源标识", "D = 纯电动，F = 插电式混动"),
                        new RuleItemVO("小型车", "序号首位为 D/F，如 京AD12345"),
                        new RuleItemVO("大型车", "序号末位为 D/F，如 京A12345D"))),
                new RuleSectionVO("使领馆牌照", List.of(
                        new RuleItemVO("使馆", "「使」+ 6 位数字，如 使123456"),
                        new RuleItemVO("领事馆", "省份 + 机关字母 + 4 位数字 + 「领」，如 京A1234领"))));
    }
}
