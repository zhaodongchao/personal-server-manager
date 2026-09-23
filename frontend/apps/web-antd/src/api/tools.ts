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
