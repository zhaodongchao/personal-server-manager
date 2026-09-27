package com.serverpanel.system.controller;

import java.util.Map;

import com.serverpanel.common.core.R;
import com.serverpanel.system.dto.auth.CaptchaVerifyBody;
import com.serverpanel.system.service.CaptchaService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 滑块人机校验接口（免登录）。
 *
 * <p>两个端点：
 * <ul>
 *   <li>{@code GET /auth/captcha/slider} —— 下发挑战令牌；</li>
 *   <li>{@code POST /auth/captcha/slider/verify} —— 校验拖拽时长、签发一次性发信令牌。</li>
 * </ul>
 * 发信令牌由 {@code /mail/code} 携带并原子消费，保证「先过机校验、再发邮件」。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
@RestController
@RequestMapping("/api/v1/auth/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;

    /** 下发滑块挑战令牌 */
    @PostMapping("/slider")
    public R<Map<String, String>> slider() {
        return R.ok(Map.of("captchaToken", captchaService.issueSliderToken()));
    }

    /** 校验滑块并下发一次性发信令牌 */
    @PostMapping("/slider/verify")
    public R<Map<String, String>> verify(@Valid @RequestBody CaptchaVerifyBody body) {
        String sendToken = captchaService.verifySlider(
                body.getCaptchaToken(), body.getDragSeconds());
        return R.ok(Map.of("sendToken", sendToken));
    }
}
