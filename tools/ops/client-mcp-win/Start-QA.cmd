@echo off
rem SlumsMS client QA: double-click to start the controller and the long-running worker. TEST server only.
rem Asks for admin once (the copied client needs it). Settings: worker-config.json (lifetime, idle minutes, auto-recover).
net session >nul 2>&1
if %errorlevel% neq 0 (
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
set QA=Z:\MSv83\SlumsMS-dev\client-mcp
if not exist "%QA%\server.py" (
  echo Z: is not mapped or client-mcp is missing: %QA%
  pause
  exit /b 1
)
cd /d "%QA%"
rem Only one worker at a time: two would both read the same request queue.
powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='python.exe' or Name='pythonw.exe'\" | Where-Object { $_.CommandLine -match 'elevated-worker|focus-controller' }) { exit 1 }"
if %errorlevel% neq 0 (
  echo A QA worker or controller is already running. Close its window first, then start this again.
  pause
  exit /b 1
)
rem Archive old requests and answers (the worker also skips anything written before it starts).
if not exist logs\old mkdir logs\old
for %%F in (worker-inbox.jsonl worker-outbox.jsonl) do (
  if exist logs\%%F move /y logs\%%F "logs\old\%%~nF-%RANDOM%%RANDOM%%%~xF" >nul
)
type nul > logs\worker-inbox.jsonl
rem The idle guard: off only when you say so here, for this run only (reset at the end and on every start).
rem No answer in 20 seconds keeps it ON, so this window can be left alone.
set FORCE=false
choice /c YN /n /t 20 /d N /m "Skip the idle wait for this run? You're OK with the client being driven while you're at the PC [Y/N, N in 20 s]: "
if %errorlevel%==1 set FORCE=true
powershell -NoProfile -Command "$p='%QA%\local-config.json'; $c=Get-Content $p -Raw | ConvertFrom-Json; $c.force=[bool]::Parse('%FORCE%'); $c | ConvertTo-Json -Compress | Set-Content $p -Encoding ascii"
if "%FORCE%"=="true" (echo Idle wait OFF for this run.) else (echo Idle wait ON: requests wait until you have left the PC alone, then run by themselves.)
start "" pythonw.exe -B "%QA%\scripts\focus-controller.py"
timeout /t 2 /nobreak >nul
echo SlumsMS client QA worker is running: 12 hours after the last QA request by default (worker-config.json).
echo It restarts and logs the test client back in if it crashes. Close this window to stop it.
set CRASHES=0
:run
python -B "%QA%\scripts\elevated-worker.py"
rem Exit code 2 = the worker itself crashed: start it again, at most 5 times.
if not %errorlevel%==2 goto stopped
set /a CRASHES+=1
if %CRASHES% geq 5 goto stopped
echo Worker crashed; starting it again in 10 seconds.
timeout /t 10 /nobreak >nul
goto run
:stopped
echo Worker stopped.
powershell -NoProfile -Command "$p='%QA%\local-config.json'; $c=Get-Content $p -Raw | ConvertFrom-Json; $c.force=$false; $c | ConvertTo-Json -Compress | Set-Content $p -Encoding ascii"
echo Idle wait is back ON.
pause
