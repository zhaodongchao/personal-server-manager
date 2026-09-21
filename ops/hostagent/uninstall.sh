#!/usr/bin/env bash
# ============================================================
# psm-hostagent 卸载脚本：对系统零残留
#   - 停止并禁用 systemd 服务
#   - 删除 /usr/local/lib/psm-hostagent、/etc/systemd/system/psm-hostagent.service
#   - 保留 /etc/psm-hostagent/secret 与 /var/lib/psm-hostagent（数据/密钥），
#     如需一并清除请加 --purge
#
# 作者: zhaodc          创建时间: 2026-09-21
# ============================================================
set -Eeuo pipefail

SUDO=/usr/bin/sudo
LIB_DIR=/usr/local/lib/psm-hostagent
UNIT=/etc/systemd/system/psm-hostagent.service
PURGE=0
[[ "${1:-}" == "--purge" ]] && PURGE=1

log() { printf '[hostagent-uninstall] %s\n' "$*"; }

${SUDO} -n /usr/bin/systemctl disable --now psm-hostagent.service 2>/dev/null || true
${SUDO} -n /usr/bin/cp -f /dev/null "${UNIT}" 2>/dev/null || true
${SUDO} -n /usr/bin/rm -f "${UNIT}" 2>/dev/null || true
${SUDO} -n /usr/bin/systemctl daemon-reload || true
${SUDO} -n /usr/bin/rm -rf "${LIB_DIR}" || true
log '服务与程序文件已移除'

if [[ "${PURGE}" == "1" ]]; then
  ${SUDO} -n /usr/bin/rm -rf /etc/psm-hostagent /var/lib/psm-hostagent /run/psm-hostagent || true
  log '已清除密钥与状态目录（--purge）'
else
  log '保留 /etc/psm-hostagent 与 /var/lib/psm-hostagent（如需清除请加 --purge）'
fi
log '卸载完成；本代理从未修改 ufw 规则 / crontab / 宝塔配置'
