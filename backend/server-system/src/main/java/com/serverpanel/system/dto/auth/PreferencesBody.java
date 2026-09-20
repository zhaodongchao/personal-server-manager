package com.serverpanel.system.dto.auth;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.bson.Document;

/**
 * 保存用户偏好设置请求体。
 */
@Data
public class PreferencesBody {

    /** 偏好设置全量 JSON（Vben preferences 主状态） */
    @NotNull(message = "偏好设置内容不能为空")
    private Document preferences;

    /** 自定义扩展偏好（Vben custom preferences，可空） */
    private Document custom;
}
