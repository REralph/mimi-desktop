@echo off
rem Builds and installs Mimi, with shortcuts on the Desktop and in the Start menu.
rem Run again to update. Pass -NoBuild to reinstall the last build without rebuilding.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install.ps1" %*
if errorlevel 1 (
    echo.
    echo Something went wrong - see the message above.
)
pause
