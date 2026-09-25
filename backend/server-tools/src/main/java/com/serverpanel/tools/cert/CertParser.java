package com.serverpanel.tools.cert;

import java.util.List;

import com.serverpanel.tools.cert.dto.CertParseResultVO;
import com.serverpanel.tools.cert.dto.RuleSectionVO;

/**
 * 证件解析器（策略接口，每种证件一个实现）。
 *
 * <p>实现约定：
 * <ul>
 *   <li>结构/校验错误返回 {@code valid=false}，不抛异常 —— 「解析失败」是业务结论而非系统错误；</li>
 *   <li>合法但语义异常（未收录号段、区划未同步）不降 valid，只写 warns 并把 level 置为 warn；</li>
 *   <li>解析全程内存计算，禁止落库、禁止日志输出证件号原文（隐私红线）。</li>
 * </ul>
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
public interface CertParser {

    /** 支持的证件类型 */
    CertType type();

    /**
     * 解析证件号码。
     *
     * @param value 已归一化（trim、去空格）的证件号原文
     * @return 解析结果（valid/level/errors/warns/fields/extra）
     */
    CertParseResultVO parse(String value);

    /**
     * 构造规则文档（options 下发，前端折叠面板渲染）。
     */
    List<RuleSectionVO> rules();
}
