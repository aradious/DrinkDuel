@echo off
setlocal
title DrinkDuel Local Preview
where java >nul 2>nul
if errorlevel 1 goto missing
where npm.cmd >nul 2>nul
if errorlevel 1 goto missing
if not exist "%~dp0frontend\node_modules" (
  echo Frontend dependencies are missing. Run npm install in the frontend folder first.
  pause
  exit /b 1
)
rem Explicit opt-in. Neither the local profile nor dev identity is enabled by default.
set "MAVEN_OPTS=%MAVEN_OPTS% -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
start "DrinkDuel Backend - LOCAL ONLY" /D "%~dp0backend" cmd /k "mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local-preview -Dspring-boot.run.arguments=--drinkduel.dev-identity.enabled=true"
start "DrinkDuel Frontend" /D "%~dp0frontend" cmd /k "npm.cmd start"
echo.
echo Local preview: http://localhost:4200
echo Create a room in one browser. Join in another browser or incognito window.
echo Close the two server windows to stop DrinkDuel.
echo This launcher is for this computer only. Google sign-in is not enabled.
pause
exit /b 0
:missing
echo Java 21 and Node/npm must be available on PATH.
pause
exit /b 1
