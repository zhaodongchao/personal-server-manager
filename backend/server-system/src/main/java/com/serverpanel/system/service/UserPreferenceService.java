package com.serverpanel.system.service;

import com.serverpanel.framework.mongo.MongoBaseService;
import com.serverpanel.system.entity.mongo.UserPreferenceDocument;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 用户偏好设置服务（MongoDB 存储）。
 *
 * <p>按用户维度存取前端偏好设置全量 JSON：登录后前端拉取应用，
 * 设置面板变更后防抖保存，upsert 保证一人一档。
 *
 * @author zhaodc
 * @since 2026-09-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    /** 集合名 */
    private static final String COLLECTION = "user_preference";

    private final MongoBaseService mongoBaseService;
    private final MongoTemplate mongoTemplate;

    /**
     * 启动时依据 @Indexed 注解创建索引（Spring Data 默认关闭自动索引创建，需显式执行）
     */
    @PostConstruct
    public void initIndex() {
        try {
            IndexOperations indexOps = mongoTemplate.indexOps(UserPreferenceDocument.class);
            // Spring Data MongoDB 5.x 中 PersistentEntityIndexResolver 已移除，统一由 IndexResolver.create 工厂方法创建
            IndexResolver resolver = IndexResolver.create(mongoTemplate.getConverter().getMappingContext());
            resolver.resolveIndexFor(UserPreferenceDocument.class).forEach(indexOps::ensureIndex);
        } catch (Exception e) {
            // 索引创建失败不阻断启动（如 MongoDB 暂不可用），保存时 upsert 仍可工作
            log.warn("Init user_preference index failed: {}", e.getMessage());
        }
    }

    /**
     * 查询用户偏好设置
     *
     * @param userId 用户 ID
     * @return 偏好设置文档，无记录返回 empty
     */
    public Optional<UserPreferenceDocument> getByUserId(String userId) {
        Query query = new Query(Criteria.where("userId").is(userId));
        return mongoBaseService.findOne(query, UserPreferenceDocument.class, COLLECTION);
    }

    /**
     * 保存用户偏好设置（upsert，一人一档）
     *
     * @param userId      用户 ID
     * @param preferences 偏好设置全量 JSON（主状态）
     * @param custom      自定义扩展偏好，可为 null
     */
    public void save(String userId, Document preferences, Document custom) {
        Query query = new Query(Criteria.where("userId").is(userId));
        Update update = new Update()
            .set("userId", userId)
            .set("preferences", preferences)
            .set("updatedAt", LocalDateTime.now());
        if (custom != null) {
            update.set("custom", custom);
        }
        mongoBaseService.upsert(query, update, UserPreferenceDocument.class, COLLECTION);
    }

    /**
     * 删除用户偏好设置
     *
     * @param userId 用户 ID
     */
    public void deleteByUserId(String userId) {
        Query query = new Query(Criteria.where("userId").is(userId));
        mongoBaseService.remove(query, COLLECTION);
    }
}
