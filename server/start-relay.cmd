@echo off
rem Runs the PTT relay and restarts it if it ever exits. Output goes to relay.log.
cd /d "%~dp0"
:loop
echo [%date% %time%] starting relay >> relay.log
node server.js >> relay.log 2>&1
ping -n 6 127.0.0.1 >nul
goto loop
