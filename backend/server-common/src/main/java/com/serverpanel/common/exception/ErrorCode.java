package com.serverpanel.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码。
 *
 * <p>分段规则：0 成功；4xx/5xx 通用 HTTP 语义；1xxx 认证；2xxx 系统管理；
 * 3xxx 监控；4xxx 文件；5xxx 运维；6xxx 应用栈与 Nginx 管理。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ===== 通用 =====
    SUCCESS(0, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或会话已过期"),
    FORBIDDEN(403, "无操作权限"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方式不支持"),
    PAYLOAD_TOO_LARGE(413, "请求体过大"),
    ERROR(500, "系统内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),

    // ===== 认证 1xxx =====
    AUTH_LOGIN_FAILED(1001, "用户名或密码错误"),
    AUTH_LOCKED(1002, "失败次数过多，账号或IP已锁定，请稍后再试"),
    AUTH_USER_DISABLED(1003, "账号已停用"),
    AUTH_OLD_PASSWORD_ERROR(1004, "原密码不正确"),
    AUTH_USER_NOT_FOUND(1005, "用户不存在"),

    // ===== 系统管理 2xxx =====
    USER_EXISTS(2001, "用户名已存在"),
    ROLE_EXISTS(2002, "角色名或权限字符已存在"),
    BUILTIN_DATA(2003, "内置数据不允许此操作"),
    CANNOT_DELETE_SELF(2004, "不能删除/停用当前登录用户"),
    MENU_HAS_CHILDREN(2005, "存在子菜单，无法删除"),
    ROLE_IN_USE(2006, "角色已分配用户，无法删除"),
    DICT_TYPE_EXISTS(2007, "字典类型已存在"),
    DICT_DATA_EXISTS(2008, "该字典类型下存在数据，无法删除"),
    CONFIG_KEY_EXISTS(2009, "参数键名已存在"),
    ROLE_NOT_FOUND(2010, "角色不存在"),

    // ===== 监控 3xxx =====
    MONITOR_NOT_READY(3001, "监控采集器尚未就绪"),

    // ===== 文件 4xxx =====
    FILE_PATH_DENIED(4001, "路径不在允许范围内"),
    FILE_EXISTS(4002, "目标已存在"),
    FILE_NOT_EXISTS(4003, "源路径不存在"),
    FILE_ROOT_FORBIDDEN(4004, "不允许对根目录本身执行此操作"),
    FILE_TOO_LARGE_TEXT(4005, "文件过大，不支持在线编辑"),
    FILE_NOT_TEXT(4006, "非文本文件，无法在线编辑"),
    FILE_ARCHIVE_UNSUPPORTED(4007, "不支持的压缩格式"),
    FILE_RECYCLE_NOT_FOUND(4008, "回收站记录不存在或已过期"),
    FILE_PERM_INVALID(4009, "权限字符串非法"),
    FILE_DIR_NOT_EMPTY(4010, "目录非空，无法删除到回收站"),

    // ===== 运维 5xxx =====
    CMD_NOT_ALLOWED(5001, "命令不在白名单内"),
    CMD_TIMEOUT(5002, "命令执行超时"),
    PROCESS_NOT_FOUND(5003, "进程不存在"),
    SERVICE_NOT_FOUND(5004, "服务不存在或不可管理"),
    CRON_EXPR_INVALID(5005, "cron 表达式非法"),
    CRON_JOB_NOT_FOUND(5006, "计划任务不存在"),
    FIREWALL_UNAVAILABLE(5007, "未检测到可用的防火墙(ufw/firewalld)"),
    FIREWALL_RULE_NOT_FOUND(5008, "防火墙规则不存在"),

    HOST_CHANNEL_UNAVAILABLE(5009, "宿主执行通道不可用，请先在宿主机安装 psm-hostagent"),
    HOST_CHANNEL_PROTOCOL_MISMATCH(5010, "宿主通道协议版本不匹配，已降级为只读"),
    SERVICE_PROTECTED(5011, "该服务在保护清单内，需危险操作权限并二次确认"),
    CRON_JOB_RUNNING(5012, "计划任务正在执行中，已按并发策略跳过本次触发"),
    CRON_MISFIRE_LIMIT(5013, "错过的执行次数超出补跑上限"),
    FIREWALL_RULE_CHANGED(5014, "防火墙规则已变化，请刷新后重试"),
    FIREWALL_GUARD_TRIGGERED(5015, "该变更会切断当前 SSH / 面板访问，需二次确认"),
    FIREWALL_FOREIGN_RULE(5016, "该规则由外部程序（fail2ban 等）管理，面板禁止直接删除"),
    FIREWALL_ROLLBACK_UNAVAILABLE(5017, "该变更不可回滚：快照缺失或已被后续变更覆盖"),

    // ===== 应用栈 6xxx =====
    DOCKER_UNAVAILABLE(6001, "Docker 服务不可用"),
    DOCKER_RESOURCE_NOT_FOUND(6002, "容器或镜像不存在"),
    WEBSITE_DOMAIN_EXISTS(6003, "域名已存在"),
    NGINX_UNAVAILABLE(6004, "Nginx 不可用"),
    NGINX_CONF_INVALID(6005, "nginx 配置校验失败，已回滚"),
    MYSQL_ADMIN_UNAVAILABLE(6006, "MySQL 管理连接不可用"),
    DB_IDENTIFIER_INVALID(6007, "库名/用户名/密码不符合规则"),
    DB_EXISTS(6008, "数据库已存在"),
    DB_NOT_FOUND(6009, "数据库不存在"),

    // ===== Nginx 管理 6010+ =====
    NGINX_INSTANCE_NOT_FOUND(6010, "Nginx 实例不存在"),
    NGINX_GUARD_TRIGGERED(6011, "该操作存在风险，需二次确认"),
    NGINX_DOMAIN_CONFLICT(6012, "域名已被其它站点使用"),
    NGINX_UPSTREAM_IN_USE(6013, "上游组仍被站点引用，无法删除"),
    NGINX_CERT_IN_USE(6014, "证书仍被站点引用，无法删除"),
    NGINX_STREAM_PORT_CONFLICT(6015, "监听端口已被其它转发占用"),

    // ===== 服务器配置管理 6020+ =====
    SERVER_CONFIG_CATEGORY_NOT_FOUND(6020, "配置类别不存在"),
    SERVER_CONFIG_VALIDATE_FAILED(6021, "配置校验未通过，未做任何变更"),
    SERVER_CONFIG_APPLY_FAILED(6022, "配置生效失败，已自动回滚"),
    SERVER_CONFIG_BACKUP_FAILED(6023, "配置备份失败，已中止生效"),
    SERVER_CONFIG_SELF_LOCKOUT_RISK(6024, "该变更可能导致自身失联，需二次确认"),
    SERVER_CONFIG_ITEM_INVALID(6025, "配置项不合法"),
    SERVER_CONFIG_BUSY(6026, "该配置类别正在生效中，请稍后重试");

    private final int code;
    private final String message;
}
