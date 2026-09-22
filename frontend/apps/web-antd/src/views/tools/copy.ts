import { message } from 'ant-design-vue';

/**
 * 复制文本到剪贴板。
 *
 * 面板常通过 http（非安全上下文）访问，此时 navigator.clipboard 不可用，
 * 故保留 execCommand 兜底，否则「复制」按钮在生产环境会静默失效。
 *
 * @param text 待复制内容
 */
export async function copyText(text: string) {
  if (!text) {
    message.warning('没有可复制的内容');
    return;
  }
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      message.success('已复制');
      return;
    }
    throw new Error('clipboard unavailable');
  } catch {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.append(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    if (ok) {
      message.success('已复制');
    } else {
      message.error('复制失败，请手动选中后复制');
    }
  }
}
