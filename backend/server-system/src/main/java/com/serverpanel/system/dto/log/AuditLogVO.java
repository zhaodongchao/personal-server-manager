package com.serverpanel.system.dto.log;

import com.serverpanel.system.entity.SysAuditLog;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志视图对象（对外契约）。
 *
 * <p>为什么不直接返回实体：
 * <ol>
 *   <li>实体字段名贴合数据库列（requestUri / durationMs / errorMsg），而前端表格按
 *       uri / costMs / error 取值，直接返回会让「URI / 耗时 / 错误」三列恒定为空；</li>
 *   <li>实体 id 是 Long（雪花），序列化成 JSON 数字后前端会丢精度，而前端用它做表格行键；</li>
 *   <li>bizCode / risky 是本次新增列，需要稳定的对外名字。</li>
 * </ol>
 * 统一成前端契约后，实体字段名可继续随数据库自由演进。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
@Data
public class AuditLogVO {

    /** 雪花 ID 字符串化，避免前端 Number 精度丢失 */
    private String id;

    /** 业务模块 system/file/ops/appstack/access */
    private String module;

    /** 动作，如 user:add、access:denied */
    private String action;

    /** 操作人登录名 */
    private String operator;

    /** 类#方法；越权记录为 "-" */
    private String method;

    /** 请求 URI */
    private String uri;

    /** 请求方法 */
    private String requestMethod;

    /** 入参 JSON（已脱敏，截断 2KB） */
    private String params;

    /** 结果类别码：0 成功 / 403 越权或无权限 / 500 执行业务方法时抛异常 */
    private Integer resultCode;

    /** 业务错误码（如 6039、1010）；成功为 null */
    private Integer bizCode;

    /** 是否高危操作：1 是 0 否 */
    private Integer risky;

    /** 错误信息 */
    private String error;

    /** 耗时毫秒 */
    private Long costMs;

    private String ip;

    private String userAgent;

    private LocalDateTime createdAt;

    /**
     * 实体 → 视图对象。
     *
     * @param entity 审计实体（可为 null）
     * @return 视图对象；入参为 null 时返回 null
     */
    public static AuditLogVO of(SysAuditLog entity) {
        if (entity == null) {
            return null;
        }
        AuditLogVO vo = new AuditLogVO();
        vo.setId(entity.getId() == null ? null : String.valueOf(entity.getId()));
        vo.setModule(entity.getModule());
        vo.setAction(entity.getAction());
        vo.setOperator(entity.getOperator());
        vo.setMethod(entity.getMethod());
        vo.setUri(entity.getRequestUri());
        vo.setRequestMethod(entity.getRequestMethod());
        vo.setParams(entity.getParams());
        vo.setResultCode(entity.getResultCode());
        vo.setBizCode(entity.getBizCode());
        vo.setRisky(entity.getRisky());
        vo.setError(entity.getErrorMsg());
        vo.setCostMs(entity.getDurationMs());
        vo.setIp(entity.getIp());
        vo.setUserAgent(entity.getUserAgent());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
