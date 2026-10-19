@echo off
REM Thin wrapper - all logic lives in test.sh (single source of truth).
REM Requires bash on PATH: Git Bash, WSL, or MSYS2.
bash "%~dp0test.sh" %*
exit /b %ERRORLEVEL%
