import { createVNode } from 'vue';

import { Input, message, Modal } from 'ant-design-vue';

/**
 * 二级认证（step-up）交互。
 *
 * 背景：服务端对 {@code @Audit(safe = true)} 的高危接口，会在业务逻辑之前调用
 * Sa-Token 的 StpUtil.checkSafe()，未在安全窗口内时返回业务码 1010
 * （AUTH_SAFE_REQUIRED）。request.ts 捕获 1010 后调用本模块弹出密码框，
 * 认证成功后自动重放原请求。
 *
 * 为什么由调用方把客户端实例传进来，而不是本模块直接 import api：
 * request.ts → safe-auth.ts → api/request.ts 会形成静态循环依赖，
 * 传实例可以彻底避开这个环。
 */

/** 本模块需要的最小客户端能力（RequestClient 满足） */
interface SafeClient {
  post: (url: string, data?: any) => Promise<any>;
}

let pending: null | Promise<boolean> = null;

function promptOnce(client: SafeClient): Promise<boolean> {
  return new Promise((resolve) => {
    let password = '';
    Modal.confirm({
      cancelText: '取消',
      content: () =>
        createVNode('div', null, [
          createVNode(
            'p',
            { style: 'margin-bottom: 8px' },
            '该操作属于高危操作，请输入当前账号密码完成二级认证。',
          ),
          createVNode(Input.Password, {
            autofocus: true,
            onChange: (e: any) => {
              password = e.target.value;
            },
            placeholder: '当前账号密码',
            size: 'large',
          }),
        ]),
      okText: '认证并继续',
      onCancel() {
        resolve(false);
      },
      async onOk() {
        if (!password) {
          message.warning('请输入密码');
          // reject 可阻止弹窗关闭，让用户就地重试
          return Promise.reject(new Error('empty-password'));
        }
        try {
          await client.post('/auth/safe', { password });
          message.success('二级认证已通过');
          resolve(true);
        } catch {
          // 失败原因（密码错误 / 账号锁定）已由请求层统一提示，这里只保证弹窗不关闭
          return Promise.reject(new Error('safe-auth-failed'));
        }
      },
      title: '需要二级认证',
    });
  });
}

/**
 * 确保当前会话已通过二级认证。
 *
 * <p>并发去重：多个高危请求同时被拒时只弹一个框，共用同一次认证结果。
 *
 * @param client 请求客户端实例（由 request.ts 传入）
 * @returns 是否已通过（用户取消时为 false）
 */
export function ensureSafe(client: SafeClient): Promise<boolean> {
  pending ??= promptOnce(client).finally(() => {
    pending = null;
  });
  return pending;
}
