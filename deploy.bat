@echo off
setlocal

REM ===========================================
REM  Upbit AutoTrade - Deploy to AWS
REM ===========================================

set "PEM_KEY=D:\aws\mk-key.pem"
set "SSH_USER=ec2-user"
set "SSH_HOST=3.39.211.240"
set "REMOTE_DIR=/home/ec2-user/coin-autotrade"
set "REMOTE_JAR=%REMOTE_DIR%/app.jar"
set "REMOTE_SHUTDOWN=%REMOTE_DIR%/script/shutdown.sh"
set "REMOTE_STARTUP=%REMOTE_DIR%/script/startup.sh"

set "LOCAL_PROJECT_DIR=%~dp0"
set "LOCAL_JAR=%LOCAL_PROJECT_DIR%target\upbit-autotrade-1.0.0.jar"

echo ========================================
echo   Upbit AutoTrade - Deploy Start
echo ========================================
echo.

REM Step 1: Maven Build
echo [1/4] Maven Build...
cd /d "%LOCAL_PROJECT_DIR%"
call mvn -U clean package -DskipTests
if %ERRORLEVEL% neq 0 (
    echo [FAIL] Maven build failed!
    exit /b 1
)
if not exist "%LOCAL_JAR%" (
    echo [FAIL] JAR not found: %LOCAL_JAR%
    exit /b 1
)
echo [OK] Build complete
echo.

REM Step 2: Stop remote app
echo [2/4] Stopping remote app...
ssh -i "%PEM_KEY%" -o StrictHostKeyChecking=no %SSH_USER%@%SSH_HOST% "bash %REMOTE_SHUTDOWN%" 2>nul
echo [OK] App stopped
echo.

REM Step 3: Backup + Upload JAR
echo [3/4] Uploading JAR...
ssh -i "%PEM_KEY%" -o StrictHostKeyChecking=no %SSH_USER%@%SSH_HOST% "cp %REMOTE_JAR% %REMOTE_JAR%.bak 2>/dev/null" 2>nul
scp -i "%PEM_KEY%" -o StrictHostKeyChecking=no "%LOCAL_JAR%" %SSH_USER%@%SSH_HOST%:%REMOTE_JAR%
if %ERRORLEVEL% neq 0 (
    echo [FAIL] Upload failed!
    exit /b 1
)
echo [OK] Upload complete
echo.

REM Step 4: Start remote app
echo [4/4] Starting remote app...
ssh -i "%PEM_KEY%" -o StrictHostKeyChecking=no %SSH_USER%@%SSH_HOST% "bash %REMOTE_STARTUP%"
echo [OK] App started
echo.

echo ========================================
echo   Deploy complete!
echo   https://mkgalaxy.kr/
echo ========================================
