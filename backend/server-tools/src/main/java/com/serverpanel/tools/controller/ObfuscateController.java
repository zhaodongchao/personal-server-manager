package com.serverpanel.tools.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.tools.dto.ObfuscateBody;
import com.serverpanel.tools.dto.ObfuscateOptionsVO;
import com.serverpanel.tools.service.ObfuscateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可逆混淆接口。
 *
 * <p>安全声明：这里的「混淆」<b>不是加密</b>，不具备任何安全性，只用于防止明文被
 * 直接阅读。接口不记录入参（同 {@link CryptoController} 的审计口径）。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@RestController
@RequestMapping("/api/v1/tools/obfuscate")
@RequiredArgsConstructor
public class ObfuscateController {

    private final ObfuscateService obfuscateService;

    /** 混淆方式与参数定义 */
    @SaCheckPermission("tools:obfuscate:list")
    @GetMapping("/options")
    public R<ObfuscateOptionsVO> options() {
        return R.ok(obfuscateService.options());
    }

    /** 混淆 / 反混淆 */
    @SaCheckPermission("tools:obfuscate:exec")
    @Audit(module = "tools", action = "obfuscate:transform", recordParams = false)
    @PostMapping("/transform")
    public R<String> transform(@Valid @RequestBody ObfuscateBody body) {
        return R.ok(obfuscateService.transform(body));
    }
}
