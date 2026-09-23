package com.serverpanel.tools.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.tools.dto.QrcodeDecodeBody;
import com.serverpanel.tools.dto.QrcodeDecodeResultVO;
import com.serverpanel.tools.dto.QrcodeGenerateBody;
import com.serverpanel.tools.dto.QrcodeGenerateResultVO;
import com.serverpanel.tools.dto.QrcodeOptionsVO;
import com.serverpanel.tools.service.QrcodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 二维码工具接口：生成（含样式控制）与识别。
 *
 * <p>审计口径：{@code recordParams = false}。请求体里可能有 WiFi 明文口令、
 * 名片手机号、收款地址以及整张 base64 图片 —— 这些都不该落进 sys_audit_log。
 * 与 {@link CryptoController} / {@link JwtController} 同一处理：只留「谁、何时、
 * 用了生成还是识别、结果如何」。
 *
 * <p>纯内存图像处理，不落盘、不发网络请求，因此只依赖 server-framework。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@RestController
@RequestMapping("/api/v1/tools/qrcode")
@RequiredArgsConstructor
public class QrcodeController {

    private final QrcodeService qrcodeService;

    /** 内容类型、容错等级、样式清单与各项上限 */
    @SaCheckPermission("tools:qrcode:list")
    @GetMapping("/options")
    public R<QrcodeOptionsVO> options() {
        return R.ok(qrcodeService.options());
    }

    /** 生成二维码（返回 data URL 图片） */
    @SaCheckPermission("tools:qrcode:exec")
    @Audit(module = "tools", action = "qrcode:generate", recordParams = false)
    @PostMapping("/generate")
    public R<QrcodeGenerateResultVO> generate(@Valid @RequestBody QrcodeGenerateBody body) {
        return R.ok(qrcodeService.generate(body));
    }

    /** 识别图片中的二维码（识别不出不算错误，返回 found=false + 中文原因） */
    @SaCheckPermission("tools:qrcode:exec")
    @Audit(module = "tools", action = "qrcode:decode", recordParams = false)
    @PostMapping("/decode")
    public R<QrcodeDecodeResultVO> decode(@Valid @RequestBody QrcodeDecodeBody body) {
        return R.ok(qrcodeService.decode(body));
    }
}
