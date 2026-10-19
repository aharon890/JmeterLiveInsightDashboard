@echo off
REM Thin wrapper - all logic lives in merge-pr.sh (single source of truth).
REM Requires bash on PATH: Git Bash, WSL, or MSYS2.
bash "%~dp0merge-pr.sh" %*
exit /b %ERRORLEVEL%
