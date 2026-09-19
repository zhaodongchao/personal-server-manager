package com.serverpanel.framework.mongo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * MongoDB 启动连通性检查。
 *
 * <p>启动后执行 ping 命令确认 MongoDB 可达；失败仅告警不阻断启动，
 * 与 Redis/MySQL 的可用性行为解耦（偏好设置等功能降级为不可用）。
 *
 * @author zhaodc
 * @since 2026-09-19
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoConnectivityChecker implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Document result = mongoTemplate.executeCommand(new Document("ping", 1));
            log.info("MongoDB connectivity check OK (ok={})", result.get("ok"));
        } catch (Exception e) {
            log.warn("MongoDB connectivity check FAILED: {} — 偏好设置等 MongoDB 功能暂不可用",
                e.getMessage());
        }
    }
}
