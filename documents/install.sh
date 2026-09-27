#!/usr/bin/env bash
# ServerPanel 安装：写入 systemd 服务并启动（root 运行）
set -euo pipefail

JAR="${1:-/opt/serverpanel/serverpanel.jar}"
SERVICE_NAME="serverpanel"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
JAR_DIR="$(dirname "$(readlink -f "$JAR")")"

if [ ! -f "$JAR" ]; then
  echo "错误：找不到 jar 文件 $JAR" >&2
  echo "用法：sudo ./scripts/install.sh /path/to/serverpanel.jar" >&2
  exit 1
fi

JAVA_BIN="$(command -v java || true)"
if [ -z "$JAVA_BIN" ]; then
  echo "错误：未找到 java，请先安装 JDK 21 并加入 PATH" >&2
  exit 1
fi

echo "==> 写入 systemd 单元 $SERVICE_FILE"
cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=ServerPanel - Personal Server Management Panel
Documentation=https://github.com/serverpanel
After=network.target mysql.service redis-server.service
Wants=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${JAR_DIR}
ExecStart=${JAVA_BIN} -Xms256m -Xmx1g -jar ${JAR} --spring.profiles.active=prod
Environment="SERVER_PORT=${SERVER_PORT:-8080}"
Environment="MYSQL_HOST=${MYSQL_HOST:-localhost}"
Environment="MYSQL_PORT=${MYSQL_PORT:-3306}"
Environment="MYSQL_DB=${MYSQL_DB:-server_panel}"
Environment="MYSQL_USER=${MYSQL_USER:-panel}"
Environment="MYSQL_PASSWORD=${MYSQL_PASSWORD:-Panel@123456}"
Environment="REDIS_HOST=${REDIS_HOST:-localhost}"
Environment="REDIS_PORT=${REDIS_PORT:-6379}"
Environment="REDIS_PASSWORD=${REDIS_PASSWORD:-}"
Environment="PANEL_MYSQL_ADMIN_USER=${PANEL_MYSQL_ADMIN_USER:-root}"
Environment="PANEL_MYSQL_ADMIN_PASSWORD=${PANEL_MYSQL_ADMIN_PASSWORD:-}"
Environment="PANEL_FILE_ROOTS=${PANEL_FILE_ROOTS:-/www,/srv,/var/www}"
# 邮箱验证码登录 / 自助注册：MAIL_* 决定 SMTP（MAIL_HOST 为空则该功能整体降级），
# PANEL_MAIL_FROM 决定发件人（须与 SMTP 账号一致），PANEL_REGISTER_ENABLED 是否开放自助注册。
# 数值/布尔项在 shell 侧就给默认值，避免 systemd 注入空串导致类型转换失败。
Environment="MAIL_HOST=${MAIL_HOST:-}"
Environment="MAIL_PORT=${MAIL_PORT:-465}"
Environment="MAIL_USERNAME=${MAIL_USERNAME:-}"
Environment="MAIL_PASSWORD=${MAIL_PASSWORD:-}"
Environment="MAIL_SSL=${MAIL_SSL:-true}"
Environment="PANEL_MAIL_FROM=${PANEL_MAIL_FROM:-}"
Environment="PANEL_MAIL_PURPOSES=${PANEL_MAIL_PURPOSES:-all}"
Environment="PANEL_REGISTER_ENABLED=${PANEL_REGISTER_ENABLED:-false}"
# 允许注册的邮箱域名白名单（逗号分隔，留空=不限制）；命中限制时注册/发码返回 1036
Environment="PANEL_REGISTER_EMAIL_DOMAINS=${PANEL_REGISTER_EMAIL_DOMAINS:-}"
# 多 SMTP 发件账号：在首账号（MAIL_*）之外，追加账号用索引变量
# SERVERPANEL_MAIL_ACCOUNTS_1_HOST / _PORT / _USERNAME / _PASSWORD / _FROM / _SSL / _PURPOSES
Restart=on-failure
RestartSec=5
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF

echo "==> 重载并启动服务"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null
systemctl restart "$SERVICE_NAME"
sleep 2
systemctl --no-pager --full status "$SERVICE_NAME" || true

echo ""
echo "安装完成。"
echo "访问：http://<host>:${SERVER_PORT:-8080}/ （默认账号 admin / Admin@123）"
echo "日志：journalctl -u $SERVICE_NAME -f"
echo ""
echo "后续改配置建议：sudo systemctl edit $SERVICE_NAME（写 [Service] Environment=...），"
echo "  生成 override 文件 —— 直接改本服务单元会被重跑 install.sh 覆盖。"
if [ -n "${MAIL_HOST:-}" ]; then
  echo "邮箱验证码登录：已启用（SMTP ${MAIL_HOST}）；自助注册：${PANEL_REGISTER_ENABLED:-false}"
else
  echo "邮箱验证码登录：未配置 MAIL_HOST，已降级（登录页不渲染入口）。"
  echo "  补上 MAIL_HOST / MAIL_USERNAME / MAIL_PASSWORD / PANEL_MAIL_FROM 后重跑本脚本即可。"
fi
