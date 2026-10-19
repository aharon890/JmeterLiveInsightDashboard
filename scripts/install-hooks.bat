@echo off
REM Thin wrapper - all logic lives in install-hooks.sh (single source of truth).
REM Requires bash on PATH: Git Bash, WSL, or MSYS2.
bash "%~dp0install-hooks.sh" %*
exit /b %ERRORLEVEL%
