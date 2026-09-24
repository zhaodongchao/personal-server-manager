package com.serverpanel.tools.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.tools.dto.RegularExpressionsVO;
import com.serverpanel.tools.dto.RegexGenerateBody;
import com.serverpanel.tools.dto.RegexGenerateResultVO;
import com.serverpanel.tools.dto.RegexTestBody;
import com.serverpanel.tools.dto.RegexTestResultVO;
import com.serverpanel.tools.service.RegexService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 正则工具接口：按场景生成正则 + 测试（匹配 / 结构解析 / 替换预览）。
 *
 * <p>审计口径：{@code recordParams = false} —— 待匹配文本与自定义规则
 * 可能含敏感内容（口令、密钥、内网地址等），与二维码工具同口径：
 * 只留「谁、何时、做了生成还是测试、结果如何」。
 *
 * <p>纯内存计算，不落盘、不发网络请求，因此只依赖 server-framework。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@RestController
@RequestMapping("/api/v1/tools/regex")
@RequiredArgsConstructor
public class RegexController {

    private final RegexService regexService;

    /** 场景清单、标志清单与各项上限 */
    @SaCheckPermission("tools:regex:list")
    @GetMapping("/options")
    public R<RegexOptionsVO> options() {
        return R.ok(regexService.options());
    }

    /** 按场景 + 参数生成正则（含逐段说明与示例） */
    @SaCheckPermission("tools:regex:exec")
    @Audit(module = "tools", action = "regex:generate", recordParams = false)
    @PostMapping("/generate")
    public R<RegexGenerateResultVO> generate(@Valid @RequestBody RegexGenerateBody body) {
        return R.ok(regexService.generate(body));
    }

    /** 测试/解析正则（语法错误不算接口错误，返回 valid=false + 中文原因） */
    @SaCheckPermission("tools:regex:exec")
    @Audit(module = "tools", action = "regex:test", recordParams = false)
    @PostMapping("/test")
    public R<RegexTestResultVO> test(@Valid @RequestBody RegexTestBody body) {
        return R.ok(regexService.test(body));
    }
}
