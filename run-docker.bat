@echo off
setlocal
title DrinkDuel Docker

where docker >nul 2>nul
if errorlevel 1 (
  echo Docker is not available. Install and start Docker Desktop, then try again.
  exit /b 1
)

docker compose up --build
