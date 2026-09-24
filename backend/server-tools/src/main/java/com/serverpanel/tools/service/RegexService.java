package com.serverpanel.tools.service;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.dto.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 正则工具 Service：按场景生成正则 + 测试（匹配 / 结构解析 / 替换预览）。
 *
 * <p>两个安全事实写在最前面：
 * <ul>
 *   <li><b>Java 无法对正则匹配设置超时</b>，灾难性回溯只能靠「模式限长 +
 *       文本限长 + 匹配条数封顶」来缓解，三条上限都定义为本类常量；</li>
 *   <li><b>正则语法错误不算接口错误</b>：/test 对编译失败返回 valid=false +
 *       中文原因（沿用二维码识别「识别不出不算错误」的口径），方便前端
 *       做逐次编辑的即时校验而不弹全局错误。</li>
 * </ul>
 *
 * <p>纯内存计算：不落盘、不发网络请求、不碰宿主资源。
 *
 * @author zhaodc
 * @since 2026-09-24 UTC+8
 */
@Service
public class RegexService {

    /** 测试用模式长度上限（模板表可存 2000，测试场景 1000 已足够） */
    public static final int MAX_PATTERN_LENGTH = 1000;

    /** 待匹配文本长度上限 */
    public static final int MAX_TEST_TEXT_LENGTH = 100_000;

    /** 单次返回的匹配条数上限 */
    public static final int MAX_MATCHES = 500;

    /** 生成场景生成的模式同样要能编译 —— 拼错参数在服务端就拦下 */
    private static final String GENERAL_NOTE = "生成的模式默认是查找式匹配（局部命中即算匹配）；用于整串校验时，请在模式前后补 ^ 与 $。";

    // ========== 对外能力 ==========

    /** 场景清单、标志清单与各项上限 */
    public RegexOptionsVO options() {
        RegexOptionsVO vo = new RegexOptionsVO();
        vo.setScenarios(buildScenarios());
        vo.setFlags(List.of(
            new RegexFlagVO("i", "忽略大小写", "CASE_INSENSITIVE，A-Z 与 a-z 等价"),
            new RegexFlagVO("m", "多行模式", "MULTILINE，^ 与 $ 匹配每一行的首尾"),
            new RegexFlagVO("s", "点号通配", "DOTALL，. 开始匹配换行符"),
            new RegexFlagVO("x", "宽松模式", "COMMENTS，忽略模式中的空白与 # 注释"),
            new RegexFlagVO("u", "Unicode 大小写", "UNICODE_CASE，配合 i 做全 Unicode 折叠")));
        vo.setLimits(new RegexLimitsVO(
            MAX_PATTERN_LENGTH, MAX_TEST_TEXT_LENGTH, MAX_MATCHES, RegexTemplateService.MAX_TEMPLATE_COUNT));
        return vo;
    }

    /** 按场景 + 参数生成正则 */
    public RegexGenerateResultVO generate(RegexGenerateBody body) {
        String flags = body.getFlags() == null ? "" : body.getFlags().trim();
        parseFlags(flags);
        Map<String, String> params = body.getParams() == null ? Map.of() : body.getParams();
        Generated g = switch (body.getScenario()) {
            case "PHONE_CN" -> phoneCn(params);
            case "EMAIL" -> email(params);
            case "ID_CARD" -> idCard(params);
            case "IPV4" -> ipv4(params);
            case "URL" -> url(params);
            case "DATE" -> date(params);
            case "PASSWORD" -> password(params);
            case "CHAR_RULE" -> charRule(params);
            default -> throw new ServiceException(ErrorCode.TOOLS_REGEX_SCENARIO_UNSUPPORTED);
        };
        // 拼出来的模式必须可编译：参数错误在这里以中文报出，而不是让用户拿去测试时才发现
        try {
            Pattern.compile(g.pattern());
        } catch (PatternSyntaxException e) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_PARAM_INVALID,
                "生成的正则不合法：" + e.getDescription() + "（位置 " + e.getIndex() + "），请检查参数");
        }
        List<String> notes = new ArrayList<>(g.notes());
        notes.add(GENERAL_NOTE);
        return new RegexGenerateResultVO(g.pattern(), flags, g.explanation(), g.samples(), notes);
    }

    /** 测试：匹配明细 + 结构解析 + 可选替换预览（语法错误返回 valid=false，不抛错） */
    public RegexTestResultVO test(RegexTestBody body) {
        String flags = body.getFlags() == null ? "" : body.getFlags().trim();
        int flagBits = parseFlags(flags);
        if (body.getPattern().length() > MAX_PATTERN_LENGTH) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_PATTERN_TOO_LARGE,
                "正则表达式超过 " + MAX_PATTERN_LENGTH + " 字符上限");
        }
        if (body.getText().length() > MAX_TEST_TEXT_LENGTH) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_TEXT_TOO_LARGE,
                "待匹配文本超过 " + MAX_TEST_TEXT_LENGTH + " 字符上限");
        }

        RegexTestResultVO vo = new RegexTestResultVO();
        vo.setFlagsApplied(flags);
        vo.setMatches(List.of());
        vo.setStructure(List.of());

        Pattern pattern;
        try {
            pattern = Pattern.compile(body.getPattern(), flagBits);
        } catch (PatternSyntaxException e) {
            vo.setValid(false);
            vo.setErrorMessage("正则语法错误：" + e.getDescription() + "（位置 " + e.getIndex() + "）");
            return vo;
        }
        vo.setValid(true);
        vo.setStructure(parseStructure(body.getPattern()));

        Matcher matcher = pattern.matcher(body.getText());
        // 命名组：Java 20+ 的 Matcher.namedGroups()（本项目 JDK 21），转成 组号 -> 组名
        Map<Integer, String> nameByIndex = new HashMap<>();
        matcher.namedGroups().forEach((name, index) -> nameByIndex.put(index, name));

        List<RegexMatchVO> matches = new ArrayList<>();
        boolean truncated = false;
        int textLen = body.getText().length();
        int from = 0;
        while (true) {
            if (!matcher.find(from)) {
                break;
            }
            if (matches.size() >= MAX_MATCHES) {
                truncated = true;
                break;
            }
            matches.add(toMatch(matcher, nameByIndex));
            // 空匹配会原地卡死：强制从下一位继续（结尾处的空匹配直接收工）
            from = matcher.end() == matcher.start()
                ? (matcher.end() >= textLen ? -1 : matcher.end() + 1)
                : matcher.end();
            if (from < 0) {
                break;
            }
        }
        vo.setMatchCount(matches.size());
        vo.setTruncated(truncated);
        vo.setMatches(matches);

        if (body.getReplacement() != null) {
            try {
                vo.setReplacementPreview(matcher.replaceAll(body.getReplacement()));
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                vo.setErrorMessage("替换串不合法（组引用不存在或格式错误）：" + e.getMessage());
            }
        }
        return vo;
    }

    // ========== flags 解析（模板保存也复用本方法做白名单校验） ==========

    /**
     * 解析 flags 字符串为 {@link Pattern} 标志位。
     *
     * <p>仅允许 i / m / s / x / u，且不允许重复 —— 重复在语义上无害，
     * 但大概率是手滑，宁可拦下。
     */
    public static int parseFlags(String flags) {
        if (flags == null || flags.isEmpty()) {
            return 0;
        }
        if (flags.length() > 5) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_FLAGS_INVALID);
        }
        int bits = 0;
        for (char c : flags.toCharArray()) {
            int bit = switch (c) {
                case 'i' -> Pattern.CASE_INSENSITIVE;
                case 'm' -> Pattern.MULTILINE;
                case 's' -> Pattern.DOTALL;
                case 'x' -> Pattern.COMMENTS;
                case 'u' -> Pattern.UNICODE_CASE;
                default -> -1;
            };
            if (bit < 0) {
                throw new ServiceException(ErrorCode.TOOLS_REGEX_FLAGS_INVALID, "不支持的标志字符：" + c);
            }
            if ((bits & bit) != 0) {
                throw new ServiceException(ErrorCode.TOOLS_REGEX_FLAGS_INVALID, "标志重复：" + c);
            }
            bits |= bit;
        }
        return bits;
    }

    // ========== 匹配明细 ==========

    private static RegexMatchVO toMatch(Matcher matcher, Map<Integer, String> nameByIndex) {
        List<RegexGroupVO> groups = new ArrayList<>();
        groups.add(new RegexGroupVO(0, null, matcher.group()));
        for (int i = 1; i <= matcher.groupCount(); i++) {
            groups.add(new RegexGroupVO(i, nameByIndex.get(i), matcher.group(i)));
        }
        return new RegexMatchVO(matcher.start(), matcher.end(), matcher.group(), groups);
    }

    // ========== 结构解析（教学级手写扫描器） ==========

    /**
     * 把模式切成人能读懂的分段。
     *
     * <p>不追求 AST 级精确（例如不做字符类内量词的特判），只保证两件事：
     * 每个片段的原文截取不出错、每句中文说明不误导。嵌套深度按 '(' 压栈、
     * ')' 出栈维护，供前端缩进。
     */
    private static List<RegexTokenVO> parseStructure(String pattern) {
        List<RegexTokenVO> tokens = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int depth = 0;
        int i = 0;
        int len = pattern.length();
        while (i < len) {
            char c = pattern.charAt(i);
            if (!isSpecial(c)) {
                literal.append(c);
                i++;
                continue;
            }
            flushLiteral(tokens, literal, depth);
            switch (c) {
                case '\\' -> i = readEscape(pattern, i, tokens, depth);
                case '[' -> i = readCharClass(pattern, i, tokens, depth);
                case '(' -> {
                    String head = groupHead(pattern, i);
                    tokens.add(new RegexTokenVO(head, "group", depth, describeGroupHead(head)));
                    depth++;
                    i += head.length();
                }
                case ')' -> {
                    depth = Math.max(0, depth - 1);
                    tokens.add(new RegexTokenVO(")", "groupEnd", depth, "分组结束"));
                    i++;
                }
                case '*', '+' -> i = readQuantifier(pattern, i, tokens, depth);
                case '{' -> {
                    // 单独出现的 '{' 若不成量词结构，Java 会当字面量，这里按量词尝试、失败回落字面量
                    int close = pattern.indexOf('}', i);
                    if (close > i && isQuantifierBraces(pattern.substring(i, close + 1))) {
                        i = readQuantifier(pattern, i, tokens, depth);
                    } else {
                        literal.append(c);
                        i++;
                    }
                }
                case '?' -> {
                    tokens.add(new RegexTokenVO("?", "quantifier", depth, "? 前面的元素出现 0 次或 1 次"));
                    i = readQuantifierSuffix(pattern, i + 1, tokens, depth);
                }
                case '^' -> {
                    tokens.add(new RegexTokenVO("^", "anchor", depth, "行首锚点（配合 m 标志匹配每一行开头）"));
                    i++;
                }
                case '$' -> {
                    tokens.add(new RegexTokenVO("$", "anchor", depth, "行尾锚点（配合 m 标志匹配每一行结尾）"));
                    i++;
                }
                case '|' -> {
                    tokens.add(new RegexTokenVO("|", "alternation", depth, "或：左右两边任选其一"));
                    i++;
                }
                case '.' -> {
                    tokens.add(new RegexTokenVO(".", "dot", depth, "任意一个字符（默认不含换行；s 标志下含）"));
                    i++;
                }
                default -> {
                    literal.append(c);
                    i++;
                }
            }
        }
        flushLiteral(tokens, literal, depth);
        return tokens;
    }

    /** 连续普通字符合并为一个字面量片段 */
    private static void flushLiteral(List<RegexTokenVO> tokens, StringBuilder literal, int depth) {
        if (!literal.isEmpty()) {
            tokens.add(new RegexTokenVO(literal.toString(), "literal", depth, "字面量：按原文匹配"));
            literal.setLength(0);
        }
    }

    private static boolean isSpecial(char c) {
        return c == '\\' || c == '[' || c == '(' || c == ')' || c == '*' || c == '+'
            || c == '?' || c == '{' || c == '^' || c == '$' || c == '|' || c == '.';
    }

    /** 转义序列：\d \w \s \b 等给固定说明，\Q...\E 整段按字面量 */
    private static int readEscape(String pattern, int i, List<RegexTokenVO> tokens, int depth) {
        int len = pattern.length();
        if (i + 1 >= len) {
            tokens.add(new RegexTokenVO("\\", "literal", depth, "悬空的转义符（模式在此结束）"));
            return i + 1;
        }
        char next = pattern.charAt(i + 1);
        if (next == 'Q') {
            int end = pattern.indexOf("\\E", i + 2);
            int stop = end < 0 ? len : Math.min(len, end + 2);
            tokens.add(new RegexTokenVO(pattern.substring(i, stop), "literal", depth,
                "\\Q...\\E 引用段：内部全部按字面量处理"));
            return stop;
        }
        String seq = pattern.substring(i, Math.min(len, i + 2));
        tokens.add(new RegexTokenVO(seq, "literal", depth, describeEscape(next)));
        return i + 2;
    }

    private static String describeEscape(char c) {
        return switch (c) {
            case 'd' -> "\\d 一个数字（0-9）";
            case 'D' -> "\\D 一个非数字字符";
            case 'w' -> "\\w 一个单词字符（字母、数字、下划线）";
            case 'W' -> "\\W 一个非单词字符";
            case 's' -> "\\s 一个空白字符（空格、制表、换行等）";
            case 'S' -> "\\S 一个非空白字符";
            case 'b' -> "\\b 单词边界（零宽，不消费字符）";
            case 'B' -> "\\B 非单词边界（零宽）";
            case 'n' -> "\\n 换行符";
            case 't' -> "\\t 制表符";
            case 'r' -> "\\r 回车符";
            default -> "转义：按字面量匹配 " + c;
        };
    }

    /** 字符类 [...]：处理否定、首个 ] 字面量与类内转义 */
    private static int readCharClass(String pattern, int i, List<RegexTokenVO> tokens, int depth) {
        int len = pattern.length();
        int j = i + 1;
        boolean negated = j < len && pattern.charAt(j) == '^';
        if (negated) {
            j++;
        }
        if (j < len && pattern.charAt(j) == ']') {
            j++; // 紧跟的 ] 是字面量成员
        }
        while (j < len && pattern.charAt(j) != ']') {
            if (pattern.charAt(j) == '\\' && j + 1 < len) {
                j++;
            }
            j++;
        }
        int stop = Math.min(len, j + 1); // 含收尾的 ]
        tokens.add(new RegexTokenVO(pattern.substring(i, stop), "charClass", depth,
            negated ? "否定字符类：匹配不在括号内的任意一个字符" : "字符类：匹配括号内的任意一个字符（支持 a-z 区间）"));
        return stop;
    }

    /** 量词：* + 与 {n} {n,} {n,m}，随后检查懒惰 / 占有后缀 */
    private static int readQuantifier(String pattern, int i, List<RegexTokenVO> tokens, int depth) {
        int len = pattern.length();
        char c = pattern.charAt(i);
        int j;
        String base;
        if (c == '*') {
            base = "*";
            j = i + 1;
        } else if (c == '+') {
            base = "+";
            j = i + 1;
        } else {
            int close = pattern.indexOf('}', i);
            if (close < 0) {
                close = len - 1;
            }
            j = Math.min(len, close + 1);
            base = pattern.substring(i, j);
        }
        String desc = describeQuantifier(base);
        String full = base;
        if (j < len && (pattern.charAt(j) == '?' || pattern.charAt(j) == '+')) {
            full = base + pattern.charAt(j);
            desc += pattern.charAt(j) == '?' ? "，懒惰模式：尽量少匹配" : "，占有模式：不交还匹配（不吃回溯）";
            j++;
        }
        tokens.add(new RegexTokenVO(full, "quantifier", depth, desc));
        return j;
    }

    /** 量词的懒惰（?）/ 占有（+）后缀：存在则追加一个说明 token */
    private static int readQuantifierSuffix(String pattern, int i, List<RegexTokenVO> tokens, int depth) {
        if (i < pattern.length() && (pattern.charAt(i) == '?' || pattern.charAt(i) == '+')) {
            String suffix = String.valueOf(pattern.charAt(i));
            tokens.add(new RegexTokenVO(suffix, "quantifier", depth,
                suffix.equals("?") ? "懒惰模式：尽量少匹配" : "占有模式：不交还匹配（不吃回溯）"));
            return i + 1;
        }
        return i;
    }

    private static String describeQuantifier(String base) {
        if (base.equals("*")) {
            return "* 前面的元素出现 0 次或多次（尽量多）";
        }
        if (base.equals("+")) {
            return "+ 前面的元素出现 1 次或多次（尽量多）";
        }
        if (base.startsWith("{") && base.endsWith("}")) {
            String inner = base.substring(1, base.length() - 1);
            if (inner.contains(",")) {
                String[] parts = inner.split(",", -1);
                if (parts[1].isEmpty()) {
                    return base + " 前面的元素至少重复 " + parts[0] + " 次";
                }
                return base + " 前面的元素重复 " + parts[0] + " 到 " + parts[1] + " 次";
            }
            return base + " 前面的元素恰好重复 " + inner + " 次";
        }
        return base + " 重复指定次数";
    }

    private static boolean isQuantifierBraces(String text) {
        if (!text.startsWith("{") || !text.endsWith("}") || text.length() < 3) {
            return false;
        }
        String inner = text.substring(1, text.length() - 1);
        if (inner.isEmpty()) {
            return false;
        }
        String[] parts = inner.split(",", -1);
        if (parts.length > 2) {
            return false;
        }
        try {
            if (!parts[0].isBlank()) {
                Integer.parseInt(parts[0].trim());
            }
            if (parts.length == 2 && !parts[1].isBlank()) {
                Integer.parseInt(parts[1].trim());
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 识别分组开头：捕获 / 非捕获 / 命名 / 四种环视 */
    private static String groupHead(String pattern, int i) {
        if (pattern.startsWith("(?<=", i) || pattern.startsWith("(?<!", i)) {
            return pattern.substring(i, i + 4);
        }
        if (pattern.startsWith("(?<", i)) {
            int close = pattern.indexOf('>', i);
            if (close > i + 3) {
                return pattern.substring(i, close + 1);
            }
        }
        if (pattern.startsWith("(?=", i) || pattern.startsWith("(?!", i)
            || pattern.startsWith("(?:", i)) {
            return pattern.substring(i, i + 3);
        }
        return "(";
    }

    private static String describeGroupHead(String head) {
        return switch (head) {
            case "(" -> "捕获组开始：按 ( 出现顺序编号，可用 $1、\\1 引用";
            case "(?:" -> "非捕获分组开始：仅用于组合与限定，不占用组号";
            case "(?=" -> "正向环视开始：右侧必须能匹配，但不消费字符";
            case "(?!" -> "负向环视开始：右侧必须不能匹配，不消费字符";
            case "(?<=" -> "正向后行环视开始：左侧必须能匹配，不消费字符";
            case "(?<!" -> "负向后行环视开始：左侧必须不能匹配，不消费字符";
            default -> {
                String name = head.substring(3, head.length() - 1);
                yield "命名捕获组开始：组名 " + name + "，可用 ${" + name + "} 引用";
            }
        };
    }

    // ========== 场景生成（8 个预设） ==========

    /** 生成结果中间载体 */
    private record Generated(String pattern, List<String> explanation, List<String> samples, List<String> notes) {
    }

    private List<RegexScenarioVO> buildScenarios() {
        List<RegexScenarioVO> list = new ArrayList<>();
        list.add(new RegexScenarioVO("PHONE_CN", "中国大陆手机号", "11 位手机号，可携带 +86 国家码",
            List.of(
                new FieldVO("strict", "号段校验", "select", true, "strict", null, null, null,
                    List.of(new OptionVO("strict", "严格（校验 1 + 第二位 3-9）"),
                        new OptionVO("loose", "宽松（仅要求 11 位数字）"))),
                new FieldVO("allowPrefix", "国家码", "select", true, "none", null, null, null,
                    List.of(new OptionVO("none", "不带国家码"),
                        new OptionVO("optional", "+86 可选"),
                        new OptionVO("required", "+86 必须携带"))))));
        list.add(new RegexScenarioVO("EMAIL", "邮箱地址", "账号@域名，可选常见邮箱或自定义后缀",
            List.of(
                new FieldVO("domainMode", "域名", "select", true, "any", null, null, null,
                    List.of(new OptionVO("any", "任意域名"),
                        new OptionVO("common", "常见邮箱服务商"),
                        new OptionVO("custom", "自定义后缀"))),
                new FieldVO("customDomain", "自定义后缀", "text", false, "", "domainMode 选「自定义后缀」时必填，如 example.com", null, null),
                new FieldVO("allowPlus", "允许 + 别名", "switch", false, "0", "a+b@example.com 这类加号别名", null, null))));
        list.add(new RegexScenarioVO("ID_CARD", "18 位身份证号", "按 GB 11643 格式校验（不含校验位算法）",
            List.of(
                new FieldVO("allowLowerX", "允许小写 x", "switch", false, "0", "末位校验码是否允许小写 x", null, null))));
        list.add(new RegexScenarioVO("IPV4", "IPv4 地址", "点分四段，可附加端口",
            List.of(
                new FieldVO("strict", "取值校验", "select", true, "strict", null, null, null,
                    List.of(new OptionVO("strict", "严格（每段 0-255）"),
                        new OptionVO("loose", "宽松（1-3 位数字）"))),
                new FieldVO("allowPort", "附加端口", "switch", false, "0", "允许 :8080 这类端口后缀", null, null))));
        list.add(new RegexScenarioVO("URL", "URL 地址", "scheme://host[/path]",
            List.of(
                new FieldVO("scheme", "协议", "select", true, "http", null, null, null,
                    List.of(new OptionVO("http", "http / https"),
                        new OptionVO("https", "仅 https"),
                        new OptionVO("any", "任意协议"))),
                new FieldVO("allowPath", "匹配路径", "switch", false, "1", "允许 /path?query 这类后缀", null, null))));
        list.add(new RegexScenarioVO("DATE", "日期", "yyyy-MM-dd 风格，分隔符与 0 填充可选",
            List.of(
                new FieldVO("sep", "分隔符", "select", true, "-", null, null, null,
                    List.of(new OptionVO("-", "短横线 -"),
                        new OptionVO("/", "斜杠 /"),
                        new OptionVO(".", "点号 ."))),
                new FieldVO("zeroPad", "0 填充", "select", true, "strict", null, null, null,
                    List.of(new OptionVO("strict", "必须 0 填充（09）"),
                        new OptionVO("loose", "允许单数字（9）"))),
                new FieldVO("withTime", "附加时间", "switch", false, "0", "附加 HH:mm(:ss) 时间部分", null, null))));
        list.add(new RegexScenarioVO("PASSWORD", "强密码", "用环视同时要求多种字符（长度只管下限）",
            List.of(
                new FieldVO("minLen", "最小长度", "number", true, "8", "6 ~ 64", 6, 64),
                new FieldVO("needUpper", "必须含大写字母", "switch", false, "1", null, null, null),
                new FieldVO("needLower", "必须含小写字母", "switch", false, "1", null, null, null),
                new FieldVO("needDigit", "必须含数字", "switch", false, "1", null, null, null),
                new FieldVO("needSpecial", "必须含特殊字符", "switch", false, "0", null, null, null),
                new FieldVO("specialChars", "特殊字符集合", "text", false, "!@#$%^&*", "needSpecial 开启时生效", null, null))));
        list.add(new RegexScenarioVO("CHAR_RULE", "自定义字符规则", "字符集 + 长度 + 前后缀字面量自由拼装",
            List.of(
                new FieldVO("charset", "字符集", "text", true, "A-Za-z0-9", "直接写字符类内容，如 a-z、A-Za-z0-9_", null, null),
                new FieldVO("minLen", "最小长度", "number", true, "6", "1 ~ 1000", 1, 1000),
                new FieldVO("maxLen", "最大长度", "number", false, "", "留空或 0 表示不设上限", 1, 1000),
                new FieldVO("literalPrefix", "前置字面量", "text", false, "", "匹配结果必须以它开头", null, null),
                new FieldVO("literalSuffix", "后置字面量", "text", false, "", "匹配结果必须以它结尾", null, null))));
        return list;
    }

    private Generated phoneCn(Map<String, String> p) {
        boolean strict = !"loose".equals(p.getOrDefault("strict", "strict"));
        String prefixMode = p.getOrDefault("allowPrefix", "none");
        String body = strict ? "1[3-9]\\d{9}" : "\\d{11}";
        StringBuilder pat = new StringBuilder();
        List<String> ex = new ArrayList<>();
        switch (prefixMode) {
            case "required" -> {
                pat.append("(?:\\+?86)");
                ex.add("(?:\\+?86) 必须携带国家码 86（+ 号可省略）");
            }
            case "optional" -> {
                pat.append("(?:\\+?86)?");
                ex.add("(?:\\+?86)? 可选的国家码 +86 / 86");
            }
            default -> {
                // 不带国家码
            }
        }
        pat.append(body);
        if (strict) {
            ex.add("1[3-9] 第一位固定为 1，第二位限定 3-9（现有号段范围）");
            ex.add("\\d{9} 剩余 9 位数字");
        } else {
            ex.add("\\d{11} 任意 11 位数字（不校验号段）");
        }
        List<String> samples = new ArrayList<>();
        samples.add("required".equals(prefixMode) ? "+8613812345678" : "13812345678");
        return new Generated(pat.toString(), ex, samples,
            List.of("宽松模式只校验位数，不校验运营商号段。"));
    }

    private Generated email(Map<String, String> p) {
        boolean allowPlus = isOn(p, "allowPlus");
        String domainMode = p.getOrDefault("domainMode", "any");
        String local = allowPlus ? "[A-Za-z0-9._%+-]+" : "[A-Za-z0-9._%-]+";
        StringBuilder pat = new StringBuilder(local).append('@');
        List<String> ex = new ArrayList<>();
        ex.add(local + " 账号部分" + (allowPlus ? "（含 + 号别名）" : ""));
        ex.add("@ 账号与域名的分隔符");
        List<String> notes = new ArrayList<>();
        switch (domainMode) {
            case "common" -> {
                pat.append("(?:gmail|qq|163|126|outlook|hotmail|yahoo|sina|sohu|foxmail)\\.com");
                ex.add("域名限定为常见邮箱服务商的 .com 域");
            }
            case "custom" -> {
                String custom = p.getOrDefault("customDomain", "").trim();
                if (custom.isEmpty()) {
                    throw new ServiceException(ErrorCode.TOOLS_REGEX_PARAM_INVALID, "自定义后缀不能为空");
                }
                String quoted = Pattern.quote(custom);
                pat.append(quoted);
                ex.add(quoted + " 固定域名（按字面量匹配）");
            }
            default -> {
                pat.append("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
                ex.add("[A-Za-z0-9.-]+\\.[A-Za-z]{2,} 任意域名（顶级域至少 2 个字母）");
            }
        }
        notes.add("建议搭配 i 标志使用，忽略大小写。");
        return new Generated(pat.toString(), ex, List.of("someone@example.com"), notes);
    }

    private Generated idCard(Map<String, String> p) {
        boolean lowerX = isOn(p, "allowLowerX");
        String tail = lowerX ? "[\\dXx]" : "[\\dX]";
        String pat = "[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}" + tail;
        List<String> ex = List.of(
            "[1-9]\\d{5} 6 位地址码（首位非 0）",
            "(?:18|19|20)\\d{2} 4 位年份（1800~2099 范围内常见写法）",
            "(?:0[1-9]|1[0-2]) 月份 01-12",
            "(?:0[1-9]|[12]\\d|3[01]) 日 01-31",
            "\\d{3} 3 位顺序码",
            tail + " 末位校验码" + (lowerX ? "（含小写 x）" : "（数字或大写 X）"));
        return new Generated(pat, ex, List.of("11010519491231002X"),
            List.of("仅校验格式：校验位算法、地区码与日期真实性需在业务代码里另行验证。"));
    }

    private Generated ipv4(Map<String, String> p) {
        boolean strict = !"loose".equals(p.getOrDefault("strict", "strict"));
        boolean allowPort = isOn(p, "allowPort");
        String octet = strict ? "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)" : "\\d{1,3}";
        String pat = "(?:" + octet + "\\.){3}" + octet + (allowPort ? "(?::\\d{1,5})?" : "");
        List<String> ex = new ArrayList<>();
        if (strict) {
            ex.add("(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d) 单段 0-255（不允许 256+ 与非法前导写法）");
        } else {
            ex.add("\\d{1,3} 单段 1-3 位数字（不校验 255 上限）");
        }
        ex.add("(?:…\\.){3}… 点号分隔的四段结构");
        if (allowPort) {
            ex.add("(?::\\d{1,5})? 可选的 :端口 后缀");
        }
        List<String> samples = new ArrayList<>();
        samples.add("192.168.1.1");
        if (allowPort) {
            samples.add("10.0.0.1:8080");
        }
        List<String> notes = new ArrayList<>();
        if (!strict) {
            notes.add("宽松模式允许 999.999.999.999 这类越界值。");
        }
        return new Generated(pat, ex, samples, notes);
    }

    private Generated url(Map<String, String> p) {
        String scheme = p.getOrDefault("scheme", "http");
        boolean allowPath = isOn(p, "allowPath");
        String schemePart = switch (scheme) {
            case "https" -> "https";
            case "any" -> "[a-zA-Z][a-zA-Z0-9+.-]*";
            default -> "https?";
        };
        String host = "(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?::\\d{1,5})?";
        String pat = schemePart + "://" + (allowPath ? "[^\\s]+" : host);
        List<String> ex = new ArrayList<>();
        ex.add(schemePart + " 协议部分");
        ex.add(":// 协议与主机的分隔符");
        if (allowPath) {
            ex.add("[^\\s]+ 主机与路径（遇到空白即停）");
        } else {
            ex.add("(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,} 点分域名 + 可选端口");
        }
        List<String> samples = List.of(allowPath ? "https://example.com/path?query=1" : "https://example.com");
        return new Generated(pat, ex, samples,
            List.of("主机按「点分域名标签」近似匹配，不覆盖 IP 直连与 localhost；更宽的写法请用自定义字符规则场景。"));
    }

    private Generated date(Map<String, String> p) {
        String sep = p.getOrDefault("sep", "-");
        boolean pad = !"loose".equals(p.getOrDefault("zeroPad", "strict"));
        boolean withTime = isOn(p, "withTime");
        String quoted = Pattern.quote(sep);
        String month = pad ? "(?:0[1-9]|1[0-2])" : "(?:0?[1-9]|1[0-2])";
        String day = pad ? "(?:0[1-9]|[12]\\d|3[01])" : "(?:0?[1-9]|[12]\\d|3[01])";
        StringBuilder pat = new StringBuilder("\\d{4}").append(quoted).append(month)
            .append(quoted).append(day);
        List<String> ex = new ArrayList<>();
        ex.add("\\d{4} 4 位年份");
        ex.add(quoted + " 日期分隔符");
        ex.add(month + " 月份" + (pad ? "（必须 0 填充）" : "（允许单数字）"));
        ex.add(day + " 日" + (pad ? "（必须 0 填充）" : "（允许单数字）"));
        String sampleDate = pad ? ("2026" + sep + "09" + sep + "24") : ("2026" + sep + "9" + sep + "4");
        List<String> samples = new ArrayList<>();
        samples.add(sampleDate);
        if (withTime) {
            pat.append("(?:\\s+(?:[01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?)?");
            ex.add("(?:\\s+HH:mm(:ss)?)? 可选的时间部分（24 小时制）");
            samples.add(sampleDate + " 13:45:30");
        }
        return new Generated(pat.toString(), ex, samples,
            List.of("不校验日期真实存在性（例如 2 月 30 日也能通过）。"));
    }

    private Generated password(Map<String, String> p) {
        int minLen = getInt(p, "minLen", 8);
        if (minLen < 6 || minLen > 64) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_PARAM_INVALID, "密码最小长度需在 6 ~ 64 之间");
        }
        boolean upper = isOn(p, "needUpper");
        boolean lower = isOn(p, "needLower");
        boolean digit = isOn(p, "needDigit");
        boolean special = isOn(p, "needSpecial");
        String specialChars = p.getOrDefault("specialChars", "!@#$%^&*");
        if (special && specialChars.isBlank()) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_PARAM_INVALID, "特殊字符集合不能为空");
        }
        StringBuilder pat = new StringBuilder("^");
        List<String> ex = new ArrayList<>();
        if (upper) {
            pat.append("(?=.*[A-Z])");
            ex.add("(?=.*[A-Z]) 正向环视：串中必须存在大写字母");
        }
        if (lower) {
            pat.append("(?=.*[a-z])");
            ex.add("(?=.*[a-z]) 正向环视：串中必须存在小写字母");
        }
        if (digit) {
            pat.append("(?=.*\\d)");
            ex.add("(?=.*\\d) 正向环视：串中必须存在数字");
        }
        if (special) {
            String clazz = "[" + Pattern.quote(specialChars) + "]";
            pat.append("(?=.*").append(clazz).append(")");
            ex.add("(?=" + clazz + ") 正向环视：串中必须存在特殊字符");
        }
        pat.append(".{").append(minLen).append(",}$");
        ex.add(".{" + minLen + ",} 主体至少 " + minLen + " 个任意字符");
        ex.add("^ $ 锚定整串（密码必须整体满足）");
        // 构造一条必然满足各环视的示例
        StringBuilder sample = new StringBuilder("Abcdef12");
        if (special) {
            sample.append('!');
        }
        while (sample.length() < minLen) {
            sample.append('x');
        }
        return new Generated(pat.toString(), ex, List.of(sample.toString()),
            List.of("只约束最小长度下限与字符种类，不含长度上限；如需上限可把 {n,} 改成 {n,m}。"));
    }

    private Generated charRule(Map<String, String> p) {
        String charset = p.getOrDefault("charset", "A-Za-z0-9").trim();
        if (charset.isEmpty()) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_PARAM_INVALID, "字符集不能为空");
        }
        int minLen = getInt(p, "minLen", 6);
        int maxLen = getInt(p, "maxLen", 0);
        if (minLen < 1 || minLen > 1000) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_PARAM_INVALID, "最小长度需在 1 ~ 1000 之间");
        }
        if (maxLen < 0 || maxLen > 1000) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_PARAM_INVALID, "最大长度需在 0 ~ 1000 之间（0 表示不设上限）");
        }
        if (maxLen > 0 && maxLen < minLen) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_PARAM_INVALID, "最大长度不能小于最小长度");
        }
        String quant = maxLen > 0 ? ("{" + minLen + "," + maxLen + "}") : ("{" + minLen + ",}");
        String prefix = p.getOrDefault("literalPrefix", "");
        String suffix = p.getOrDefault("literalSuffix", "");
        StringBuilder pat = new StringBuilder();
        List<String> ex = new ArrayList<>();
        if (!prefix.isEmpty()) {
            pat.append(Pattern.quote(prefix));
            ex.add(Pattern.quote(prefix) + " 前置字面量（按原文匹配）");
        }
        pat.append('[').append(charset).append(']').append(quant);
        ex.add("[" + charset + "] 允许的字符集合");
        ex.add(quant + (maxLen > 0 ? " 重复 " + minLen + " 到 " + maxLen + " 次" : " 至少重复 " + minLen + " 次"));
        if (!suffix.isEmpty()) {
            pat.append(Pattern.quote(suffix));
            ex.add(Pattern.quote(suffix) + " 后置字面量（按原文匹配）");
        }
        return new Generated(pat.toString(), ex, List.of(),
            List.of("字符集直接写入字符类：注意 ]、\\、^、- 在类内的特殊含义；生成的示例请按字符集自行构造。"));
    }

    // ========== 参数小工具 ==========

    private static boolean isOn(Map<String, String> params, String key) {
        String v = params.get(key);
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static int getInt(Map<String, String> params, String key, int def) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(ErrorCode.TOOLS_REGEX_PARAM_INVALID, "参数 " + key + " 不是合法数字");
        }
    }
}
