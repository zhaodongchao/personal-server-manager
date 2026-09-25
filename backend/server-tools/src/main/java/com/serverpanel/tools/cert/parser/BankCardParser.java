package com.serverpanel.tools.cert.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.serverpanel.tools.cert.CertParser;
import com.serverpanel.tools.cert.CertType;
import com.serverpanel.tools.cert.dto.CertFieldVO;
import com.serverpanel.tools.cert.dto.CertParseResultVO;
import com.serverpanel.tools.cert.dto.RuleItemVO;
import com.serverpanel.tools.cert.dto.RuleSectionVO;
import com.serverpanel.tools.entity.SysBankBin;
import com.serverpanel.tools.mapper.SysBankBinMapper;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 银行卡解析器（Luhn 校验 + BIN 最长前缀匹配）。
 *
 * <p>Luhn（模 10，GB/T 15694 兼容）：从右往左，奇数位（1 起）直接累加，
 * 偶数位 ×2 超 9 减 9，总和 mod 10 == 0 为合法。BIN 取库内最长前缀命中
 * 发卡行与卡种；未命中属「合法但未收录」，不判错只给 warn。
 * 明确不做卡级别（白金/金卡等公开数据不准）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Component
@RequiredArgsConstructor
public class BankCardParser implements CertParser {

    private final SysBankBinMapper binMapper;

    @Override
    public CertType type() {
        return CertType.BANK_CARD;
    }

    @Override
    public CertParseResultVO parse(String value) {
        String normalized = value.trim().replace(" ", "").replace("-", "");
        List<String> errors = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        List<CertFieldVO> fields = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();

        if (!normalized.matches("\\d{12,23}")) {
            errors.add("卡号应为 12~23 位数字，实际「"
                    + (normalized.matches("\\d+") ? normalized.length() + " 位" : "含非数字字符") + "」");
            extra.put("bankCard", Map.of("length", normalized.length()));
            return new CertParseResultVO(type().getKey(), false, "error", errors, warns, fields, extra);
        }

        boolean luhnOk = luhn(normalized);
        fields.add(new CertFieldVO("卡号长度", String.valueOf(normalized.length())));
        fields.add(new CertFieldVO("Luhn 校验", luhnOk ? "通过" : "不通过（卡号有误）"));
        if (!luhnOk) {
            errors.add("Luhn 校验不通过：卡号数字有误");
        }

        SysBankBin hit = longestPrefixBin(normalized);
        if (hit == null) {
            warns.add("BIN 未收录（可在系统管理-基础数据中维护）");
            fields.add(new CertFieldVO("发卡行", "未知（BIN 未收录）"));
        } else {
            String cardTypeText = switch (hit.getCardType()) {
                case 2 -> "贷记卡（信用卡）";
                case 3 -> "准贷记卡";
                default -> "借记卡";
            };
            fields.add(0, new CertFieldVO("BIN", hit.getBin()));
            fields.add(new CertFieldVO("发卡行", hit.getBankName()));
            fields.add(new CertFieldVO("卡种", cardTypeText));
            if (hit.getCardLen() != null) {
                boolean lenOk = normalized.length() == hit.getCardLen();
                fields.add(new CertFieldVO("卡长校验",
                        "实际 " + normalized.length() + " 位 / 标准 " + hit.getCardLen() + " 位"
                                + (lenOk ? "（相符）" : "（不符，可能为异形卡或 BIN 归属有误）")));
                if (!lenOk) {
                    warns.add("实际卡长与标准卡长不符");
                }
            }
            extra.put("bankCard", Map.of(
                    "bin", hit.getBin(),
                    "bankName", hit.getBankName(),
                    "cardType", hit.getCardType(),
                    "luhnOk", luhnOk));
        }
        return new CertParseResultVO(type().getKey(), errors.isEmpty(),
                errors.isEmpty() ? (warns.isEmpty() ? "ok" : "warn") : "error", errors, warns, fields, extra);
    }

    /** Luhn 模 10 校验 */
    private boolean luhn(String digits) {
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = Character.getNumericValue(digits.charAt(i));
            if (doubled) {
                d = d * 2 > 9 ? d * 2 - 9 : d * 2;
            }
            sum += d;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    /** BIN 最长前缀匹配（BIN 表量级小，全量加载后内存匹配） */
    private SysBankBin longestPrefixBin(String cardNo) {
        List<SysBankBin> all = binMapper.selectList(new LambdaQueryWrapper<>());
        SysBankBin best = null;
        for (SysBankBin item : all) {
            String bin = item.getBin();
            if (cardNo.startsWith(bin) && (best == null || bin.length() > best.getBin().length())) {
                best = item;
            }
        }
        return best;
    }

    @Override
    public List<RuleSectionVO> rules() {
        return List.of(
                new RuleSectionVO("结构", List.of(
                        new RuleItemVO("BIN 卡识别码（前 6~10 位）", "发卡行标识，最长前缀匹配 sys_bank_bin"),
                        new RuleItemVO("发卡行自定义位", "账户标识，由发卡行自行分配"),
                        new RuleItemVO("校验位（最后 1 位）", "Luhn 算法得出"))),
                new RuleSectionVO("Luhn 校验（模 10）", List.of(
                        new RuleItemVO("算法", "从右往左，偶数位 ×2、超过 9 则减 9，全部求和后 mod 10 == 0 为合法"),
                        new RuleItemVO("兼容性", "GB/T 15694 兼容，国际通用"))),
                new RuleSectionVO("输出说明", List.of(
                        new RuleItemVO("卡长校验", "实际位数 vs BIN 表标准位数（16/19），不符给警告不判错"),
                        new RuleItemVO("不做项", "卡级别（白金/金卡等）公开数据不准，不承诺"))));
    }
}
