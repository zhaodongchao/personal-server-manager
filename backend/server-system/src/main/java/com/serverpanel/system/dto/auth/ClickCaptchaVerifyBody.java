package com.serverpanel.system.dto.auth;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 点选人机校验确认请求。
 *
 * <p>前端按提示顺序上报每次点击的相对坐标（0~1，相对图片宽高，与显示尺寸无关），
 * 服务端换算成像素后与挑战中记录的目标字符中心比对，命中容差内且顺序一致才通过。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@Data
public class ClickCaptchaVerifyBody {

    @NotBlank(message = "人机验证挑战不能为空")
    private String captchaToken;

    /** 按点击顺序排列的坐标点，数量须与提示字符数一致 */
    @Valid
    @NotEmpty(message = "点击坐标不能为空")
    private List<ClickPoint> clicks;

    /**
     * 校验用途：{@code send}=发邮件验证码（默认），{@code login}=密码登录。
     * 决定 {@link com.serverpanel.system.service.CaptchaService#verifyClick}
     * 签发何种一次性令牌（captcha:send: / captcha:login:），两场景令牌隔离。
     */
    private String purpose = "send";

    /** 一次点击的相对坐标（0~1，相对图片宽 / 高） */
    @Data
    public static class ClickPoint {

        private double x;

        private double y;
    }
}
