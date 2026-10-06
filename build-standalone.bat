@echo off
setlocal
title DrinkDuel Standalone Build
set "MAVEN_OPTS=%MAVEN_OPTS% -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"

where java >nul 2>nul
if errorlevel 1 goto missing_java
where npm.cmd >nul 2>nul
if errorlevel 1 goto missing_node

if not exist "%~dp0frontend\package-lock.json" (
  echo Frontend package-lock.json is missing.
  exit /b 1
)
if not exist "%~dp0backend\mvnw.cmd" (
  echo Backend Maven Wrapper is missing.
  exit /b 1
)

if not defined APP_BASE_PATH set "APP_BASE_PATH=/"
if not "%APP_BASE_PATH:~0,1%"=="/" (
  echo APP_BASE_PATH must start with / and end with /.
  exit /b 1
)
if not "%APP_BASE_PATH:~-1%"=="/" (
  echo APP_BASE_PATH must start with / and end with /.
  exit /b 1
)

echo [1/3] Building the Angular production application for %APP_BASE_PATH%...
pushd "%~dp0frontend"
call npm.cmd ci
if not "%ERRORLEVEL%"=="0" goto frontend_failed
call npm.cmd run build -- --base-href "%APP_BASE_PATH%"
if not "%ERRORLEVEL%"=="0" goto frontend_failed
popd

if not exist "%~dp0frontend\dist\drinkduel\browser\index.html" (
  echo Angular build output was not found at frontend\dist\drinkduel\browser.
  exit /b 1
)

echo [2/3] Packaging Spring Boot with the Angular application...
pushd "%~dp0backend"
call mvnw.cmd -Pstandalone clean package
if not "%ERRORLEVEL%"=="0" goto backend_failed
popd

echo [3/3] Creating the standalone release artifact...
if not exist "%~dp0release" mkdir "%~dp0release"
copy /Y "%~dp0backend\target\drinkduel-0.0.1-SNAPSHOT.jar" "%~dp0release\drinkduel.jar" >nul
if errorlevel 1 (
  echo Could not create release\drinkduel.jar.
  exit /b 1
)

echo.
echo Standalone JAR created successfully:
echo %~dp0release\drinkduel.jar
exit /b 0

:frontend_failed
popd
echo Angular production build failed.
exit /b 1

:backend_failed
popd
echo Standalone Spring Boot packaging failed.
exit /b 1

:missing_java
echo Java 21 is required on PATH.
exit /b 1

:missing_node
echo Node.js and npm are required on PATH.
exit /b 1
