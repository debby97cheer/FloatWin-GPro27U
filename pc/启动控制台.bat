@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem 1) 优先用随包虚拟环境里的 pythonw（一定带 tkinter，避免系统 Python 缺 tkinter 打不开）
set "PYW=%~dp0..\.venv_gui\Scripts\pythonw.exe"
if exist "%PYW%" (
  start "" "%PYW%" floatwin_gui.pyw
  exit /b
)

rem 2) 其次系统里的 pythonw / py / python / python3
where pythonw >nul 2>nul && (
  start "" pythonw floatwin_gui.pyw
  exit /b
)
where py >nul 2>nul && (
  start "" py -3 floatwin_gui.pyw
  exit /b
)
where python >nul 2>nul && (
  start "" python floatwin_gui.pyw
  exit /b
)
where python3 >nul 2>nul && (
  start "" python3 floatwin_gui.pyw
  exit /b
)

rem 3) 都没有 Python 就用打包好的 exe
if exist "%~dp0FloatWinConsole.exe" (
  start "" "%~dp0FloatWinConsole.exe"
  exit /b
)
if exist "%~dp0..\gui_exe_dist\FloatWinConsole.exe" (
  start "" "%~dp0..\gui_exe_dist\FloatWinConsole.exe"
  exit /b
)

echo.
echo   没找到 Python，也没找到 FloatWinConsole.exe。
echo.
pause
