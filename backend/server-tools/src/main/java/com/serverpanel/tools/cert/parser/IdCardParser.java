package com.serverpanel.tools.cert.parser;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
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
 * 身份证解析器（GB 11643-1999）。
 *
 * <p>结构：6 位地址码（GB/T 2260）+ 8 位出生日期码 + 3 位顺序码 + 1 位校验码。
 * 校验码为 ISO 7064:1983 MOD 11-2。15 位老证自动升位为 18 位后解析
 * （出生年按 19xx 补全）。扩展输出年龄/生肖/星座；区划联查
 * {@link RegionLookupService}，未同步或未命中时降级并在 warns 说明。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Component
@RequiredArgsConstructor
public class IdCardParser implements CertParser {

    /** MOD 11-2 权重（i=1..17） */
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 校验码映射：Y 0..10 → 1 0 X 9 8 7 6 5 4 3 2 */
    private static final String CHECK_MAP = "10X98765432";

    /** 生肖（年份 (y-4) mod 12 对应下标） */
    private static final String[] ZODIAC = {"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};

    /** 星座起始（月, 日）与名称 */
    private static final int[] CONS_START = {120, 219, 321, 420, 521, 622, 723, 823, 923, 1024, 1123, 1222};
    private static final String[] CONS_NAME = {"水瓶座", "双鱼座", "白羊座", "金牛座", "双子座", "巨蟹座",
        "狮子座", "处女座", "天秤座", "天蝎座", "射手座", "摩羯座"};

    private final RegionLookupService regionLookup;

    @Override
    public CertType type() {
        return CertType.ID_CARD;
    }

    @Override
    public CertParseResultVO parse(String value) {
        String normalized = value.trim().replace(" ", "").toUpperCase();
        List<String> errors = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        List<CertFieldVO> fields = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();
        boolean upgradedFrom15 = false;

        String id18;
        if (normalized.length() == 15) {
            if (!normalized.chars().allMatch(Character::isDigit)) {
                errors.add("15 位老身份证应全为数字");
                return fail(normalized, errors, warns, fields, extra);
            }
            upgradedFrom15 = true;
            id18 = normalized.substring(0, 6) + "19" + normalized.substring(6);
            id18 = id18 + checkDigit(id18);
            warns.add("15 位老身份证：已按 19xx 年补全出生年份升位为 18 位");
        } else if (normalized.length() == 18) {
            id18 = normalized;
        } else {
            errors.add("长度应为 15 或 18 位，实际 " + normalized.length() + " 位");
            return fail(normalized, errors, warns, fields, extra);
        }

        String addr = id18.substring(0, 6);
        String birth = id18.substring(6, 14);
        String sequence = id18.substring(14, 17);
        String check = id18.substring(17);

        // 地址码：全 0 或首位非法直接判错
        if (!addr.chars().allMatch(Character::isDigit) || addr.charAt(0) == '0') {
            errors.add("地址码不合法：" + addr);
        }
        // 出生日期：真实日历校验（含闰年 0229）
        LocalDate birthDate = null;
        if (birth.chars().allMatch(Character::isDigit)) {
            try {
                birthDate = LocalDate.parse(birth, DateTimeFormatter.BASIC_ISO_DATE);
                if (birthDate.isAfter(LocalDate.now())) {
                    errors.add("出生日期在未来：" + birthDate);
                }
            } catch (RuntimeException e) {
                errors.add("出生日期不是真实日历日期：" + birth);
            }
        } else {
            errors.add("出生日期码应全为数字：" + birth);
        }
        // 顺序码与校验码
        if (!sequence.chars().allMatch(Character::isDigit)) {
            errors.add("顺序码应全为数字：" + sequence);
        }
        String expected = checkDigit(id18.substring(0, 17));
        boolean checkOk = check.equals(expected);
        if (!checkOk) {
            errors.add("校验码不符：第 18 位应为 " + expected + "，实际 " + check);
        }

        // 区划联查（未同步/未命中降级为省级或提示）
        String regionText = "";
        RegionLookupService.Result region = regionLookup.resolve6(addr);
        if (region.status() == RegionLookupService.Status.READY) {
            regionText = region.full();
            if (region.partial()) {
                warns.add("6 位地址码未精确命中（可能为已撤销的老区划），仅识别到上级区划");
            }
        } else if (region.status() == RegionLookupService.Status.NOT_READY) {
            warns.add("行政区划数据尚未同步，区划解析已降级（可在系统管理-基础数据中同步）");
        } else {
            warns.add("地址码 " + addr + " 未收录（可能为历史区划代码）");
        }

        boolean valid = errors.isEmpty();
        if (valid) {
            if (birthDate != null) {
                fields.add(new CertFieldVO("出生日期", birthDate.format(DateTimeFormatter.ISO_LOCAL_DATE)));
                int age = Period.between(birthDate, LocalDate.now()).getYears();
                fields.add(new CertFieldVO("年龄", age + " 岁（周岁）"));
                fields.add(new CertFieldVO("生肖", zodiac(birthDate.getYear())));
                fields.add(new CertFieldVO("星座", constellation(birthDate.getMonthValue(), birthDate.getDayOfMonth())));
            }
            boolean male = Character.getNumericValue(sequence.charAt(2)) % 2 == 1;
            fields.add(new CertFieldVO("性别", male ? "男" : "女"));
            fields.add(new CertFieldVO("校验码", check + (checkOk ? "（校验通过）" : "（校验失败）")));
        } else if (birthDate != null) {
            fields.add(new CertFieldVO("出生日期", birthDate.format(DateTimeFormatter.ISO_LOCAL_DATE)));
        }
        if (!regionText.isEmpty()) {
            fields.add(0, new CertFieldVO("行政区划", regionText));
        } else if (valid) {
            fields.add(0, new CertFieldVO("行政区划", addr + "（未联查到区划库）"));
        }

        extra.put("idCard", Map.of(
                "code6", addr,
                "birth", birth,
                "sequence", sequence,
                "check", check,
                "checkOk", checkOk,
                "upgradedFrom15", upgradedFrom15));
        String level = valid ? (warns.isEmpty() ? "ok" : "warn") : "error";
        return new CertParseResultVO(type().getKey(), valid, level, errors, warns, fields, extra);
    }

    /** 失败骨架（长度不足时 also 填 extra 便于前端展示结构） */
    private CertParseResultVO fail(String normalized, List<String> errors, List<String> warns,
                                   List<CertFieldVO> fields, Map<String, Object> extra) {
        extra.put("idCard", Map.of("valueLength", normalized.length()));
        return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
    }

    /** MOD 11-2 校验码计算 */
    private String checkDigit(String first17) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += Character.getNumericValue(first17.charAt(i)) * WEIGHTS[i];
        }
        return String.valueOf(CHECK_MAP.charAt(sum % 11));
    }

    private String zodiac(int year) {
        return ZODIAC[Math.floorMod(year - 4, 12)];
    }

    private String constellation(int month, int day) {
        int md = month * 100 + day;
        for (int i = 0; i < CONS_START.length; i++) {
            if (md < CONS_START[i]) {
                return CONS_NAME[(i + CONS_NAME.length - 1) % CONS_NAME.length];
            }
        }
        return CONS_NAME[CONS_NAME.length - 1];
    }

    @Override
    public List<RuleSectionVO> rules() {
        return List.of(
                new RuleSectionVO("结构（18 位）", List.of(
                        new RuleItemVO("地址码（1-6 位）", "GB/T 2260 行政区划代码：前 2 位省、3-4 位市、5-6 位区县；"
                                + "首位大区：1 华北、2 东北、3 华东、4 中南、5 西南、6 西北、7 台湾、81 香港、82 澳门"),
                        new RuleItemVO("出生日期码（7-14 位）", "YYYYMMDD，校验真实日历日期（含闰年 0229）"),
                        new RuleItemVO("顺序码（15-17 位）", "同一地址码同日出生人员的顺序编号；奇数 = 男，偶数 = 女"),
                        new RuleItemVO("校验码（第 18 位）", "ISO 7064:1983 MOD 11-2，可能为数字或 X"))),
                new RuleSectionVO("校验算法（MOD 11-2）", List.of(
                        new RuleItemVO("权重 Wi", "2^(18-i) mod 11，即 7 9 10 5 8 4 2 1 6 3 7 9 10 5 8 4 2（i=1..17）"),
                        new RuleItemVO("计算", "S = Σ(第 i 位 × Wi)，Y = S mod 11"),
                        new RuleItemVO("映射", "Y 0~10 → 1 0 X 9 8 7 6 5 4 3 2"))),
                new RuleSectionVO("15 位老证升位", List.of(
                        new RuleItemVO("结构", "6 地址 + 6 位出生（YYMMDD）+ 3 顺序，无校验码"),
                        new RuleItemVO("升位规则", "出生年补 19，末位补按 MOD 11-2 计算的校验码"))),
                new RuleSectionVO("扩展输出", List.of(
                        new RuleItemVO("年龄 / 生肖 / 星座", "按出生日期计算周岁；生肖按年份，星座按月日"),
                        new RuleItemVO("区划联查", "地址码在行政区划库联查「省-市-区县」全称；数据未同步时降级"))));
    }
}
