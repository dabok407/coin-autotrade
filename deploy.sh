#!/bin/bash
# ===========================================
# Upbit AutoTrade - AWS 배포 자동화 스크립트
# ===========================================

set -e

# 설정
PEM_KEY="D:/aws/mk-key.pem"
SSH_USER="ec2-user"
SSH_HOST="3.39.211.240"
REMOTE_DIR="/home/ec2-user/coin-autotrade"
REMOTE_JAR="${REMOTE_DIR}/app.jar"
REMOTE_SHUTDOWN="${REMOTE_DIR}/script/shutdown.sh"
REMOTE_STARTUP="${REMOTE_DIR}/script/startup.sh"

LOCAL_PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOCAL_JAR="${LOCAL_PROJECT_DIR}/target/upbit-autotrade-1.0.0.jar"

SSH_CMD="ssh -i ${PEM_KEY} -o StrictHostKeyChecking=no ${SSH_USER}@${SSH_HOST}"
SCP_CMD="scp -i ${PEM_KEY} -o StrictHostKeyChecking=no"

echo "========================================"
echo "  Upbit AutoTrade 배포 시작"
echo "========================================"
echo ""

# Step 1: Maven 빌드
echo "[1/4] Maven 빌드 중..."
cd "${LOCAL_PROJECT_DIR}"
mvn -U clean package -DskipTests
echo ""

# 빌드 결과 확인
if [ ! -f "${LOCAL_JAR}" ]; then
    echo "빌드 실패: JAR 파일을 찾을 수 없습니다: ${LOCAL_JAR}"
    exit 1
fi
JAR_SIZE=$(du -h "${LOCAL_JAR}" | cut -f1)
echo "빌드 완료: ${LOCAL_JAR} (${JAR_SIZE})"
echo ""

# Step 2: 원격 서버 앱 중지
echo "[2/4] 원격 서버 앱 중지 중..."
${SSH_CMD} "bash ${REMOTE_SHUTDOWN}" || echo "  (앱이 이미 중지된 상태일 수 있습니다)"
echo "앱 중지 완료"
echo ""

# Step 3: JAR 업로드
echo "[3/4] JAR 파일 업로드 중..."
${SSH_CMD} "cp ${REMOTE_JAR} ${REMOTE_JAR}.bak 2>/dev/null" || true
${SCP_CMD} "${LOCAL_JAR}" "${SSH_USER}@${SSH_HOST}:${REMOTE_JAR}"
echo "업로드 완료"
echo ""

# Step 4: 원격 서버 앱 시작
echo "[4/4] 원격 서버 앱 시작 중..."
${SSH_CMD} "bash ${REMOTE_STARTUP}"
echo "앱 시작 완료"
echo ""

echo "========================================"
echo "  배포 완료!"
echo "  서버: https://mkgalaxy.kr/"
echo "========================================"
