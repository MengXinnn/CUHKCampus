@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" %*
set "campus_exit=%errorlevel%"
if not "%campus_exit%"=="0" pause
exit /b %campus_exit%
