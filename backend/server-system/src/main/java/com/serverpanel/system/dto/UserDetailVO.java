package com.serverpanel.system.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户详情视图对象：在列表字段基础上补充头像，供「编辑用户」弹窗回显。
 *
 * <p>同时返回两种形态：
 * <ul>
 *   <li>{@link #avatar} —— 库中原始值（null / preset:N / data:image/...;base64,...），
 *       前端据此判断当前是「预设」还是「自定义上传」并高亮对应预设；</li>
 *   <li>{@link #avatarUrl} —— 归一化后的可渲染 src，前端可直接塞进 img/avatar。</li>
 * </ul>
 *
 * @author zhaodc
 * @since 2026-09-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserDetailVO extends UserVO {

    /** 库中原始头像值：null / preset:N / data:image/...;base64,... */
    private String avatar;

    /** 归一化后的可渲染 src（预设映射为站内静态资源，自定义为 data URL） */
    private String avatarUrl;
}
