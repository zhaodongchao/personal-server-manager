package com.serverpanel.tools.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.serverpanel.common.annotation.Audit;
import com.serverpanel.common.core.R;
import com.serverpanel.tools.dto.ImageConvertOptionsVO;
import com.serverpanel.tools.dto.ImageConvertResultVO;
import com.serverpanel.tools.service.ImageConvertService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 图片转换工具接口：主流格式互转（PNG/JPEG/WEBP/BMP/GIF/TIFF）+ 缩放 + 质量调节。
 *
 * <p>审计口径：{@code recordParams = false} —— multipart 文件参数本就不入审计，
 * 且图片内容可能含敏感信息，与二维码/正则工具同口径：只留「谁、何时、做了什么、结果如何」。
 *
 * <p>纯内存计算，不落盘、不发网络请求。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@RestController
@RequestMapping("/api/v1/tools/image-convert")
@RequiredArgsConstructor
public class ImageConvertController {

    private final ImageConvertService imageConvertService;

    /** 格式清单、上限与全局说明 */
    @SaCheckPermission("tools:image:list")
    @GetMapping("/options")
    public R<ImageConvertOptionsVO> options() {
        return R.ok(imageConvertService.options());
    }

    /**
     * 批量转换（multipart 表单）：单文件失败不中断批次，逐项回传成功/失败。
     *
     * @param files        图片文件（最多 10 个，单个 ≤ 10MB，总量 ≤ 50MB）
     * @param targetFormat 目标格式（JPEG/PNG/WEBP/BMP/GIF/TIFF，大小写不敏感）
     * @param resizeMode   缩放模式：none / percent / dimension / longEdge
     * @param percent      百分比缩放（1-500）
     * @param width        指定宽（dimension 模式）
     * @param height       指定高（dimension 模式）
     * @param longEdge     最长边（longEdge 模式）
     * @param keepRatio    dimension 模式是否保持纵横比（默认 true）
     * @param quality      质量 1-100（无损格式忽略，默认 85）
     * @return 每个文件的转换结果
     */
    @SaCheckPermission("tools:image:convert")
    @Audit(module = "tools", action = "image:convert", recordParams = false)
    @PostMapping("/convert")
    public R<List<ImageConvertResultVO>> convert(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("targetFormat") String targetFormat,
            @RequestParam(value = "resizeMode", required = false, defaultValue = "none") String resizeMode,
            @RequestParam(value = "percent", required = false) Integer percent,
            @RequestParam(value = "width", required = false) Integer width,
            @RequestParam(value = "height", required = false) Integer height,
            @RequestParam(value = "longEdge", required = false) Integer longEdge,
            @RequestParam(value = "keepRatio", required = false, defaultValue = "true") boolean keepRatio,
            @RequestParam(value = "quality", required = false) Integer quality) {
        return R.ok(imageConvertService.convert(
                files, targetFormat, resizeMode, percent, width, height, longEdge, keepRatio, quality));
    }
}
