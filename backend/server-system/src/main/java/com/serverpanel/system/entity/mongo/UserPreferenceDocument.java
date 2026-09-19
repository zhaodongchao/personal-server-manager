package com.serverpanel.system.entity.mongo;

import lombok.Data;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDateTime;

/**
 * 用户偏好设置文档（MongoDB，集合 user_preference）。
 *
 * <p>按用户维度存储前端偏好设置全量 JSON，userId 唯一索引保证一人一档。
 *
 * @author zhaodc
 * @since 2026-09-19
 */
@Data
@org.springframework.data.mongodb.core.mapping.Document("user_preference")
public class UserPreferenceDocument {

    /** 文档 ID */
    @Id
    private ObjectId id;

    /** 用户 ID（唯一索引） */
    @Indexed(unique = true)
    private String userId;

    /** 前端偏好设置全量 JSON（Vben preferences 主状态） */
    private Document preferences;

    /** 前端自定义扩展偏好（Vben custom preferences，可空） */
    private Document custom;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}
