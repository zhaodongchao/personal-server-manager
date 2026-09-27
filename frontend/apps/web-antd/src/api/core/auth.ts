import { baseRequestClient, requestClient } from '#/api/request';

export namespace AuthApi {
  /** 登录接口参数 */
  export interface LoginParams {
    password?: string;
    username?: string;
    /** 人机校验登录令牌（点选校验通过后下发，一次性），缺失后端返回 1037 */
    captcha?: string;
  }

  /** 登录接口返回值 */
  export interface LoginResult {
    accessToken: string;
  }

  export interface RefreshTokenResult {
    data: string;
    status: number;
  }

  /** 邮箱认证可用性：SMTP 是否配置 + 自助注册是否开放 + 允许注册的邮箱域名 */
  export interface MailEnabledResult {
    /** SMTP 与发件人均已配置 —— 决定「邮箱验证码登录」入口 */
    enabled: boolean;
    /** serverpanel.register.enabled —— 与 enabled 共同决定「创建账号」入口 */
    registerEnabled: boolean;
    /** 允许注册的邮箱域名白名单，空数组表示不限制 */
    allowedEmailDomains: string[];
  }

  /** 发送邮箱验证码参数 */
  export interface MailCodeParams {
    email: string;
    /** login=登录（要求邮箱已绑定面板账号）；register=注册（要求邮箱未被占用） */
    purpose: 'login' | 'register';
    /** 人机校验通过后下发的发信令牌（一次性） */
    captcha: string;
  }

  /** 点选验证码挑战（图片 + 需按序点击的目标字符；答案坐标不在响应中） */
  export interface ClickCaptchaResult {
    captchaToken: string;
    /** 验证码图片（data:image/png;base64,...） */
    image: string;
    /** 需按顺序点击的目标字符 */
    prompt: string[];
    width: number;
    height: number;
  }

  /** 一次点击的相对坐标（0~1，相对图片宽高，与显示尺寸无关） */
  export interface CaptchaClickPoint {
    x: number;
    y: number;
  }

  /** 人机校验响应（按 purpose 返回对应令牌，二选一） */
  export interface CaptchaVerifyResult {
    /** purpose=send：发邮件验证码令牌 */
    sendToken?: string;
    /** purpose=login：密码登录令牌 */
    loginToken?: string;
  }

  /** 用户名可用性查询结果 */
  export interface CheckUsernameResult {
    available: boolean;
  }

  /** 邮箱验证码登录参数 */
  export interface MailLoginParams {
    code: string;
    email: string;
  }

  /** 邮箱验证码登录返回值 */
  export interface MailLoginResult {
    accessToken: string;
  }

  /** 自助注册参数（邮箱验证码方式） */
  export interface RegisterParams {
    code: string;
    email: string;
    password: string;
    username: string;
  }
}

/**
 * 登录
 */
export async function loginApi(data: AuthApi.LoginParams) {
  return requestClient.post<AuthApi.LoginResult>('/auth/login', data);
}

/**
 * 二级认证（step-up）：校验当前密码，通过后开启安全窗口。
 *
 * 用于 @Audit(safe = true) 的高危接口：服务端返回业务码 1010 时，
 * 由 request.ts 调用本接口完成认证并自动重放原请求。
 *
 * 用 requestClient 而不是 baseRequestClient：本项目的 token 只走 Authorization 头
 * （sa-token.is-read-cookie=false），baseRequestClient 不带该头会直接 401。
 */
export async function safeApi(password: string) {
  return requestClient.post<{ timeout: number }>('/auth/safe', { password });
}

/**
 * 邮箱认证能力是否可用。
 *
 * 免登录接口，一次返回两个独立开关：
 * - enabled：SMTP 与发件人是否就绪 → 决定「邮箱验证码登录」入口；
 * - registerEnabled：serverpanel.register.enabled → 与 enabled 共同决定「创建账号」入口。
 *
 * 沿用第三方登录「按配置启用」的口径，后端没配 SMTP 时入口直接不出现。
 */
export async function getMailEnabledApi() {
  return requestClient.get<AuthApi.MailEnabledResult>('/auth/mail/enabled');
}

/**
 * 发送邮箱验证码。
 *
 * 必须先通过点选人机校验拿到发信令牌（captcha）再调用本接口，否则后端返回 1037/1038；
 * 场景前置校验在后端（login 要求邮箱已绑定账号、register 要求注册开关开启且邮箱未占用），
 * 冷却 / 日限 / 一次性消费同样由后端 MailCodeService 承担，前端不做任何计数。
 */
export async function sendMailCodeApi(data: AuthApi.MailCodeParams) {
  return requestClient.post('/auth/mail/code', data);
}

/** 领取点选人机校验挑战（图片 + 目标字符；答案坐标仅存服务端） */
export async function getClickCaptchaApi() {
  return requestClient.post<AuthApi.ClickCaptchaResult>('/auth/captcha/click');
}

/**
 * 上报点击坐标换取一次性令牌。
 *
 * clicks 为按提示顺序点击的相对坐标（0~1）；
 * purpose=send 返回 sendToken（供 /mail/code），purpose=login 返回 loginToken（供 /auth/login）。
 * 坐标错误 / 顺序不符 / 挑战失效均由后端返回 1038。
 */
export async function verifyClickCaptchaApi(
  captchaToken: string,
  clicks: AuthApi.CaptchaClickPoint[],
  purpose: 'send' | 'login' = 'send',
) {
  return requestClient.post<AuthApi.CaptchaVerifyResult>(
    '/auth/captcha/click/verify',
    { captchaToken, clicks, purpose },
  );
}

/** 注册用户名实时查重 */
export async function checkUsernameApi(username: string) {
  return requestClient.get<AuthApi.CheckUsernameResult>(
    '/auth/register/check-username',
    { params: { username } },
  );
}

/** 邮箱验证码登录（返回 accessToken） */
export async function mailLoginApi(data: AuthApi.MailLoginParams) {
  return requestClient.post<AuthApi.MailLoginResult>('/auth/mail/login', data);
}

/**
 * 自助注册（邮箱验证码方式）。
 *
 * 注册成功后账号不分配任何角色（仅个人中心可见），业务权限由管理员分配；
 * 注册总开关由后端 serverpanel.register.enabled 控制。
 */
export async function registerApi(data: AuthApi.RegisterParams) {
  return requestClient.post('/auth/register', data);
}

/**
 * 刷新accessToken
 */
export async function refreshTokenApi() {
  return baseRequestClient.post<AuthApi.RefreshTokenResult>(
    '/auth/refresh',
    undefined,
    {
      withCredentials: true,
    },
  );
}

/**
 * 退出登录
 */
export async function logoutApi() {
  return baseRequestClient.post('/auth/logout', undefined, {
    withCredentials: true,
  });
}
