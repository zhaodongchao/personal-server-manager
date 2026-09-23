package com.serverpanel.tools.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.tools.dto.IdDecodeBody;
import com.serverpanel.tools.dto.IdDecodeResultVO;
import com.serverpanel.tools.dto.IdGenerateBody;
import com.serverpanel.tools.dto.IdGenerateResultVO;
import com.serverpanel.tools.dto.IdOptionsVO;
import com.serverpanel.tools.service.IdService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ID 生成器接口：按方案生成一批 ID，以及反解一个已有 ID。
 *
 * <p>审计口径与加解密页、JWT 页<b>刻意不同</b>：那两个页面的入参必然带口令或密钥，
 * 所以一律 {@code recordParams = false}；而 ID 生成只用到「方案 + 数量 + 机器号 /
 * 起始值」，不含任何凭据，落库反而能回答「谁在什么时候批量生成过什么方案的 ID」，
 * 因此这里保留默认的入参记录。
 *
 * <p>第一类方案（自增计数器 / 序列）是<b>参数化模拟</b>：不连接数据库、不创建任何对象。
 * 真实取号能力在 {@code appstack/database}。
 *
 * @author zhaodc
 * @since 2026-09-23 UTC+8
 */
@RestController
@RequestMapping("/api/v1/tools/id")
@RequiredArgsConstructor
public class IdController {

    private final IdService idService;

    /** 方案清单：位分配、参数定义、优缺点、示例 */
    @SaCheckPermission("tools:id:list")
    @GetMapping("/options")
    public R<IdOptionsVO> options() {
        return R.ok(idService.options());
    }

    /** 按方案生成 1 ~ 1000 个 ID */
    @SaCheckPermission("tools:id:exec")
    @Audit(module = "tools", action = "id:generate")
    @PostMapping("/generate")
    public R<IdGenerateResultVO> generate(@Valid @RequestBody IdGenerateBody body) {
        return R.ok(idService.generate(body));
    }

    /** 反解一个已有 ID：拆位段、还原生成时间 */
    @SaCheckPermission("tools:id:exec")
    @Audit(module = "tools", action = "id:decode")
    @PostMapping("/decode")
    public R<IdDecodeResultVO> decode(@Valid @RequestBody IdDecodeBody body) {
        return R.ok(idService.decode(body));
    }
}
