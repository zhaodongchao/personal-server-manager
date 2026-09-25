package com.serverpanel.tools.cert.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.serverpanel.tools.cert.CertParser;
import com.serverpanel.tools.cert.CertType;
import com.serverpanel.tools.cert.dto.CertFieldVO;
import com.serverpanel.tools.cert.dto.CertParseResultVO;
import com.serverpanel.tools.cert.dto.RuleItemVO;
import com.serverpanel.tools.cert.dto.RuleSectionVO;
import com.serverpanel.tools.service.RegionLookupService;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 统一社会信用代码解析器（GB 32100-2015）。
 *
 * <p>结构：1 位登记管理部门 + 1 位机构类别 + 6 位登记机关区划码
 * + 9 位主体标识码 + 1 位校验码。字符集 0-9 + ABCDEFGHJKLMNPQRTUWXY
 * （无 I/O/S/V/Z 防混淆）。校验码 MOD 31：权重 Wi = 3^(i-1) mod 31，
 * c18 = 31 - (Σ vi×Wi mod 31)，结果 31 取 0。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Component
@RequiredArgsConstructor
public class UsccParser implements CertParser {

    /** 字符集（无 I、O、S、V、Z） */
    private static final String CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY";

    /** 权重 Wi = 3^(i-1) mod 31（i=1..17） */
    private static final int[] WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};

    /** 第 1 位登记管理部门 */
    private static final Map<String, String> DEPARTMENTS = Map.ofEntries(
            Map.entry("1", "机构编制"), Map.entry("2", "外交"), Map.entry("3", "司法行政"),
            Map.entry("4", "文化"), Map.entry("5", "民政"), Map.entry("6", "旅游"),
            Map.entry("7", "宗教"), Map.entry("8", "工会"), Map.entry("9", "工商（市场主体）"),
            Map.entry("N", "农业"), Map.entry("Y", "其他"));

    private final RegionLookupService regionLookup;

    @Override
    public CertType type() {
        return CertType.USCC;
    }

    @Override
    public CertParseResultVO parse(String value) {
        String normalized = value.trim().replace(" ", "").toUpperCase();
        List<String> errors = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        List<CertFieldVO> fields = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();

        if (normalized.length() != 18) {
            errors.add("长度应为 18 位，实际 " + normalized.length() + " 位");
            extra.put("uscc", Map.of("length", normalized.length()));
            return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
        }
        for (int i = 0; i < 18; i++) {
            if (CHARSET.indexOf(normalized.charAt(i)) < 0) {
                errors.add("第 " + (i + 1) + " 位字符不合法：" + normalized.charAt(i)
                        + "（字符集为 0-9 与 A-Z 去除 I、O、S、V、Z）");
                return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
            }
        }

        String dept = DEPARTMENTS.get(normalized.substring(0, 1));
        String orgCategory = normalized.substring(1, 2);
        String regionCode = normalized.substring(2, 8);
        String subject = normalized.substring(8, 17);
        String check = normalized.substring(17);

        fields.add(new CertFieldVO("登记管理部门", dept != null ? dept : "未知（" + normalized.charAt(0) + "）"));
        fields.add(new CertFieldVO("机构类别", orgCategory));

        RegionLookupService.Result region = regionLookup.resolve6(regionCode);
        if (region.status() == RegionLookupService.Status.READY) {
            fields.add(new CertFieldVO("登记机关区划", region.full() + "（" + regionCode + "）"));
            if (region.partial()) {
                warns.add("登记机关区划码未精确命中，仅识别到上级区划");
            }
        } else if (region.status() == RegionLookupService.Status.NOT_READY) {
            fields.add(new CertFieldVO("登记机关区划", regionCode));
            warns.add("行政区划数据尚未同步，区划解析已降级（可在系统管理-基础数据中同步）");
        } else {
            fields.add(new CertFieldVO("登记机关区划", regionCode + "（未收录）"));
            warns.add("登记机关区划码 " + regionCode + " 未收录");
        }
        fields.add(new CertFieldVO("主体标识码", subject));

        String expected = checkDigit(normalized.substring(0, 17));
        boolean checkOk = check.equals(expected);
        fields.add(new CertFieldVO("校验码", check + (checkOk ? "（校验通过）" : "（校验失败，应为 " + expected + "）")));
        if (!checkOk) {
            errors.add("校验码不符：第 18 位应为 " + expected + "，实际 " + check);
        }

        extra.put("uscc", Map.of(
                "department", dept == null ? "" : dept,
                "orgCategory", orgCategory,
                "regionCode", regionCode,
                "subject", subject,
                "check", check,
                "checkOk", checkOk));
        boolean valid = errors.isEmpty();
        return new CertParseResultVO(type().getKey(), valid,
                valid ? (warns.isEmpty() ? "ok" : "warn") : "error", errors, warns, fields, extra);
    }

    /** MOD 31 校验码计算 */
    private String checkDigit(String first17) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += CHARSET.indexOf(first17.charAt(i)) * WEIGHTS[i];
        }
        int c18 = 31 - (sum % 31);
        if (c18 == 31) {
            c18 = 0;
        }
        return String.valueOf(CHARSET.charAt(c18));
    }

    @Override
    public List<RuleSectionVO> rules() {
        RuleSectionVO structure = new RuleSectionVO("结构（18 位）", List.of(
                new RuleItemVO("字符集", "0-9 + ABCDEFGHJKLMNPQRTUWXY（无 I、O、S、V、Z，防混淆）"),
                new RuleItemVO("登记管理部门（第 1 位）", "1 机构编制、2 外交、3 司法行政、4 文化、5 民政、"
                        + "6 旅游、7 宗教、8 工会、9 工商（市场主体）、N 农业、Y 其他"),
                new RuleItemVO("机构类别（第 2 位）", "登记管理部门内的机构类别细分"),
                new RuleItemVO("登记机关区划（3-8 位）", "登记管理机关行政区划码，联查行政区划库"),
                new RuleItemVO("主体标识码（9-17 位）", "原组织机构代码（9 位）")));
        RuleSectionVO checksum = new RuleSectionVO("校验算法（MOD 31）", List.of(
                new RuleItemVO("字符值 v", "数字 = 面值；字母 = 序位 + 10（A=10 … Y=30）"),
                new RuleItemVO("权重 Wi", "3^(i-1) mod 31（i=1..17）"),
                new RuleItemVO("计算", "c18 = 31 - (Σ vi×Wi mod 31)，结果 31 取 0")));
        return List.of(structure, checksum);
    }
}
