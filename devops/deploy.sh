#!/bin/bash
# ============================================================
# Mini-Lottery 部署脚本
# 使用方式：在本地项目根目录执行 bash devops/deploy.sh
# 前提：服务器已安装 Docker + Docker Compose
# ============================================================

# ===== 配置（按你的实际情况修改）=====
SERVER_IP="47.112.180.205"
SERVER_USER="root"
REMOTE_DIR="/opt/mini-lottery"

echo "====== Step 1: 本地打包 ======"
mvn clean package -DskipTests -q
echo "打包完成: target/mini-lottery-0.0.1-SNAPSHOT.jar"

echo ""
echo "====== Step 2: 上传文件到服务器 ======"
ssh ${SERVER_USER}@${SERVER_IP} "mkdir -p ${REMOTE_DIR}"
scp target/mini-lottery-0.0.1-SNAPSHOT.jar ${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}/app.jar
scp devops/Dockerfile ${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}/Dockerfile
scp devops/docker-compose.yml ${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}/docker-compose.yml
echo "上传完成"

echo ""
echo "====== Step 3: 服务器上构建并启动 ======"
ssh ${SERVER_USER}@${SERVER_IP} "cd ${REMOTE_DIR} && docker compose down && docker compose up -d --build"

echo ""
echo "====== 部署完成 ======"
echo "访问地址: http://${SERVER_IP}:8080"
echo "查看日志: ssh ${SERVER_USER}@${SERVER_IP} 'docker logs -f mini-lottery'"
