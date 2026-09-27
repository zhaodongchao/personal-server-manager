import { baseRequestClient, requestClient } from '#/api/request';

export namespace AuthApi {
  /** 登录接口参数 */
  export interface LoginParams {
    password?: string;
    username?: string;
  }

  /** 登录接口返回值 */
  export interface LoginResult {
    accessToken: string;
  }

  export interface RefreshTokenResult {
    data: string;
    status: number;
  }

  /** 邮箱验证码功能可用性（SMTP 是否已在后端配置） */
  export interface MailEnabledResult {
    enabled: boolean;
  }

  /** 发送邮箱验证码参数 */
  export interface MailCodeParams {
    email: string;
    /** login=登录（要求邮箱已绑定面板账号）；register=注册（要求邮箱未被占用） */
    purpose: 'login' | 'register';
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
 * 邮箱验证码功能是否可用（SMTP 已配置）。
 *
 * 免登录接口：登录页据此显隐「邮箱验证码登录」与「创建账号」入口 ——
 * 沿用第三方登录「按配置启用」的口径，后端没配 SMTP 时入口直接不出现。
 */
export async function getMailEnabledApi() {
  return requestClient.get<AuthApi.MailEnabledResult>('/auth/mail/enabled');
}

/**
 * 发送邮箱验证码。
 *
 * 场景前置校验在后端（login 要求邮箱已绑定账号、register 要求注册开关开启且邮箱未占用），
 * 冷却 / 日限 / 一次性消费同样由后端 MailCodeService 承担，前端不做任何计数。
 */
export async function sendMailCodeApi(data: AuthApi.MailCodeParams) {
  return requestClient.post('/auth/mail/code', data);
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
