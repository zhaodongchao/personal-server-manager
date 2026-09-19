package com.serverpanel.framework.mongo;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB 通用基础 CRUD 封装。
 *
 * <p>基于 {@link MongoTemplate} 提供集合名维度的基础操作，供各业务模块复用，
 * 避免每个模块各自直接依赖 MongoTemplate。查询条件统一使用 Spring Data 的
 * {@link Query}/{@link Criteria} 构建。
 *
 * @author zhaodc
 * @since 2026-09-19
 */
@Component
@RequiredArgsConstructor
public class MongoBaseService {

    private final MongoTemplate mongoTemplate;

    /**
     * 保存实体（存在 id 则覆盖，不存在则插入）
     *
     * @param entity         实体对象
     * @param collectionName 集合名
     * @param <T>            实体类型
     * @return 保存后的实体（含生成的 id）
     */
    public <T> T save(T entity, String collectionName) {
        return mongoTemplate.save(entity, collectionName);
    }

    /**
     * 按 ID 查询
     *
     * @param id             文档 ID
     * @param clazz          实体类型
     * @param collectionName 集合名
     * @param <T>            实体类型
     * @return 命中的实体，Optional 包装
     */
    public <T> Optional<T> findById(Object id, Class<T> clazz, String collectionName) {
        return Optional.ofNullable(mongoTemplate.findById(id, clazz, collectionName));
    }

    /**
     * 条件查询单条
     *
     * @param query          查询条件
     * @param clazz          实体类型
     * @param collectionName 集合名
     * @param <T>            实体类型
     * @return 命中的实体，Optional 包装
     */
    public <T> Optional<T> findOne(Query query, Class<T> clazz, String collectionName) {
        return Optional.ofNullable(mongoTemplate.findOne(query, clazz, collectionName));
    }

    /**
     * 条件查询列表
     *
     * @param query          查询条件
     * @param clazz          实体类型
     * @param collectionName 集合名
     * @param <T>            实体类型
     * @return 命中实体列表
     */
    public <T> List<T> find(Query query, Class<T> clazz, String collectionName) {
        return mongoTemplate.find(query, clazz, collectionName);
    }

    /**
     * 条件计数
     *
     * @param query          查询条件
     * @param collectionName 集合名
     * @return 命中文档数
     */
    public long count(Query query, String collectionName) {
        return mongoTemplate.count(query, collectionName);
    }

    /**
     * 条件存在性判断
     *
     * @param query          查询条件
     * @param clazz          实体类型
     * @param collectionName 集合名
     * @param <T>            实体类型
     * @return 是否存在命中文档
     */
    public <T> boolean exists(Query query, Class<T> clazz, String collectionName) {
        return mongoTemplate.exists(query, clazz, collectionName);
    }

    /**
     * 条件删除
     *
     * @param query          查询条件
     * @param collectionName 集合名
     * @return 删除的文档数
     */
    public long remove(Query query, String collectionName) {
        return mongoTemplate.remove(query, collectionName).getDeletedCount();
    }

    /**
     * 条件更新或插入（upsert）
     *
     * @param query          匹配条件
     * @param update         更新内容
     * @param clazz          实体类型（用于定位集合映射）
     * @param collectionName 集合名
     * @param <T>            实体类型
     */
    public <T> void upsert(Query query, UpdateDefinition update, Class<T> clazz, String collectionName) {
        mongoTemplate.upsert(query, update, clazz, collectionName);
    }

    /**
     * 执行原始命令（如 ping、collStats 等管理类命令）
     *
     * @param command 命令文档
     * @return 命令执行结果
     */
    public Document executeCommand(Document command) {
        return mongoTemplate.executeCommand(command);
    }
}
