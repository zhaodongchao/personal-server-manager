#!/usr/bin/env bash
# ============================================================
# psm-hostagent 安装 / 升级脚本（幂等）
#
# 作者: zhaodc          创建时间: 2026-09-21
#
# 说明：本脚本在宿主机上以普通用户执行，通过 sudo -n（NOPASSWD 白名单：
#       /usr/bin/cp、/usr/bin/mkdir、/usr/bin/chmod、/usr/bin/chown、
#       /usr/bin/systemctl）完成安装，**全程无需输入密码**。
#
# 用法：
#   ./install.sh              安装或升级
#   ./install.sh --rotate     强制轮换共享密钥（面板侧需同步重启）
# ============================================================
set -Eeuo pipefail

SUDO=/usr/bin/sudo
LIB_DIR=/usr/local/lib/psm-hostagent
CONF_DIR=/etc/psm-hostagent
SECRET_FILE=${CONF_DIR}/secret
UNIT=/etc/systemd/system/psm-hostagent.service
GROUP=${PSM_HOSTAGENT_GROUP:-zhaodc}

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROTATE=0
[[ "${1:-}" == "--rotate" ]] && ROTATE=1

log() { printf '[hostagent-install] %s\n' "$*"; }
die() { printf '[hostagent-install][错误] %s\n' "$*" >&2; exit 1; }

# ---------- 0. 前置检查 ----------
for f in hostagent.py psm-hostagent.service; do
  [[ -f "${SRC_DIR}/${f}" ]] || die "缺少文件 ${SRC_DIR}/${f}"
done
command -v python3 >/dev/null || die '未找到 python3'
PY_VER="$(python3 -c 'import sys;print("%d.%d"%sys.version_info[:2])')"
log "python3 版本 ${PY_VER}"
python3 -c 'import ipaddress, socket, hmac, json' || die 'python3 标准库不完整'

if ! ${SUDO} -n /usr/bin/mkdir -p "${LIB_DIR}" 2>/dev/null; then
  die 'sudo -n 不可用；请确认当前用户具备 NOPASSWD mkdir/cp/chmod/chown/systemctl 权限'
fi

# ---------- 1. 落盘程序文件 ----------
TMP_DIR="$(mktemp -d)"; trap 'rm -rf "${TMP_DIR}"' EXIT
install -m 0755 "${SRC_DIR}/hostagent.py" "${TMP_DIR}/hostagent.py"
install -m 0644 "${SRC_DIR}/psm-hostagent.service" "${TMP_DIR}/psm-hostagent.service"
${SUDO} -n /usr/bin/cp -f "${TMP_DIR}/hostagent.py" "${LIB_DIR}/hostagent.py"
${SUDO} -n /usr/bin/chmod 0755 "${LIB_DIR}/hostagent.py"
log "已安装 ${LIB_DIR}/hostagent.py"

# ---------- 2. 共享密钥 ----------
${SUDO} -n /usr/bin/mkdir -p "${CONF_DIR}"
if [[ ! -s "${SECRET_FILE}" || "${ROTATE}" == "1" ]]; then
  python3 -c 'import secrets;print(secrets.token_hex(32))' > "${TMP_DIR}/secret"
  ${SUDO} -n /usr/bin/cp -f "${TMP_DIR}/secret" "${SECRET_FILE}"
  ${SUDO} -n /usr/bin/chmod 0400 "${SECRET_FILE}"
  ${SUDO} -n /usr/bin/chown root:root "${SECRET_FILE}"
  log "已生成共享密钥 ${SECRET_FILE}（0400 root:root）"
else
  log "共享密钥已存在，保持不变（如需轮换请加 --rotate）"
fi

# ---------- 3. 安装 systemd 单元 ----------
${SUDO} -n /usr/bin/cp -f "${TMP_DIR}/psm-hostagent.service" "${UNIT}"
${SUDO} -n /usr/bin/systemctl daemon-reload
${SUDO} -n /usr/bin/systemctl enable --now psm-hostagent.service
log 'systemd 单元已启用'

# ---------- 4. 自检 ----------
sleep 1.5
STATE="$(${SUDO} -n /usr/bin/systemctl is-active psm-hostagent.service || true)"
log "服务状态: ${STATE}"
if [[ "${STATE}" != 'active' ]]; then
  printf '[hostagent-install][错误] 服务未启动，最近日志：\n' >&2
  ${SUDO} -n /usr/bin/journalctl -u psm-hostagent -n 40 --no-pager || true
  exit 1
fi

[[ -S /run/psm-hostagent/agent.sock ]] || die '套接字 /run/psm-hostagent/agent.sock 未创建'
log '套接字已就绪：/run/psm-hostagent/agent.sock'

# 以 root 身份做一次真实 op 调用，验证密钥与协议链路
# 注意：密钥是 0400 root:root，安装者（zhaodc）读不到，需借 sudo cat 取回。
PSM_PROBE_SECRET="$(${SUDO} -n /usr/bin/cat "${SECRET_FILE}")" \
python3 - <<'PY' || die '自检调用失败'
import json, os, socket
secret = os.environ['PSM_PROBE_SECRET']
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(20)
s.connect('/run/psm-hostagent/agent.sock')
s.sendall((json.dumps({'op': 'host.probe', 'secret': secret}) + '\n').encode())
buf = b''
while not buf.endswith(b'\n'):
    chunk = s.recv(65536)
    if not chunk:
        break
    buf += chunk
resp = json.loads(buf.decode())
assert resp.get('ok'), '代理返回失败: %s' % resp
data = resp['data']
print('[hostagent-install] 自检通过：protocol=%s os=%s firewall=%s systemRunning=%s'
      % (data['protocol'], data['os'], data['firewallBackend'], data['systemRunning']))
missing = data.get('missing') or []
if missing:
    print('[hostagent-install] 宿主机缺失命令: %s' % ', '.join(missing))
PY

log '安装完成。面板容器需挂载：'
log '  -v /run/psm-hostagent:/run/psm-hostagent'
log '  -v /etc/psm-hostagent:/etc/psm-hostagent:ro'
log '  -e PSM_HOSTAGENT_SOCKET=/run/psm-hostagent/agent.sock'
log '  -e PSM_HOSTAGENT_SECRET_FILE=/etc/psm-hostagent/secret'
