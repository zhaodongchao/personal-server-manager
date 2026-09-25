package com.serverpanel.tools.cert.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.serverpanel.tools.cert.CertParser;
import com.serverpanel.tools.cert.CertType;
import com.serverpanel.tools.cert.dto.CertFieldVO;
import com.serverpanel.tools.cert.dto.CertParseResultVO;
import com.serverpanel.tools.cert.dto.RuleItemVO;
import com.serverpanel.tools.cert.dto.RuleSectionVO;
import com.serverpanel.tools.entity.SysPhoneSegment;
import com.serverpanel.tools.mapper.SysPhoneSegmentMapper;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 手机号解析器（11 位，结构 + 号段表联查）。
 *
 * <p>结构 {@code 1[3-9]\d{9}}；按 4 位（优先）/ 3 位前缀查 sys_phone_segment
 * 得运营商与卡类型。表中无记录属「合法但未收录」，不判错只给 warn。
 * 明确不做归属地（需城市级号段映射大数据，准确性无法承诺）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Component
@RequiredArgsConstructor
public class PhoneParser implements CertParser {

    private static final Pattern PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final SysPhoneSegmentMapper segmentMapper;

    @Override
    public CertType type() {
        return CertType.PHONE;
    }

    @Override
    public CertParseResultVO parse(String value) {
        String normalized = value.trim().replace(" ", "").replace("-", "");
        List<String> errors = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        List<CertFieldVO> fields = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();

        if (!PATTERN.matcher(normalized).matches()) {
            errors.add("不符合手机号结构 1[3-9]xxxxxxxxx（11 位）");
            extra.put("phone", Map.of("length", normalized.length()));
            return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
        }

        // 4 位优先，未命中回退 3 位
        SysPhoneSegment segment = findByPrefix(normalized.substring(0, 4));
        int matchedLen = 4;
        if (segment == null) {
            segment = findByPrefix(normalized.substring(0, 3));
            matchedLen = 3;
        }
        if (segment == null) {
            warns.add("未收录号段（可能为新号段或输入有误）");
            fields.add(new CertFieldVO("号段前缀", normalized.substring(0, 3)));
            fields.add(new CertFieldVO("运营商", "未知（未收录）"));
        } else {
            String segTypeText = switch (segment.getSegType()) {
                case 2 -> "虚拟运营商";
                case 3 -> "物联卡";
                default -> "基础运营商";
            };
            fields.add(new CertFieldVO("号段前缀", normalized.substring(0, matchedLen)));
            fields.add(new CertFieldVO("运营商", segment.getOperator()));
            fields.add(new CertFieldVO("卡类型", segTypeText));
            if (segment.getNote() != null && !segment.getNote().isBlank()) {
                fields.add(new CertFieldVO("号段说明", segment.getNote()));
            }
            extra.put("phone", Map.of(
                    "prefix", normalized.substring(0, matchedLen),
                    "operator", segment.getOperator(),
                    "segType", segment.getSegType()));
        }
        return new CertParseResultVO(type().getKey(), true, warns.isEmpty() ? "ok" : "warn",
                errors, warns, fields, extra);
    }

    private SysPhoneSegment findByPrefix(String prefix) {
        return segmentMapper.selectOne(new LambdaQueryWrapper<SysPhoneSegment>()
                .eq(SysPhoneSegment::getPrefix, prefix).last("LIMIT 1"));
    }

    @Override
    public List<RuleSectionVO> rules() {
        return List.of(
                new RuleSectionVO("结构（11 位）", List.of(
                        new RuleItemVO("网号（第 1 位）", "固定为 1"),
                        new RuleItemVO("识别位（第 2 位）", "3~9（0/1/2 未启用）"),
                        new RuleItemVO("HLR 识别号（3-7 位）", "归属位置寄存器编号，与号段前缀相关"),
                        new RuleItemVO("用户号（8-11 位）", "同一 HLR 内的用户顺序号"))),
                new RuleSectionVO("运营商归属", List.of(
                        new RuleItemVO("号段表", "按 3-4 位前缀查 sys_phone_segment（基础运营商 / 虚拟运营商 / 物联卡）"),
                        new RuleItemVO("未收录", "表中无记录时输出「未收录号段」，不判错"),
                        new RuleItemVO("不做项", "手机号归属地需城市级号段映射大数据，本工具不做"))));
    }
}
