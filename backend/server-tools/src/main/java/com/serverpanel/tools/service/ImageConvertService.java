package com.serverpanel.tools.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.dto.ImageConvertOptionsVO;
import com.serverpanel.tools.dto.ImageConvertResultVO;
import com.serverpanel.tools.dto.ImageFormatVO;
import com.serverpanel.tools.dto.ImageLimitsVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * 图片格式转换服务：解码 -> 缩放 -> 透明通道处理 -> 重编码，全程内存完成。
 *
 * <p>格式覆盖：PNG / JPEG / WEBP / BMP / GIF / TIFF。读取端依赖 TwelveMonkeys
 * ImageIO 插件（SPI 自动注册，优先于 JDK 内置实现），支持 CMYK JPEG、TIFF、WebP；
 * 写入端按格式差异处理：JPEG/WEBP 应用质量参数，JPEG/BMP 自动白底拍平透明区域，
 * GIF 动图按首帧转出（静帧）。
 *
 * <p>资源口径：纯内存计算，不落盘、不发网络请求；单文件失败不中断批次。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Slf4j
@Service
public class ImageConvertService {

    /** 支持互转的格式集合（规范大写） */
    private static final Set<String> SUPPORTED_FORMATS = Set.of("PNG", "JPEG", "WEBP", "BMP", "GIF", "TIFF");

    /** 质量参数生效的格式（JPEG/WEBP 确定生效，TIFF 最佳努力） */
    private static final Set<String> QUALITY_FORMATS = Set.of("JPEG", "WEBP", "TIFF");

    /** 不支持透明通道的格式（写入时白底拍平） */
    private static final Set<String> FLATTEN_ALPHA_FORMATS = Set.of("JPEG", "BMP");

    /** 未传质量参数时的默认值 */
    private static final int DEFAULT_QUALITY = 85;

    /** 各格式的展示元数据（ext 用最常见扩展名；qualitySupported/alphaSupported 见注释） */
    private static final Map<String, ImageFormatVO> FORMAT_META = new LinkedHashMap<>();

    static {
        FORMAT_META.put("JPEG", new ImageFormatVO("JPEG", "jpg", "image/jpeg", false, true, false, "有损压缩，质量参数影响体积与清晰度；不支持透明"));
        FORMAT_META.put("PNG", new ImageFormatVO("PNG", "png", "image/png", true, false, true, "无损压缩，质量参数不生效；支持透明"));
        FORMAT_META.put("WEBP", new ImageFormatVO("WEBP", "webp", "image/webp", false, true, true, "有损压缩，通常比 JPEG 体积更小；支持透明"));
        FORMAT_META.put("BMP", new ImageFormatVO("BMP", "bmp", "image/bmp", true, false, false, "无压缩位图，体积大；不支持透明"));
        FORMAT_META.put("GIF", new ImageFormatVO("GIF", "gif", "image/gif", true, false, true, "256 色索引图；动图按首帧转出（静帧）"));
        FORMAT_META.put("TIFF", new ImageFormatVO("TIFF", "tif", "image/tiff", true, true, true, "常用于扫描件/印刷；质量参数取决于所选压缩算法"));
    }

    /** 单次最多文件数 */
    private static final int MAX_FILES = 10;
    /** 单个文件上限 10MB */
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    /** 单次请求总量上限 50MB */
    private static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    /** 缩放后单边上限（10000px 的 ARGB 解码约 400MB，是内存可承受的保守值） */
    private static final int MAX_SIDE = 10_000;

    /**
     * 下发格式清单、上限与全局说明。
     *
     * @return options 响应
     */
    public ImageConvertOptionsVO options() {
        List<String> notes = List.of(
                "转换在服务器内存中完成，不保存任何上传或生成的图片",
                "GIF 动图按首帧转出（静帧）",
                "JPEG / BMP 不支持透明通道，透明区域将自动填充白底",
                "PNG / BMP / GIF 为无损格式，质量参数不生效");
        return new ImageConvertOptionsVO(new ArrayList<>(FORMAT_META.values()), limits(), notes);
    }

    /**
     * 批量转换：逐个文件解码、缩放、重编码；单文件失败不中断批次。
     *
     * @param files        上传的图片文件
     * @param targetFormat 目标格式（大小写不敏感）
     * @param resizeMode   缩放模式：none / percent / dimension / longEdge
     * @param percent      百分比缩放值（1-500）
     * @param width        指定宽（dimension 模式必填）
     * @param height       指定高（dimension 模式必填）
     * @param longEdge     最长边目标值（longEdge 模式必填）
     * @param keepRatio    dimension 模式是否保持纵横比（true 取内切缩放）
     * @param quality      质量参数（1-100，null 用默认 85；无损格式忽略）
     * @return 每个文件的转换结果（含失败项）
     */
    public List<ImageConvertResultVO> convert(MultipartFile[] files,
                                              String targetFormat,
                                              String resizeMode,
                                              Integer percent,
                                              Integer width,
                                              Integer height,
                                              Integer longEdge,
                                              boolean keepRatio,
                                              Integer quality) {
        String target = normalizeFormat(targetFormat);
        String mode = resizeMode == null ? "none" : resizeMode.trim().toLowerCase(Locale.ROOT);
        int q = normalizeQuality(quality);
        validateResizeParams(mode, percent, width, height, longEdge);
        validateFiles(files);

        List<ImageConvertResultVO> results = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            results.add(convertOne(file, target, mode, percent, width, height, longEdge, keepRatio, q));
        }
        return results;
    }

    // ==================== 内部实现 ====================

    /** 单文件转换；任何异常都收敛为失败结果，不中断批次 */
    private ImageConvertResultVO convertOne(MultipartFile file,
                                            String target,
                                            String mode,
                                            Integer percent,
                                            Integer width,
                                            Integer height,
                                            Integer longEdge,
                                            boolean keepRatio,
                                            int quality) {
        ImageConvertResultVO result = new ImageConvertResultVO();
        result.setSourceName(file.getOriginalFilename());
        result.setTargetFormat(target);
        result.setSizeBefore(file.getSize());
        result.setSuccess(false);
        try {
            byte[] bytes = file.getBytes();
            String sourceFormat = detectFormat(bytes);
            result.setSourceFormat(sourceFormat);

            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) {
                throw new ServiceException(ErrorCode.TOOLS_IMG_FILE_INVALID, "文件不是合法的图片");
            }

            // 1) 计算目标尺寸（必要时缩放）
            int w0 = src.getWidth();
            int h0 = src.getHeight();
            int[] size = targetSize(mode, percent, width, height, longEdge, keepRatio, w0, h0);
            int newW = size[0];
            int newH = size[1];
            boolean resized = newW != w0 || newH != h0;
            result.setResized(resized);

            BufferedImage image = resized ? resize(src, newW, newH) : src;
            result.setWidth(image.getWidth());
            result.setHeight(image.getHeight());

            // 2) 透明通道处理（JPEG/BMP 白底拍平）
            if (FLATTEN_ALPHA_FORMATS.contains(target) && image.getColorModel().hasAlpha()) {
                image = flattenAlpha(image);
            }

            // 3) 重编码
            byte[] encoded = encode(image, target, quality);
            result.setSizeAfter(encoded.length);
            result.setOutputName(buildOutputName(file.getOriginalFilename(), target));
            result.setDataUrl("data:" + FORMAT_META.get(target).getMime() + ";base64,"
                    + Base64.getEncoder().encodeToString(encoded));
            result.setSuccess(true);
            result.setError(null);
        } catch (ServiceException se) {
            result.setError(se.getMessage());
        } catch (IOException | RuntimeException e) {
            log.warn("图片转换失败: file={}, target={}", file.getOriginalFilename(), target, e);
            result.setError("转换失败：" + rootMessage(e));
        }
        return result;
    }

    /** 探测图片真实格式（依赖 ImageIO SPI，TwelveMonkeys 插件优先） */
    private String detectFormat(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            return readers.hasNext() ? readers.next().getFormatName().toUpperCase(Locale.ROOT) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 按模式计算目标尺寸（[宽, 高]），已收敛到 [1, MAX_SIDE]；dimension 不保持比例时直接取指定宽高 */
    private int[] targetSize(String mode, Integer percent, Integer width, Integer height,
                             Integer longEdge, boolean keepRatio, int w0, int h0) {
        return switch (mode) {
            case "none" -> new int[] {w0, h0};
            case "percent" -> new int[] {
                    clampSide(Math.round(w0 * percent / 100.0f)),
                    clampSide(Math.round(h0 * percent / 100.0f))};
            case "dimension" -> {
                if (keepRatio) {
                    double s = Math.min(width / (double) w0, height / (double) h0);
                    yield new int[] {clampSide(Math.round(w0 * (float) s)),
                            clampSide(Math.round(h0 * (float) s))};
                }
                yield new int[] {clampSide(width), clampSide(height)};
            }
            case "longEdge" -> {
                double s = longEdge / (double) Math.max(w0, h0);
                yield new int[] {clampSide(Math.round(w0 * (float) s)),
                        clampSide(Math.round(h0 * (float) s))};
            }
            default -> throw new ServiceException(ErrorCode.TOOLS_IMG_PARAM_INVALID, "缩放模式不合法");
        };
    }

    /** 执行缩放/拉伸（双三次插值，保留透明通道） */
    private BufferedImage resize(BufferedImage src, int newW, int newH) {
        BufferedImage out = new BufferedImage(newW, newH,
                src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, newW, newH, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 透明区域白底拍平（JPEG/BMP 输出用） */
    private BufferedImage flattenAlpha(BufferedImage src) {
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.setComposite(AlphaComposite.SrcOver);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    /** 按目标格式编码；质量参数仅对 JPEG/WEBP/TIFF 生效，参数不支持时自动回退默认 */
    private byte[] encode(BufferedImage image, String target, int quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(target);
        if (!writers.hasNext()) {
            throw new ServiceException(ErrorCode.TOOLS_IMG_FORMAT_UNSUPPORTED, "不支持的图片目标格式：" + target);
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (QUALITY_FORMATS.contains(target) && param != null) {
                try {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(quality / 100.0f);
                } catch (RuntimeException e) {
                    log.debug("格式 {} 不支持显式质量参数，使用默认编码参数: {}", target, e.getMessage());
                    param = writer.getDefaultWriteParam();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
                ios.flush();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.TOOLS_IMG_CONVERT_FAILED, "图片编码失败（目标格式写入出错）");
        } finally {
            writer.dispose();
        }
    }

    // ==================== 参数校验 ====================

    /** 目标格式规范化：空/不支持直接拒绝 */
    private String normalizeFormat(String targetFormat) {
        if (targetFormat == null || targetFormat.isBlank()) {
            throw new ServiceException(ErrorCode.TOOLS_IMG_PARAM_INVALID, "目标格式不能为空");
        }
        String target = targetFormat.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_FORMATS.contains(target)) {
            throw new ServiceException(ErrorCode.TOOLS_IMG_FORMAT_UNSUPPORTED, "不支持的图片目标格式：" + target);
        }
        return target;
    }

    /** 质量参数规范化：null 用默认；越界拒绝 */
    private int normalizeQuality(Integer quality) {
        if (quality == null) {
            return DEFAULT_QUALITY;
        }
        if (quality < 1 || quality > 100) {
            throw new ServiceException(ErrorCode.TOOLS_IMG_PARAM_INVALID, "质量参数须在 1-100 之间");
        }
        return quality;
    }

    /** 缩放参数按模式校验 */
    private void validateResizeParams(String mode, Integer percent, Integer width, Integer height, Integer longEdge) {
        switch (mode) {
            case "none" -> {
                // 不缩放
            }
            case "percent" -> {
                if (percent == null || percent < 1 || percent > 500) {
                    throw new ServiceException(ErrorCode.TOOLS_IMG_PARAM_INVALID, "缩放百分比须在 1-500 之间");
                }
            }
            case "dimension" -> {
                if (width == null || height == null || width < 1 || height < 1
                        || width > MAX_SIDE || height > MAX_SIDE) {
                    throw new ServiceException(ErrorCode.TOOLS_IMG_PARAM_INVALID,
                            "指定宽高须在 1-" + MAX_SIDE + " 之间");
                }
            }
            case "longEdge" -> {
                if (longEdge == null || longEdge < 1 || longEdge > MAX_SIDE) {
                    throw new ServiceException(ErrorCode.TOOLS_IMG_PARAM_INVALID,
                            "最长边须在 1-" + MAX_SIDE + " 之间");
                }
            }
            default -> throw new ServiceException(ErrorCode.TOOLS_IMG_PARAM_INVALID, "缩放模式不合法");
        }
    }

    /** 文件清单校验：数量 / 单文件大小 / 总量 */
    private void validateFiles(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new ServiceException(ErrorCode.TOOLS_IMG_PARAM_INVALID, "请至少选择一个图片文件");
        }
        if (files.length > MAX_FILES) {
            throw new ServiceException(ErrorCode.TOOLS_IMG_BATCH_TOO_MANY,
                    "单次最多转换 " + MAX_FILES + " 个文件");
        }
        long total = 0L;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new ServiceException(ErrorCode.TOOLS_IMG_PARAM_INVALID, "存在空文件，请检查后重试");
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw new ServiceException(ErrorCode.TOOLS_IMG_FILE_TOO_LARGE,
                        "单个文件不能超过 10MB：" + file.getOriginalFilename());
            }
            total += file.getSize();
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new ServiceException(ErrorCode.TOOLS_IMG_FILE_TOO_LARGE, "单次请求总大小不能超过 50MB");
        }
    }

    // ==================== 工具方法 ====================

    /** 单边尺寸收敛到 [1, MAX_SIDE] */
    private int clampSide(int side) {
        return Math.max(1, Math.min(MAX_SIDE, side));
    }

    /** 输出文件名：原名去扩展名 + 目标格式常见扩展名 */
    private String buildOutputName(String sourceName, String target) {
        String base = sourceName == null ? "image" : sourceName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base + "." + FORMAT_META.get(target).getExt();
    }

    /** 异常根因消息（面向用户展示） */
    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }

    /** 上限描述（Controller options 响应用） */
    private ImageLimitsVO limits() {
        return new ImageLimitsVO(MAX_FILES, MAX_FILE_BYTES, MAX_TOTAL_BYTES,
                1, MAX_SIDE, 1, 100, 1, 500);
    }
}
