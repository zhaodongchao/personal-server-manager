package com.serverpanel.ops.dto;

import java.util.List;

import lombok.Data;

/**
 * 「一键生效」预演结果：只看不改，供用户在确认前审阅。
 *
 * <p>它把「一键生效」9 道闸门中可在<b>不落盘</b>前提下完成的几道前置闸门一次性呈现出来：
 * 服务端 schema/黑名单校验（{@link #ruleErrors}）、宿主机预演校验（{@link #validateOutput}，
 * 即 {@code sshd -t -f <临时文件>} 这类不触碰正式路径的检查）、以及与当前托管文件的
 * 行级差异（{@link #diff}）。正式生效时会把这些闸门<b>再跑一遍</b>，绝不信任预演结果。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class ServerConfigPreviewVO {

    /** 类别键 */
    private String categoryKey;

    /** 风险级 */
    private String riskLevel;

    /** 是否需要在生效时键入关键字 */
    private Boolean needConfirm;

    /** 需键入的关键字（needConfirm=true 时非空） */
    private String applyKeyword;

    /** 将写入托管文件的完整内容 */
    private String content;

    /** 当前托管文件内容；从未托管过为 null */
    private String currentContent;

    /** 托管文件绝对路径 */
    private String targetPath;

    /** 行级 diff（{@code - 删除 / + 新增 / 空格 未变}） */
    private String diff;

    /** 托管项数量（值非空的配置项） */
    private Integer managedItemCount;

    /** 是否有变更（与当前托管文件相比） */
    private Boolean changed;

    /** 宿主机预演校验是否通过 */
    private Boolean validateOk;

    /** 预演校验输出（失败时含 sshd/sysctl 的错误文本） */
    private String validateOutput;

    /** 服务端规则校验错误（非空即不允许生效） */
    private List<String> ruleErrors;

    /** 提示性告警（不阻断生效，如「仅对新会话生效」） */
    private List<String> warnings;
}
