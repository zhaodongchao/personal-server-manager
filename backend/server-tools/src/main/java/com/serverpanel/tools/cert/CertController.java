package com.serverpanel.tools.cert;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.tools.cert.dto.CertOptionsVO;
import com.serverpanel.tools.cert.dto.CertParseBody;
import com.serverpanel.tools.cert.dto.CertParseResultVO;
import com.serverpanel.tools.cert.CertParseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证件解析接口：options（类型/规则清单）+ parse（解析）。
 *
 * <p><b>隐私红线</b>：证件号属敏感个人信息。解析全程在服务器内存完成、
 * 不落任何表；审计 {@code recordParams = false} —— 证件号不出现在
 * 审计日志与任何运行日志中，只留「谁、何时、解析了哪类证件、结果级别」。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@RestController
@RequestMapping("/api/v1/tools/cert")
@RequiredArgsConstructor
public class CertController {

    private final CertParseService certParseService;

    /** 证件类型清单、构造规则与各项上限 */
    @SaCheckPermission("tools:cert:list")
    @GetMapping("/options")
    public R<CertOptionsVO> options() {
        return R.ok(certParseService.options());
    }

    /** 解析证件号码（业务失败返回 valid=false，不抛接口异常） */
    @SaCheckPermission("tools:cert:exec")
    @Audit(module = "tools", action = "cert:parse", recordParams = false)
    @PostMapping("/parse")
    public R<CertParseResultVO> parse(@Valid @RequestBody CertParseBody body) {
        return R.ok(certParseService.parse(body));
    }
}
