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
    /** text / number / textarea */
    type: string;
    required: boolean;
    def?: null | string;
    help?: null | string;
    min?: null | number;
    max?: null | number;
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
