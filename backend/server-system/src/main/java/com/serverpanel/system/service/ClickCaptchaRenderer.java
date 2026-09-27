package com.serverpanel.system.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 点选验证码图片渲染器（纯 JDK AWT，无第三方依赖）。
 *
 * <p>在一张浅色底图上随机撒布若干字符：前 {@code targetCount} 个是目标字符
 * （数组顺序即要求的点击顺序），其余为干扰字符。每个字符带随机字体、字号、
 * 颜色与旋转角度，并叠加干扰线与噪点，抬高 OCR / 脚本识别门槛。
 *
 * <p>渲染结果同时返回每个<b>目标字符的中心像素坐标</b> —— 该坐标只交给后端
 * {@link CaptchaService} 写入 Redis，<b>绝不下发前端</b>；前端拿到的只有图片和
 * 「要依次点击哪些字符」，因此即便脚本能解析响应也拿不到答案。
 *
 * <p>字体使用 JDK 逻辑字体（SansSerif / Serif / Monospaced），由宿主 fontconfig
 * 映射到实际字体。若系统无任何可渲染字体（常见于精简版 Linux 容器），
 * {@link #render} 抛 {@link IllegalStateException}，由调用方转成 1040 明确报错，
 * 而不是下发一张空白图让用户无从下手。
 *
 * @author zhaodc
 * @since 2026-09-27 UTC+8
 */
public final class ClickCaptchaRenderer {

    static {
        // 容器 / 无 X11 环境下必须在取用 AWT 前声明无头模式，否则 Font / Graphics 初始化可能失败
        System.setProperty("java.awt.headless", "true");
    }

    /** 字符池：剔除 I / O / 0 / 1 等易混淆字形，共 32 个 */
    private static final String CHAR_POOL = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 候选字体（JDK 逻辑字体，由宿主机 fontconfig 解析） */
    private static final String[] FONT_NAMES = {Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED};

    /** 画布底色（浅灰蓝，保证深色字符对比度） */
    private static final Color BACKGROUND = new Color(246, 248, 251);

    private ClickCaptchaRenderer() {}

    /** 一个目标字符及其中心像素坐标 */
    public record Target(String ch, int x, int y) {}

    /**
     * 渲染结果：图片 + 目标字符坐标 + 画布尺寸 + 安全命中半径。
     *
     * <p>{@code safeRadius} 由格子尺寸推出（约半格再留余量），保证命中圆不会覆盖到
     * 相邻字符 —— 字符越多格子越小，半径自动收紧，避免「点错字也算过」。
     */
    public record Result(BufferedImage image, List<Target> targets, int width, int height,
            int safeRadius) {}

    /**
     * 渲染一张点选验证码。
     *
     * @param width       画布宽（像素），非正数时回退 300
     * @param height      画布高（像素），非正数时回退 160
     * @param targetCount 目标字符个数（结果中按点击顺序排列）
     * @param decoyCount  干扰字符个数
     * @return 渲染结果（含图片与目标字符中心坐标）
     * @throws IllegalStateException 宿主机无可用字体，无法渲染
     */
    public static Result render(int width, int height, int targetCount, int decoyCount) {
        int w = width > 0 ? width : 300;
        int h = height > 0 ? height : 160;
        int targetTotal = Math.min(Math.max(1, targetCount), CHAR_POOL.length());
        int total = Math.min(targetTotal + Math.max(0, decoyCount), CHAR_POOL.length());

        Random random = new Random();
        List<Character> chars = pickChars(total, random);

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, w, h);
            drawNoise(g, w, h, random);

            // 网格布局：3 列若干行，每个字符占一格并随机抖动，既避免严重重叠又保持位置不可预测
            int cols = 3;
            int rows = (int) Math.ceil((double) total / cols);
            int cellW = Math.max(1, w / cols);
            int cellH = Math.max(1, h / rows);
            int jitterX = Math.max(1, cellW / 6);
            int jitterY = Math.max(1, cellH / 8);

            List<Integer> cells = new ArrayList<>(cols * rows);
            for (int i = 0; i < cols * rows; i++) {
                cells.add(i);
            }
            Collections.shuffle(cells, random);

            List<Target> targets = new ArrayList<>(targetTotal);
            for (int i = 0; i < total; i++) {
                int cell = cells.get(i);
                int cx = (cell % cols) * cellW + cellW / 2 + random.nextInt(2 * jitterX + 1) - jitterX;
                int cy = (cell / cols) * cellH + cellH / 2 + random.nextInt(2 * jitterY + 1) - jitterY;
                cx = clamp(cx, 22, Math.max(22, w - 22));
                cy = clamp(cy, 22, Math.max(22, h - 16));
                char ch = chars.get(i);
                drawChar(g, ch, cx, cy, random);
                if (i < targetTotal) {
                    targets.add(new Target(String.valueOf(ch), cx, cy));
                }
            }
            // 安全命中半径：取较小格边的一半再留 4px 余量，确保命中圆不侵入相邻格子
            int safeRadius = Math.max(14, Math.min(cellW, cellH) / 2 - 4);
            return new Result(image, targets, w, h, safeRadius);
        } finally {
            g.dispose();
        }
    }

    /** 从字符池洗牌取 {@code total} 个互不相同的字符（前段由调用方作为目标，天然无重复） */
    private static List<Character> pickChars(int total, Random random) {
        List<Character> pool = new ArrayList<>(CHAR_POOL.length());
        for (char c : CHAR_POOL.toCharArray()) {
            pool.add(c);
        }
        Collections.shuffle(pool, random);
        return new ArrayList<>(pool.subList(0, Math.min(total, pool.size())));
    }

    /**
     * 随机字体 / 字号 / 颜色 / 旋转角度绘制单个字符，(cx, cy) 为其中心点。
     *
     * <p>旋转控制在 ±15°：既抬高 OCR 门槛，又不至于让 6/8、5/S 之类字形互相混淆 ——
     * 同一张图里可能出现形近字符，辨识度不足会误伤真人（点错即失败）。
     */
    private static void drawChar(Graphics2D g, char ch, int cx, int cy, Random random) {
        Font font = new Font(
                FONT_NAMES[random.nextInt(FONT_NAMES.length)], Font.BOLD, 32 + random.nextInt(11));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int charWidth = fm.charWidth(ch);
        if (charWidth <= 0) {
            throw new IllegalStateException("当前运行环境无可用字体，无法渲染验证码图片");
        }
        g.setColor(new Color(
                30 + random.nextInt(70), 30 + random.nextInt(70), 40 + random.nextInt(80)));
        AffineTransform origin = g.getTransform();
        g.rotate(Math.toRadians(random.nextInt(31) - 15), cx, cy);
        // 基线位置按 (ascent - descent) / 2 下移，使字形视觉中心落在 (cx, cy)
        g.drawString(String.valueOf(ch), cx - charWidth / 2,
                cy + (fm.getAscent() - fm.getDescent()) / 2);
        g.setTransform(origin);
    }

    /** 干扰线与噪点：颜色浅、分布均匀，不遮挡字符辨识 */
    private static void drawNoise(Graphics2D g, int w, int h, Random random) {
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 5; i++) {
            g.setColor(new Color(
                    190 + random.nextInt(50), 195 + random.nextInt(50), 200 + random.nextInt(45)));
            g.drawLine(random.nextInt(w), random.nextInt(h), random.nextInt(w), random.nextInt(h));
        }
        for (int i = 0; i < 60; i++) {
            g.setColor(new Color(
                    180 + random.nextInt(65), 185 + random.nextInt(60), 190 + random.nextInt(60)));
            g.fillOval(random.nextInt(w), random.nextInt(h), 2, 2);
        }
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
