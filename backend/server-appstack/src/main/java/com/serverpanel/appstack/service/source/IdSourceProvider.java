package com.serverpanel.appstack.service.source;

import java.util.Map;

import com.serverpanel.common.id.DbKind;
import com.serverpanel.common.id.IdSourceBatch;
import com.serverpanel.common.id.IdSourceProbe;
import com.serverpanel.common.id.IdSourceSpec;

/**
 * 取号原语实现（模块内部接口，不跨模块）。
 *
 * <p>每种数据库一套实现：PostgreSQL 用序列（{@code nextval}），
 * MySQL 用自增列（{@code INSERT} + {@code LAST_INSERT_ID()}）。
 * 二者是各自数据库里最原生的取号方式，也正是「ID 生成器」第一类方案
 * 想要展示的真实行为 —— 而不是参数推演。
 *
 * <p>本接口只在 {@code server-appstack} 内部使用；跨模块暴露的是
 * {@code com.serverpanel.common.id.IdSourceGateway}。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
public interface IdSourceProvider {

    /** 支持的库类型 */
    DbKind kind();

    /** 连通性探测（返回结果而非抛异常，失败原因是给用户看的） */
    IdSourceProbe probe(IdSourceSpec spec);

    /**
     * 幂等初始化取号对象。
     *
     * @return 本次实际做了什么（供界面回显）
     */
    String initialize(IdSourceSpec spec);

    /** 真连库取号 */
    IdSourceBatch fetch(IdSourceSpec spec, int count, Map<String, String> params);

    /** 取号对象名（已按默认值回落），供展示 */
    String targetOf(IdSourceSpec spec);

    /** 默认取号对象名 */
    String defaultTarget();
}
