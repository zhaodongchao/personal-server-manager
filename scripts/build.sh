#!/usr/bin/env bash
# ServerPanel 生产构建：前端产物合成进后端单 jar
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> [1/3] 构建前端 web-antd"
cd "$ROOT/frontend/apps/web-antd"
pnpm install --frozen-lockfile
pnpm build

echo "==> [2/3] 前端产物拷入后端静态资源目录"
STATIC_DIR="$ROOT/backend/server-boot/src/main/resources/static"
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -r dist/. "$STATIC_DIR/"

echo "==> [3/3] 构建后端 fat jar"
cd "$ROOT/backend"
mvn -s .mvn/settings.xml clean package -DskipTests

JAR="$ROOT/backend/server-boot/target/serverpanel.jar"
echo ""
echo "构建完成：$JAR"
echo "安装：sudo ./scripts/install.sh $JAR"
