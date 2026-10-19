@echo off
REM Thin wrapper - all logic lives in test-smoke.sh (single source of truth).
REM Requires bash on PATH: Git Bash, WSL, or MSYS2.
bash "%~dp0test-smoke.sh" %*
exit /b %ERRORLEVEL%
