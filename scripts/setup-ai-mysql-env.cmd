@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%set-ai-mysql-env.ps1"

echo.
echo If you changed User environment variables, restart IntelliJ IDEA and any open terminal before starting ReachAI backend services.
pause
