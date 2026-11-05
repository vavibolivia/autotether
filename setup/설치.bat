@echo off
rem AutoTether setup launcher - runs setup.ps1 with admin rights
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
