package com.serverpanel.tools.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.dto.FieldVO;
import com.serverpanel.tools.dto.OptionVO;
import com.serverpanel.tools.dto.QrcodeDecodeBody;
import com.serverpanel.tools.dto.QrcodeDecodeResultVO;
import com.serverpanel.tools.dto.QrcodeGenerateBody;
import com.serverpanel.tools.dto.QrcodeGenerateResultVO;
import com.serverpanel.tools.dto.QrcodeOptionsVO;
import com.serverpanel.tools.dto.QrcodeTypeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 二维码工具：生成（含尺寸/配色/码点样式/Logo/容错等级控制）与识别。
 *
 * <p>三条容易误解的事实，这里写死在服务端，前端不再解释第二遍：
 * <ul>
 *   <li><b>二维码里只有文本</b>：所谓「WiFi 码」「名片码」「付款码」只是扫码端
 *       按约定前缀解析同一串文本（{@code WIFI:...} / {@code BEGIN:VCARD} ...），
 *       码本身没有类型之分；</li>
 *   <li><b>小程序码 / 公众号带参二维码是另一回事</b>：它们必须由微信服务端 API
 *       （appid + secret 换 access_token）生成，服务端产物是微信自绘的圆形码图，
 *       任何第三方都无法离线算出。本工具对微信小程序只提供「含小程序路径/链接的
 *       标准二维码」，并在 {@code /options} 的 notice 里如实说明；</li>
 *   <li><b>边长不能随意拉伸</b>：请求 300px 而实际需要 296 或 320 时，宁可吸附到
 *       模块的整数倍 —— 非整数倍缩放会让相邻码点糊在一起，密集内容直接扫不出来。</li>
 * </ul>
 *
 * <p>全部是 CPU 与内存内的图像处理：不落盘、不发网络请求、不碰宿主资源。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Service
@RequiredArgsConstructor
public class QrcodeService {

    /** 单次编码的内容上限（字符） —— 二维码最大约 2953 字节，留足余量 */
    public static final int MAX_CONTENT_CHARS = 1 << 11;

    /** Logo 图片上限（解码前的 base64 字节数） */
    public static final int MAX_LOGO_BYTES = 512 * 1024;

    /** 待识别图片上限 */
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    /** Logo 占边长比例上限：超过 25% 会吃掉太多数据区，即便 H 级容错也容易扫不出来 */
    public static final double MAX_LOGO_SCALE = 0.25d;

    private static final int DEFAULT_SIZE = 320;
    private static final int MIN_SIZE = 64;
    private static final int MAX_SIZE = 2048;
    private static final int DEFAULT_MARGIN = 4;

    private static final Color DEFAULT_FG = Color.decode("#111827");
    private static final Color DEFAULT_BG = Color.WHITE;

    // ========== 可选清单 ==========

    public QrcodeOptionsVO options() {
        QrcodeOptionsVO vo = new QrcodeOptionsVO();
        vo.setTypes(buildTypes());
        vo.setEccLevels(List.of(
            new OptionVO("L", "L · 约 7%（内容最多，不耐脏）"),
            new OptionVO("M", "M · 约 15%（默认）"),
            new OptionVO("Q", "Q · 约 25%"),
            new OptionVO("H", "H · 约 30%（嵌 Logo 建议选它）")));
        vo.setDotStyles(List.of(
            new OptionVO("SQUARE", "方点"),
            new OptionVO("DOT", "圆点"),
            new OptionVO("ROUNDED", "圆角")));
        vo.setFormats(List.of(new OptionVO("PNG", "PNG（无损，推荐）"),
            new OptionVO("JPEG", "JPEG（体积小）")));
        vo.setDefaultSize(DEFAULT_SIZE);
        vo.setMinSize(MIN_SIZE);
        vo.setMaxSize(MAX_SIZE);
        vo.setDefaultMargin(DEFAULT_MARGIN);
        vo.setMaxContentChars(MAX_CONTENT_CHARS);
        vo.setMaxLogoBytes(MAX_LOGO_BYTES);
        vo.setMaxImageBytes(MAX_IMAGE_BYTES);
        vo.setMaxLogoScale(MAX_LOGO_SCALE);
        return vo;
    }

    private List<QrcodeTypeVO> buildTypes() {
        List<QrcodeTypeVO> types = new ArrayList<>();

        types.add(new QrcodeTypeVO("TEXT", "文本", "任意文字，扫码后直接显示原文",
            List.of(new FieldVO("text", "文本内容", "textarea", true, "", "支持中文，内容越长码点越密", null, null)),
            null));

        types.add(new QrcodeTypeVO("URL", "网址", "扫码后在浏览器打开该地址",
            List.of(new FieldVO("url", "网址", "text", true, "", "以 http:// 或 https:// 开头", null, null)),
            null));

        types.add(new QrcodeTypeVO("WIFI", "WiFi 网络", "扫码后一键连网，无需手动输密码",
            List.of(
                new FieldVO("ssid", "网络名称 (SSID)", "text", true, "", "", null, null),
                new FieldVO("password", "密码", "text", false, "", "加密方式为「无」时可留空", null, null),
                new FieldVO("encryption", "加密方式", "select", false, "WPA", "选「无密码」时可不填密码",
                    null, null, List.of(
                        new OptionVO("WPA", "WPA / WPA2 / WPA3"),
                        new OptionVO("WEP", "WEP"),
                        new OptionVO("nopass", "无密码（开放网络）"))),
                new FieldVO("hidden", "是否隐藏网络", "switch", false, "false", "", null, null)),
            "密码会以明文写进码里，任何人扫到即可入网 —— 公共场合建议另配访客网络"));

        types.add(new QrcodeTypeVO("VCARD", "联系人名片", "扫码后提示「添加到通讯录」",
            List.of(
                new FieldVO("name", "姓名", "text", true, "", "", null, null),
                new FieldVO("tel", "手机号", "text", false, "", "", null, null),
                new FieldVO("email", "邮箱", "text", false, "", "", null, null),
                new FieldVO("org", "公司 / 组织", "text", false, "", "", null, null),
                new FieldVO("title", "职位", "text", false, "", "", null, null),
                new FieldVO("url", "主页", "text", false, "", "", null, null),
                new FieldVO("address", "地址", "text", false, "", "", null, null)),
            null));

        types.add(new QrcodeTypeVO("EMAIL", "邮件", "扫码后唤起邮件客户端并预填收件人与主题",
            List.of(
                new FieldVO("to", "收件人", "text", true, "", "", null, null),
                new FieldVO("subject", "主题", "text", false, "", "", null, null),
                new FieldVO("body", "正文", "textarea", false, "", "", null, null)),
            null));

        types.add(new QrcodeTypeVO("TEL", "电话号码", "扫码后直接拨号",
            List.of(new FieldVO("tel", "电话号码", "text", true, "", "可带国际区号，如 +8613800138000", null, null)),
            null));

        types.add(new QrcodeTypeVO("SMS", "短信", "扫码后预填收件号码与短信内容",
            List.of(
                new FieldVO("number", "接收号码", "text", true, "", "", null, null),
                new FieldVO("body", "短信内容", "textarea", false, "", "", null, null)),
            null));

        types.add(new QrcodeTypeVO("GEO", "地理位置", "扫码后在地图应用中定位",
            List.of(
                new FieldVO("lat", "纬度", "text", true, "", "如 39.9042", null, null),
                new FieldVO("lng", "经度", "text", true, "", "如 116.4074", null, null)),
            null));

        types.add(new QrcodeTypeVO("EVENT", "日历事件", "扫码后加入日历（VEVENT）",
            List.of(
                new FieldVO("title", "标题", "text", true, "", "", null, null),
                new FieldVO("start", "开始时间", "datetime", true, "", "如 2026-10-01T09:00", null, null),
                new FieldVO("end", "结束时间", "datetime", false, "", "", null, null),
                new FieldVO("location", "地点", "text", false, "", "", null, null),
                new FieldVO("desc", "备注", "textarea", false, "", "", null, null)),
            null));

        types.add(new QrcodeTypeVO("BITCOIN", "比特币收款", "扫码后按 URI 规范唤起钱包",
            List.of(
                new FieldVO("address", "收款地址", "text", true, "", "", null, null),
                new FieldVO("amount", "金额 (BTC)", "text", false, "", "可留空由付款方填写", null, null),
                new FieldVO("label", "备注标签", "text", false, "", "", null, null)),
            null));

        types.add(new QrcodeTypeVO("WECHAT_MINIAPP", "微信小程序", "生成含小程序路径的标准二维码",
            List.of(
                new FieldVO("path", "小程序页面路径", "text", true, "pages/index/index", "如 pages/index/index", null, null),
                new FieldVO("query", "携带参数", "text", false, "", "如 id=123&from=poster", null, null),
                new FieldVO("prefix", "域名前缀（可选）", "text", false, "", "填了则生成完整 URL，如 https://example.com", null, null)),
            "微信官方的「小程序码（菊花朵码）」与公众号带场景值二维码只能由微信服务端 API"
                + "（appid + secret 换 access_token 后调用 wxacode.get / qrcode/create）生成，"
                + "本工具无法也无意模拟。这里产出的是「内容为小程序路径或链接的通用二维码」，"
                + "扫码后仍需微信自行完成跳转 —— 需要真小程序码请走微信开放平台接口。"));

        return types;
    }

    // ========== 生成 ==========

    public QrcodeGenerateResultVO generate(QrcodeGenerateBody body) {
        String content = buildContent(body.getContentType(), body.getParams());
        if (content.isBlank()) {
            throw new ServiceException(ErrorCode.TOOLS_QR_CONTENT_UNSUPPORTED, "待编码的内容为空");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new ServiceException(ErrorCode.TOOLS_QR_CONTENT_TOO_LARGE,
                "内容过长：" + content.length() + " 字符，上限 " + MAX_CONTENT_CHARS);
        }

        int requestSize = clamp(body.getSize() == null ? DEFAULT_SIZE : body.getSize(), MIN_SIZE, MAX_SIZE);
        int margin = clamp(body.getMargin() == null ? DEFAULT_MARGIN : body.getMargin(), 0, 16);
        ErrorCorrectionLevel ecc = parseEcc(body.getEcc());

        BitMatrix matrix;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ecc);
            // 静默区自己画：让 ZXing 画的话白边会算进 matrix，模块数与画布的换算关系就乱了
            hints.put(EncodeHintType.MARGIN, 0);
            matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new ServiceException(ErrorCode.TOOLS_QR_ENCODE_FAILED, e.getMessage());
        } catch (IllegalArgumentException e) {
            // 内容超出容量时 ZXing 会以 IllegalArgumentException 抛出
            throw new ServiceException(ErrorCode.TOOLS_QR_CONTENT_TOO_LARGE, "内容超出该容错等级的最大容量，请缩短内容或降低容错等级");
        }

        int moduleCount = matrix.getWidth() + margin * 2;
        int scale = Math.max(1, requestSize / moduleCount);
        int realSize = moduleCount * scale;

        Color fg = parseColor(body.getFgColor(), DEFAULT_FG);
        Color bg = parseColor(body.getBgColor(), DEFAULT_BG);
        Color gradientTo = body.getGradientColor() == null || body.getGradientColor().isBlank()
            ? null
            : parseColor(body.getGradientColor(), null);
        if (gradientTo == null) {
            gradientTo = fg;
        }

        BufferedImage image = new BufferedImage(realSize, realSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(bg);
            g.fillRect(0, 0, realSize, realSize);

            boolean gradient = body.getGradientColor() != null && !body.getGradientColor().isBlank();
            Paint paint = gradient
                ? new GradientPaint(0f, 0f, fg, realSize, realSize, gradientTo)
                : fg;
            g.setPaint(paint);
            drawModules(g, matrix, margin, scale, dotStyle(body.getDotStyle()));

            BufferedImage logo = readLogo(body.getLogoBase64());
            if (logo != null) {
                double ratio = body.getLogoScale() == null ? 0.2d : body.getLogoScale();
                if (ratio <= 0 || ratio > MAX_LOGO_SCALE) {
                    throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "Logo 占边长比例需在 0 ~ " + MAX_LOGO_SCALE + " 之间，过大会导致无法扫描");
                }
                drawLogo(g, logo, realSize, ratio, Boolean.TRUE.equals(body.getLogoRound()), bg);
            }
        } finally {
            g.dispose();
        }

        String format = "JPEG".equalsIgnoreCase(body.getFormat()) ? "JPEG" : "PNG";
        byte[] bytes = writeImage(image, format, bg);

        QrcodeGenerateResultVO vo = new QrcodeGenerateResultVO();
        vo.setContent(content);
        vo.setFormat(format);
        vo.setDataUrl("data:image/" + format.toLowerCase() + ";base64,"
            + Base64.getEncoder().encodeToString(bytes));
        vo.setRequestSize(requestSize);
        vo.setRealSize(realSize);
        vo.setScale(scale);
        vo.setModuleCount(moduleCount);
        vo.setBytes(bytes.length);
        return vo;
    }

    // ========== 识别 ==========

    public QrcodeDecodeResultVO decode(QrcodeDecodeBody body) {
        byte[] raw = decodeBase64(body.getImageBase64(), MAX_IMAGE_BYTES,
            "待识别图片过大（上限 " + MAX_IMAGE_BYTES / 1024 / 1024 + " MB）");
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.TOOLS_QR_IMAGE_INVALID, "图片读取失败：" + e.getMessage());
        }
        if (image == null) {
            throw new ServiceException(ErrorCode.TOOLS_QR_IMAGE_INVALID,
                "无法解析该文件，请上传 PNG / JPEG / GIF 图片");
        }

        QrcodeDecodeResultVO vo = new QrcodeDecodeResultVO();
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result result = new MultiFormatReader().decode(bitmap, hints);
            vo.setFound(true);
            vo.setText(result.getText());
            vo.setFormat(result.getBarcodeFormat() == null ? "" : result.getBarcodeFormat().name());
            vo.setReason("");
            return vo;
        } catch (NotFoundException e) {
            // 扫不出内容不是错误，与 JWT 验签口径一致：返回 found=false + 中文原因
            vo.setFound(false);
            vo.setText("");
            vo.setFormat("");
            vo.setReason("这张图里没有识别出二维码 —— 可能是图片太模糊、码被裁切，或静默区不足");
            return vo;
        } catch (RuntimeException e) {
            throw new ServiceException(ErrorCode.TOOLS_QR_IMAGE_INVALID, "识别过程失败：" + e.getMessage());
        }
    }

    // ========== 内容拼装 ==========

    /**
     * 按内容类型把 params 拼成扫码端认识的那串文本。
     *
     * <p>这些前缀格式（WIFI: / BEGIN:VCARD / SMSTO: / geo: / bitcoin: 等）都不是二维码
     * 规范的一部分，而是各家扫码器约定的「文本前缀协议」，因此拼错一个分号就会失效。
     */
    private String buildContent(String contentType, Map<String, String> params) {
        if (contentType == null || contentType.isBlank()) {
            throw new ServiceException(ErrorCode.TOOLS_QR_CONTENT_UNSUPPORTED, "内容类型不能为空");
        }
        Map<String, String> p = params == null ? Map.of() : params;
        switch (contentType) {
            case "TEXT":
                return str(p.get("text"));
            case "URL":
                return str(p.get("url"));
            case "WIFI": {
                String encryption = str(p.get("encryption"));
                if (encryption.isBlank()) {
                    encryption = str(p.get("password")).isBlank() ? "nopass" : "WPA";
                }
                StringBuilder sb = new StringBuilder("WIFI:");
                sb.append("T:").append(encryption).append(';');
                sb.append("S:").append(escapeWifi(str(p.get("ssid")))).append(';');
                if (!"nopass".equalsIgnoreCase(encryption)) {
                    sb.append("P:").append(escapeWifi(str(p.get("password")))).append(';');
                }
                boolean hidden = Boolean.parseBoolean(str(p.get("hidden")));
                sb.append("H:").append(hidden).append(';');
                return sb.append(';').toString();
            }
            case "VCARD": {
                StringBuilder sb = new StringBuilder("BEGIN:VCARD\nVERSION:3.0\n");
                sb.append("N:").append(escapeVcard(str(p.get("name")))).append(";;;;\n");
                if (has(p, "org")) {
                    sb.append("ORG:").append(escapeVcard(str(p.get("org")))).append('\n');
                }
                if (has(p, "title")) {
                    sb.append("TITLE:").append(escapeVcard(str(p.get("title")))).append('\n');
                }
                if (has(p, "tel")) {
                    sb.append("TEL;TYPE=CELL:").append(str(p.get("tel"))).append('\n');
                }
                if (has(p, "email")) {
                    sb.append("EMAIL:").append(str(p.get("email"))).append('\n');
                }
                if (has(p, "url")) {
                    sb.append("URL:").append(str(p.get("url"))).append('\n');
                }
                if (has(p, "address")) {
                    sb.append("ADR:;;").append(escapeVcard(str(p.get("address")))).append(";;;;\n");
                }
                return sb.append("END:VCARD").toString();
            }
            case "EMAIL": {
                StringBuilder sb = new StringBuilder("mailto:").append(str(p.get("to")));
                List<String> query = new ArrayList<>();
                if (has(p, "subject")) {
                    query.add("subject=" + url(str(p.get("subject"))));
                }
                if (has(p, "body")) {
                    query.add("body=" + url(str(p.get("body"))));
                }
                return query.isEmpty() ? sb.toString() : sb.append('?').append(String.join("&", query)).toString();
            }
            case "TEL":
                return "tel:" + str(p.get("tel"));
            case "SMS": {
                String number = str(p.get("number"));
                String smsBody = str(p.get("body"));
                return smsBody.isBlank() ? "SMSTO:" + number : "SMSTO:" + number + ':' + smsBody;
            }
            case "GEO":
                return "geo:" + str(p.get("lat")) + ',' + str(p.get("lng"));
            case "EVENT": {
                String title = str(p.get("title"));
                String start = toVeventTime(str(p.get("start")));
                String end = str(p.get("end")).isBlank() ? start : toVeventTime(str(p.get("end")));
                StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\n");
                sb.append("SUMMARY:").append(escapeVcard(title)).append('\n');
                sb.append("DTSTART:").append(start).append('\n');
                sb.append("DTEND:").append(end).append('\n');
                if (has(p, "location")) {
                    sb.append("LOCATION:").append(escapeVcard(str(p.get("location")))).append('\n');
                }
                if (has(p, "desc")) {
                    sb.append("DESCRIPTION:").append(escapeVcard(str(p.get("desc")))).append('\n');
                }
                return sb.append("END:VEVENT\nEND:VCALENDAR").toString();
            }
            case "BITCOIN": {
                StringBuilder sb = new StringBuilder("bitcoin:").append(str(p.get("address")));
                List<String> query = new ArrayList<>();
                if (has(p, "amount")) {
                    query.add("amount=" + url(str(p.get("amount"))));
                }
                if (has(p, "label")) {
                    query.add("label=" + url(str(p.get("label"))));
                }
                return query.isEmpty() ? sb.toString() : sb.append('?').append(String.join("&", query)).toString();
            }
            case "WECHAT_MINIAPP": {
                String path = normalizeMiniappPath(str(p.get("path")));
                String query = str(p.get("query")).trim();
                if (query.startsWith("?")) {
                    query = query.substring(1);
                }
                String tail = query.isBlank() ? path : path + '?' + query;
                String prefix = str(p.get("prefix")).trim();
                if (prefix.isBlank()) {
                    return tail;
                }
                // 有兜底域名时拼完整 URL，方便扫码后走 H5 再跳小程序
                return trimEndSlash(prefix) + "/" + (tail.startsWith("/") ? tail.substring(1) : tail);
            }
            default:
                throw new ServiceException(ErrorCode.TOOLS_QR_CONTENT_UNSUPPORTED, "不支持的内容类型：" + contentType);
        }
    }

    // ========== 绘制 ==========

    /**
     * 绘制码点。
     *
     * <p>样式不能只顾好看：二维码识别靠的是「深色模块是否连成片」，圆点之间只要
     * 有一圈反锯齿把接缝吃掉，解码器就会把相邻两个点判成孤立噪声 —— 实测直径等于
     * 模块宽的正圆（相切而不重叠）会导致识别失败。所以圆点刻意向外扩一点点让相邻点
     * 重叠，圆角半径也压到模块宽的 1/4，保证三种样式都能被同一解码器读出。
     */
    private void drawModules(Graphics2D g, BitMatrix matrix, int margin, int scale, DotStyle style) {
        int size = matrix.getWidth();
        // 圆点外扩量：相邻圆点因此重叠 2*overlap，既保持圆形观感又不脱节
        int overlap = style == DotStyle.DOT ? Math.max(1, (int) Math.round(scale * 0.12d)) : 0;
        // 圆角半径压到 1/4：再大就会削掉定位图形的边角，直接影响定位
        int arc = Math.max(1, scale / 4);
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (!matrix.get(col, row)) {
                    continue;
                }
                int x = (margin + col) * scale;
                int y = (margin + row) * scale;
                switch (style) {
                    case DOT -> g.fillOval(x - overlap, y - overlap, scale + overlap * 2, scale + overlap * 2);
                    case ROUNDED -> g.fillRoundRect(x, y, scale, scale, arc, arc);
                    default -> g.fillRect(x, y, scale, scale);
                }
            }
        }
    }

    /**
     * 中心 Logo：白色描边 + 可选圆形裁剪。
     *
     * <p>白色留边是必须的 —— 直接把 Logo 贴在码点上，会让贴边那一圈模块与 Logo
     * 混成一片，这是「加了 Logo 就扫不出来」的头号原因。
     */
    private void drawLogo(Graphics2D g, BufferedImage logo, int canvasSize, double ratio,
        boolean round, Color bg) {
        int target = (int) Math.round(canvasSize * ratio);
        int border = Math.max(2, target / 16);
        int inner = target - border * 2;
        int x = (canvasSize - target) / 2;
        int y = (canvasSize - target) / 2;

        Paint old = g.getPaint();
        g.setPaint(bg);
        g.fillRoundRect(x, y, target, target, round ? target : border, round ? target : border);
        g.setPaint(old);

        if (round) {
            g.setClip(new Ellipse2D.Float(x + border, y + border, inner, inner));
        }
        g.drawImage(logo, x + border, y + border, inner, inner, null);
        g.setClip(null);

        if (round) {
            g.setPaint(bg);
            g.setStroke(new BasicStroke(border));
            g.drawOval(x + border / 2, y + border / 2, target - border, target - border);
        }
        g.setPaint(old);
    }

    private BufferedImage readLogo(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] raw = decodeBase64(base64, MAX_LOGO_BYTES, "Logo 图片过大（上限 512 KB）");
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
            if (image == null) {
                throw new ServiceException(ErrorCode.TOOLS_QR_IMAGE_INVALID, "Logo 不是合法的图片（支持 PNG / JPEG / GIF）");
            }
            return image;
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.TOOLS_QR_IMAGE_INVALID, "Logo 读取失败：" + e.getMessage());
        }
    }

    private byte[] writeImage(BufferedImage image, String format, Color bg) {
        BufferedImage target = image;
        if ("JPEG".equals(format)) {
            // JPEG 不支持透明：先垫一层背景色，否则透明区域会渲染成黑块
            target = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = target.createGraphics();
            try {
                g.setColor(bg);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(target, format, out);
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.TOOLS_QR_ENCODE_FAILED, "图片输出失败：" + e.getMessage());
        }
        return out.toByteArray();
    }

    // ========== 小工具 ==========

    private DotStyle dotStyle(String value) {
        if ("DOT".equalsIgnoreCase(value)) {
            return DotStyle.DOT;
        }
        if ("ROUNDED".equalsIgnoreCase(value)) {
            return DotStyle.ROUNDED;
        }
        return DotStyle.SQUARE;
    }

    private ErrorCorrectionLevel parseEcc(String value) {
        if ("L".equalsIgnoreCase(value)) {
            return ErrorCorrectionLevel.L;
        }
        if ("Q".equalsIgnoreCase(value)) {
            return ErrorCorrectionLevel.Q;
        }
        if ("H".equalsIgnoreCase(value)) {
            return ErrorCorrectionLevel.H;
        }
        return ErrorCorrectionLevel.M;
    }

    /** 支持 #RGB / #RRGGBB；解析不出来时用兜底色而不是报错 —— 配色填错不该让整张码生成失败 */
    private Color parseColor(String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        try {
            if (hex.length() == 3) {
                StringBuilder sb = new StringBuilder();
                for (char c : hex.toCharArray()) {
                    sb.append(c).append(c);
                }
                hex = sb.toString();
            }
            if (hex.length() != 6) {
                return fallback;
            }
            return Color.decode("#" + hex);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private byte[] decodeBase64(String base64, int maxBytes, String tooLargeMessage) {
        String raw = base64.trim();
        // 前端读文件时常常连 data URL 前缀一起传，这里一并兼容
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:")) {
            raw = comma > 0 ? raw.substring(comma + 1) : "";
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.TOOLS_QR_IMAGE_INVALID, "不是合法的 base64 图片数据");
        }
        if (bytes.length == 0) {
            throw new ServiceException(ErrorCode.TOOLS_QR_IMAGE_INVALID, "图片内容为空");
        }
        if (bytes.length > maxBytes) {
            throw new ServiceException(ErrorCode.TOOLS_QR_IMAGE_INVALID, tooLargeMessage);
        }
        return bytes;
    }

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private String str(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean has(Map<String, String> params, String key) {
        String value = params.get(key);
        return value != null && !value.trim().isBlank();
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** WIFI 串里 ; , : \ 是分隔符，必须转义 */
    private String escapeWifi(String value) {
        return value.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:");
    }

    /** vCard 的逗号、分号、反斜杠与换行需要转义 */
    private String escapeVcard(String value) {
        return value.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n");
    }

    /** 小程序路径统一补 leading slash，去掉尾部斜杠 */
    private String normalizeMiniappPath(String path) {
        String p = path.trim();
        if (p.isBlank()) {
            return "pages/index/index";
        }
        p = trimEndSlash(p);
        return p.startsWith("/") ? p : "/" + p;
    }

    private String trimEndSlash(String value) {
        String v = value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /**
     * 把 {@code <input type="datetime-local">} 的 2026-10-01T09:00 转成 VEVENT 的 20261001T090000。
     *
     * <p>长度判断要按「时间部分的位数」而不是整串长度：整串里还夹着一个 T，
     * 按总长补零会把 0900 补成 090000 的判断条件算错。
     */
    private String toVeventTime(String raw) {
        String v = str(raw).replace("-", "").replace(":", "").replace(" ", "T");
        int t = v.indexOf('T');
        String date = t >= 0 ? v.substring(0, t) : v;
        String time = t >= 0 ? v.substring(t + 1) : "";
        if (time.length() == 4) {
            time = time + "00";
        }
        if (time.isBlank()) {
            time = "000000";
        }
        return date + "T" + time;
    }

    private enum DotStyle {
        SQUARE, DOT, ROUNDED
    }
}
