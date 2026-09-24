import { requestClient } from '#/api/request';

export namespace ToolsApi {
  /** 下拉项 */
  export interface Option {
    value: string;
    label: string;
  }

  /** 表单字段定义（由服务端下发，界面按此渲染） */
  export interface Field {
    name: string;
    label: string;
    /** text / number / textarea / select / switch / datetime */
    type: string;
    required: boolean;
    def?: null | string;
    help?: null | string;
    min?: null | number;
    max?: null | number;
    /** type=select 时的候选项 */
    options?: Option[];
  }

  /** 对称算法形态 */
  export interface CipherOption {
    value: string;
    label: string;
    /** 允许的密钥字节长度；空数组表示任意长度 */
    keyLengths: number[];
    /** 支持的分组模式；空数组表示流算法 */
    modes: string[];
    /** CBC 所需 IV 字节长度；0 表示不需要 */
    ivLength: number;
    note: string;
  }

  /** 字符串加解密页可选清单 */
  export interface CryptoOptions {
    digests: Option[];
    hmacs: Option[];
    encodings: Option[];
    ciphers: CipherOption[];
    keyEncodings: Option[];
    cipherEncodings: Option[];
    /** 单次处理文本上限（字符数） */
    maxChars: number;
  }

  /** 混淆方式 */
  export interface ObfuscateMethod {
    value: string;
    label: string;
    desc: string;
    /** 是否对称（正反同一套逻辑） */
    symmetric: boolean;
    fields: Field[];
  }

  /** 混淆页可选清单 */
  export interface ObfuscateOptions {
    methods: ObfuscateMethod[];
    maxChars: number;
  }

  // ---------------- 请求体 ----------------

  export interface DigestBody {
    text: string;
    algorithm: string;
    upper: boolean;
  }

  export interface HmacBody {
    text: string;
    key: string;
    keyEncoding: string;
    algorithm: string;
    upper: boolean;
  }

  export interface CodecBody {
    text: string;
    algorithm: string;
    /** ENCODE / DECODE */
    op: string;
  }

  export interface SymmetricBody {
    text: string;
    algorithm: string;
    key: string;
    keyEncoding: string;
    iv?: string;
    ivEncoding: string;
    mode: string;
    padding: string;
    /** ENCRYPT / DECRYPT */
    op: string;
    inputEncoding: string;
    outputEncoding: string;
  }

  export interface ObfuscateBody {
    text: string;
    method: string;
    /** OBFUSCATE / DEOBFUSCATE */
    op: string;
    params: Record<string, string>;
  }
}

/** 字符串加解密可选清单（算法名以服务端为唯一真源） */
export function getCryptoOptionsApi() {
  return requestClient.get<ToolsApi.CryptoOptions>('/tools/crypto/options');
}

/** 摘要：MD5 / SHA-1 / SHA-256 / SHA-512 */
export function digestApi(data: ToolsApi.DigestBody) {
  return requestClient.post<string>('/tools/crypto/digest', data);
}

/** HMAC */
export function hmacApi(data: ToolsApi.HmacBody) {
  return requestClient.post<string>('/tools/crypto/hmac', data);
}

/** Base64 / Hex 编解码 */
export function codecApi(data: ToolsApi.CodecBody) {
  return requestClient.post<string>('/tools/crypto/codec', data);
}

/** 对称加解密：AES / DES / 3DES / RC4 */
export function symmetricApi(data: ToolsApi.SymmetricBody) {
  return requestClient.post<string>('/tools/crypto/symmetric', data);
}

/** 混淆方式可选清单 */
export function getObfuscateOptionsApi() {
  return requestClient.get<ToolsApi.ObfuscateOptions>('/tools/obfuscate/options');
}

/** 混淆 / 反混淆 */
export function obfuscateApi(data: ToolsApi.ObfuscateBody) {
  return requestClient.post<string>('/tools/obfuscate/transform', data);
}

// ---------------- 二维码工具 ----------------

export namespace ToolsApi {
  /** 二维码内容类型（文本 / 网址 / WiFi / 名片 ……） */
  export interface QrcodeType {
    value: string;
    label: string;
    desc: string;
    fields: Field[];
    /**
     * 该类型的限制说明。
     *
     * 小程序码（菊花朵码）与公众号带场景值二维码必须经微信服务端 API 生成，
     * 这里如实说明，避免用户误以为本工具能产出官方码。
     */
    notice?: null | string;
  }

  export interface QrcodeOptions {
    types: QrcodeType[];
    eccLevels: Option[];
    dotStyles: Option[];
    formats: Option[];
    defaultSize: number;
    minSize: number;
    maxSize: number;
    defaultMargin: number;
    maxContentChars: number;
    maxLogoBytes: number;
    maxImageBytes: number;
    /** Logo 占边长比例上限，超过会让码扫不出来 */
    maxLogoScale: number;
  }

  export interface QrcodeGenerateBody {
    contentType: string;
    /** 按类型而定的参数，字段定义见 options.types[].fields */
    params?: Record<string, any>;
    size?: number;
    margin?: number;
    /** L / M / Q / H */
    ecc?: string;
    fgColor?: string;
    bgColor?: string;
    gradientColor?: string;
    /** SQUARE / DOT / ROUNDED */
    dotStyle?: string;
    logoBase64?: string;
    logoScale?: number;
    logoRound?: boolean;
    /** PNG / JPEG */
    format?: string;
  }

  export interface QrcodeGenerateResult {
    /** 实际编码进码里的文本，便于核对拼装结果 */
    content: string;
    dataUrl: string;
    format: string;
    requestSize: number;
    /** 吸附到模块整数倍后的真实边长 */
    realSize: number;
    scale: number;
    moduleCount: number;
    bytes: number;
  }

  export interface QrcodeDecodeBody {
    imageBase64: string;
  }

  export interface QrcodeDecodeResult {
    found: boolean;
    text: string;
    reason: string;
    format: string;
  }
}

// ---------------- JWT 工具 ----------------

export namespace ToolsApi {
  /** JWT 算法描述（由服务端下发，前端不硬编码算法名） */
  export interface JwtAlgorithm {
    value: string;
    label: string;
    /** HMAC / RSA / RSA-PSS / ECDSA / EdDSA */
    family: string;
    /** 所需密钥类型：HMAC / RSA / EC / OKP */
    keyType: string;
    signable: boolean;
    note: string;
  }

  /** JWT 页可选清单 */
  export interface JwtOptions {
    algorithms: JwtAlgorithm[];
    keyFormats: Option[];
    secretEncodings: Option[];
    maxChars: number;
  }

  export interface JwtVerifyBody {
    token: string;
    /** SECRET / PEM / JWK */
    keyFormat: string;
    key: string;
    /** TEXT / BASE64 / HEX（仅对称密钥使用） */
    secretEncoding: string;
  }

  export interface JwtVerifyResult {
    verified: boolean;
    algorithm: string;
    family: string;
    keyType: null | string;
    kid: null | string;
    reason: string;
  }

  export interface JwtSignBody {
    algorithm: string;
    /** Payload JSON 对象原文 */
    payload: string;
    /** Header 附加字段（alg 不可覆盖） */
    header?: string;
    keyFormat: string;
    key: string;
    secretEncoding: string;
  }

  export interface JwtSignResult {
    token: string;
    header: string;
    algorithm: string;
  }

  /** ID 位段定义（用于画 64bit 分段条） */
  export interface IdSegment {
    name: string;
    width: number;
    /** SIGN / TIME / MACHINE / SEQ / RANDOM / VERSION / VARIANT / COUNTER */
    role: string;
    note?: null | string;
  }

  /** ID 方案参数定义（由服务端下发，界面按此渲染表单） */
  export interface IdParam {
    name: string;
    label: string;
    /** number / text / switch / select */
    type: string;
    required: boolean;
    def?: null | string;
    help?: null | string;
    min?: null | number;
    max?: null | number;
    options?: null | Option[];
  }

  /** 一种 ID 生成方案 */
  export interface IdScheme {
    value: string;
    label: string;
    group: string;
    groupLabel: string;
    bits: string;
    totalBits: number;
    /** NUMBER / UUID / HEX */
    shape: string;
    ordered: string;
    generator: string;
    note: string;
    pros: string[];
    cons: string[];
    segments: IdSegment[];
    params: IdParam[];
    sample: string;
    timeBased: boolean;
    distributed: boolean;
  }

  /** ID 生成器可选清单 */
  export interface IdOptions {
    schemes: IdScheme[];
    groups: Option[];
    maxCount: number;
    segLimit: number;
  }

  /** ID 生成请求体 */
  export interface IdGenerateBody {
    scheme: string;
    count: number;
    params: Record<string, string>;
  }

  /** 单条生成结果 */
  export interface IdItem {
    index: number;
    value: string;
    hex?: null | string;
    time?: null | string;
    extra?: null | string;
    segValues?: null | string[];
  }

  /** ID 生成结果 */
  export interface IdGenerateResult {
    scheme: string;
    label: string;
    group: string;
    groupLabel: string;
    bits: string;
    totalBits: number;
    shape: string;
    ordered: string;
    generator: string;
    count: number;
    ids: IdItem[];
    segments: IdSegment[];
    segValuesIncluded: boolean;
    segLimit: number;
    notes: string[];
    warnings: string[];
    elapsedMs: number;
  }

  /** ID 反解请求体 */
  export interface IdDecodeBody {
    scheme: string;
    value: string;
    /** 时间基准（毫秒），雪花类反解需要 */
    epoch?: null | number;
  }

  /** ID 反解结果 */
  export interface IdDecodeResult {
    scheme: string;
    label: string;
    value: string;
    hex?: null | string;
    time?: null | string;
    segments: IdSegment[];
    segValues?: null | string[];
    facts: string[];
    valid: boolean;
    reason: string;
  }
}

/** JWT 工具可选清单 */
export function getJwtOptionsApi() {
  return requestClient.get<ToolsApi.JwtOptions>('/tools/jwt/options');
}

/** 验证 JWT 签名（密钥只发到后端，不落审计表） */
export function verifyJwtApi(data: ToolsApi.JwtVerifyBody) {
  return requestClient.post<ToolsApi.JwtVerifyResult>('/tools/jwt/verify', data);
}

/** 签发 JWT（产出可直接使用的凭据，审计按 risky 标记） */
export function signJwtApi(data: ToolsApi.JwtSignBody) {
  return requestClient.post<ToolsApi.JwtSignResult>('/tools/jwt/sign', data);
}

/** ID 生成器方案清单（含位分配、参数定义、优缺点） */
export function getIdOptionsApi() {
  return requestClient.get<ToolsApi.IdOptions>('/tools/id/options');
}

/** 按方案生成一批 ID（入参不含凭据，审计保留入参记录） */
export function generateIdsApi(data: ToolsApi.IdGenerateBody) {
  return requestClient.post<ToolsApi.IdGenerateResult>('/tools/id/generate', data);
}

/** 反解一个已有 ID：拆位段、还原生成时间 */
export function decodeIdApi(data: ToolsApi.IdDecodeBody) {
  return requestClient.post<ToolsApi.IdDecodeResult>('/tools/id/decode', data);
}

/** 二维码工具清单（内容类型、容错等级、样式与各项上限） */
export function getQrcodeOptionsApi() {
  return requestClient.get<ToolsApi.QrcodeOptions>('/tools/qrcode/options');
}

/** 生成二维码（含尺寸/配色/码点样式/Logo 控制） */
export function generateQrcodeApi(data: ToolsApi.QrcodeGenerateBody) {
  return requestClient.post<ToolsApi.QrcodeGenerateResult>(
    '/tools/qrcode/generate',
    data,
  );
}

/** 识别图片中的二维码（识别不出不算错误，返回 found=false + 中文原因） */
export function decodeQrcodeApi(data: ToolsApi.QrcodeDecodeBody) {
  return requestClient.post<ToolsApi.QrcodeDecodeResult>(
    '/tools/qrcode/decode',
    data,
  );
}

// ---------------- 正则工具 ----------------

export namespace ToolsApi {
  /** 正则生成场景（schema 驱动，同二维码内容类型模式） */
  export interface RegexScenario {
    value: string;
    label: string;
    desc: string;
    fields: Field[];
  }

  /** 正则标志（i / m / s / x / u） */
  export interface RegexFlag {
    value: string;
    label: string;
    desc: string;
  }

  /** 正则工具各项处理上限 */
  export interface RegexOptions {
    scenarios: RegexScenario[];
    flags: RegexFlag[];
    maxPatternLength: number;
    maxTestTextLength: number;
    maxMatches: number;
    maxTemplateCount: number;
  }

  export interface RegexGenerateBody {
    scenario: string;
    /** 按场景而定的参数，字段定义见 options.scenarios[].fields */
    params: Record<string, string>;
    flags?: string;
  }

  export interface RegexGenerateResult {
    pattern: string;
    flags: string;
    /** 逐段中文说明（每行对应模式的一个组成段） */
    explanation: string[];
    /** 能匹配该模式的示例文本（可直接「填入测试」验证） */
    samples: string[];
    notes: string[];
  }

  export interface RegexTestBody {
    pattern: string;
    flags?: string;
    text: string;
    /** 非空时返回替换预览，支持 $1 / ${name} 组引用 */
    replacement?: null | string;
  }

  /** 捕获组取值 */
  export interface RegexGroup {
    index: number;
    /** 命名组的名称（非命名组为 null） */
    name?: null | string;
    /** 组未参与匹配时为 null */
    value?: null | string;
  }

  export interface RegexMatch {
    index: number;
    end: number;
    text: string;
    /** 第 0 项为整体匹配 */
    groups: RegexGroup[];
  }

  /** 结构解析出的一个片段 */
  export interface RegexToken {
    token: string;
    /** literal / charClass / quantifier / group / groupEnd / anchor / alternation / dot */
    type: string;
    /** 嵌套深度（分组内 +1），用于缩进展示 */
    depth: number;
    desc: string;
  }

  export interface RegexTestResult {
    valid: boolean;
    errorMessage?: null | string;
    flagsApplied: string;
    matchCount: number;
    /** 是否因超过单次返回上限被截断 */
    truncated: boolean;
    matches: RegexMatch[];
    structure: RegexToken[];
    replacementPreview?: null | string;
  }

  /** 正则模板（全局共享） */
  export interface RegexTemplate {
    id: string;
    name: string;
    pattern: string;
    flags: string;
    category: string;
    description?: null | string;
    sample?: null | string;
    sort: number;
    createdAt?: null | string;
    updatedAt?: null | string;
  }

  export interface RegexTemplateBody {
    name: string;
    pattern: string;
    flags?: string;
    category?: string;
    description?: string;
    sample?: string;
    sort?: number;
  }
}

/** 正则工具可选清单（场景、标志与各项上限） */
export function getRegexOptionsApi() {
  return requestClient.get<ToolsApi.RegexOptions>('/tools/regex/options');
}

/** 按场景 + 参数生成正则（含逐段说明与示例） */
export function generateRegexApi(data: ToolsApi.RegexGenerateBody) {
  return requestClient.post<ToolsApi.RegexGenerateResult>(
    '/tools/regex/generate',
    data,
  );
}

/** 测试/解析正则（语法错误不算接口错误，返回 valid=false + 中文原因） */
export function testRegexApi(data: ToolsApi.RegexTestBody) {
  return requestClient.post<ToolsApi.RegexTestResult>('/tools/regex/test', data);
}

/** 正则模板分页（管理 Tab 用） */
export function getRegexTemplatePageApi(params: {
  category?: string;
  keyword?: string;
  pageNum: number;
  pageSize: number;
}) {
  return requestClient.get<{
    records: ToolsApi.RegexTemplate[];
    pageNum: number;
    pageSize: number;
    total: number;
  }>('/tools/regex/template/page', { params });
}

/** 正则模板全量列表（测试 Tab 下拉联动用，按 sort、name 排序） */
export function getRegexTemplateListApi() {
  return requestClient.get<ToolsApi.RegexTemplate[]>(
    '/tools/regex/template/list',
  );
}

/** 新增正则模板 */
export function createRegexTemplateApi(data: ToolsApi.RegexTemplateBody) {
  return requestClient.post<void>('/tools/regex/template', data);
}

/** 编辑正则模板 */
export function updateRegexTemplateApi(
  id: string,
  data: ToolsApi.RegexTemplateBody,
) {
  return requestClient.put<void>(`/tools/regex/template/${id}`, data);
}

/** 删除正则模板 */
export function deleteRegexTemplateApi(id: string) {
  return requestClient.delete<void>(`/tools/regex/template/${id}`);
}

// ===== 图片转换工具 =====

export namespace ToolsApi {
  /** 图片格式描述 */
  export interface ImageFormat {
    format: string;
    ext: string;
    mime: string;
    lossless: boolean;
    qualitySupported: boolean;
    alphaSupported: boolean;
    note: string;
  }

  /** 图片转换各项上限 */
  export interface ImageLimits {
    maxFiles: number;
    maxFileBytes: number;
    maxTotalBytes: number;
    minSide: number;
    maxSide: number;
    minQuality: number;
    maxQuality: number;
    minPercent: number;
    maxPercent: number;
  }

  /** 图片转换 options 响应 */
  export interface ImageConvertOptions {
    formats: ImageFormat[];
    limits: ImageLimits;
    notes: string[];
  }

  /** 单个文件的转换结果（失败项 success=false + error） */
  export interface ImageConvertResult {
    sourceName: string;
    sourceFormat: null | string;
    targetFormat: string;
    outputName: string;
    sizeBefore: number;
    sizeAfter: number;
    width: number;
    height: number;
    resized: boolean;
    dataUrl: string;
    success: boolean;
    error: null | string;
  }
}

/** 图片转换 options：格式清单 + 上限 + 说明 */
export function getImageConvertOptionsApi() {
  return requestClient.get<ToolsApi.ImageConvertOptions>(
    '/tools/image-convert/options',
  );
}

/** 批量图片转换（multipart 表单，文件字段 files） */
export function convertImagesApi(
  files: File[],
  params: {
    targetFormat: string;
    resizeMode?: string;
    percent?: number;
    width?: number;
    height?: number;
    longEdge?: number;
    keepRatio?: boolean;
    quality?: number;
  },
) {
  const form = new FormData();
  files.forEach((file) => form.append('files', file));
  form.append('targetFormat', params.targetFormat);
  form.append('resizeMode', params.resizeMode ?? 'none');
  if (params.percent !== undefined) form.append('percent', String(params.percent));
  if (params.width !== undefined) form.append('width', String(params.width));
  if (params.height !== undefined) form.append('height', String(params.height));
  if (params.longEdge !== undefined) {
    form.append('longEdge', String(params.longEdge));
  }
  form.append('keepRatio', String(params.keepRatio ?? true));
  if (params.quality !== undefined) {
    form.append('quality', String(params.quality));
  }
  return requestClient.post<ToolsApi.ImageConvertResult[]>(
    '/tools/image-convert/convert',
    form,
  );
}
