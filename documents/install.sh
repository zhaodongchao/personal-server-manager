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
