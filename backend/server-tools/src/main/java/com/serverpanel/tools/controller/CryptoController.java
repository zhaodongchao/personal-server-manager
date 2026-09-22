package com.serverpanel.tools.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.tools.dto.CryptoCodecBody;
import com.serverpanel.tools.dto.CryptoDigestBody;
import com.serverpanel.tools.dto.CryptoHmacBody;
import com.serverpanel.tools.dto.CryptoOptionsVO;
import com.serverpanel.tools.dto.CryptoSymmetricBody;
import com.serverpanel.tools.service.CryptoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字符串加解密接口。
 *
 * <p>审计口径（重要）：这些接口处理的往往是口令、Token、密钥之类的明文，
 * 因此全部标注 {@code @Audit(recordParams = false)} —— 保留「谁在什么时候用了哪个
 * 能力」的留痕，但<b>不把入参写进 sys_audit_log</b>。这与 /auth/safe 的取舍一致：
 * 审计表自身的价值不能被它泄露的明文抵消。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@RestController
@RequestMapping("/api/v1/tools/crypto")
@RequiredArgsConstructor
public class CryptoController {

    private final CryptoService cryptoService;

    /** 可选清单：算法名与中文展示名以服务端为唯一真源 */
    @SaCheckPermission("tools:crypto:list")
    @GetMapping("/options")
    public R<CryptoOptionsVO> options() {
        return R.ok(cryptoService.options());
    }

    /** 摘要：MD5 / SHA-1 / SHA-256 / SHA-512 */
    @SaCheckPermission("tools:crypto:exec")
    @Audit(module = "tools", action = "crypto:digest", recordParams = false)
    @PostMapping("/digest")
    public R<String> digest(@Valid @RequestBody CryptoDigestBody body) {
        return R.ok(cryptoService.digest(body));
    }

    /** HMAC */
    @SaCheckPermission("tools:crypto:exec")
    @Audit(module = "tools", action = "crypto:hmac", recordParams = false)
    @PostMapping("/hmac")
    public R<String> hmac(@Valid @RequestBody CryptoHmacBody body) {
        return R.ok(cryptoService.hmac(body));
    }

    /** Base64 / Hex 编解码 */
    @SaCheckPermission("tools:crypto:exec")
    @Audit(module = "tools", action = "crypto:codec", recordParams = false)
    @PostMapping("/codec")
    public R<String> codec(@Valid @RequestBody CryptoCodecBody body) {
        return R.ok(cryptoService.codec(body));
    }

    /** 对称加解密：AES / DES / 3DES / RC4 */
    @SaCheckPermission("tools:crypto:exec")
    @Audit(module = "tools", action = "crypto:symmetric", risky = true, recordParams = false)
    @PostMapping("/symmetric")
    public R<String> symmetric(@Valid @RequestBody CryptoSymmetricBody body) {
        return R.ok(cryptoService.symmetric(body));
    }
}
