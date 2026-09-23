package com.serverpanel.tools.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.tools.dto.JwtOptionsVO;
import com.serverpanel.tools.dto.JwtSignBody;
import com.serverpanel.tools.dto.JwtSignResultVO;
import com.serverpanel.tools.dto.JwtVerifyBody;
import com.serverpanel.tools.dto.JwtVerifyResultVO;
import com.serverpanel.tools.service.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JWT 工具接口：验签与签发。
 *
 * <p>安全声明：请求体里必然带着密钥（对称口令或非对称私钥），因此与
 * {@link CryptoController} 保持同一审计口径 —— {@code recordParams = false}，
 * 审计表只留「谁、何时、调了验签还是签发、结果如何」，不落任何密钥与 token 明文。
 *
 * <p>签发接口另标 {@code risky = true}：产出的是可直接使用的凭据，属于风险动作，
 * 需要在审计页能被单独筛出来。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@RestController
@RequestMapping("/api/v1/tools/jwt")
@RequiredArgsConstructor
public class JwtController {

    private final JwtService jwtService;

    /** 支持的算法清单与密钥录入方式 */
    @SaCheckPermission("tools:jwt:list")
    @GetMapping("/options")
    public R<JwtOptionsVO> options() {
        return R.ok(jwtService.options());
    }

    /** 验证签名 */
    @SaCheckPermission("tools:jwt:exec")
    @Audit(module = "tools", action = "jwt:verify", recordParams = false)
    @PostMapping("/verify")
    public R<JwtVerifyResultVO> verify(@Valid @RequestBody JwtVerifyBody body) {
        return R.ok(jwtService.verify(body));
    }

    /** 签发（生成签名后的）JWT */
    @SaCheckPermission("tools:jwt:exec")
    @Audit(module = "tools", action = "jwt:sign", risky = true, recordParams = false)
    @PostMapping("/sign")
    public R<JwtSignResultVO> sign(@Valid @RequestBody JwtSignBody body) {
        return R.ok(jwtService.sign(body));
    }
}
