@echo off

set IMAGE=namespace-converter-builder
set CONTAINER=ns-conv-extract
set OUTPUT_DIR=%~dp0dist

echo.
echo =====================================================
echo  Building Namespace Converter Rider Plugin
echo =====================================================
echo.

docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not running. Please start Docker Desktop.
    exit /b 1
)

echo [1/3] Building plugin image...
docker build -t %IMAGE% "%~dp0."
if errorlevel 1 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo.
echo [2/3] Extracting plugin zip...
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

docker rm %CONTAINER% >nul 2>&1
docker create --name %CONTAINER% %IMAGE%
if errorlevel 1 (
    echo ERROR: Could not create container.
    exit /b 1
)

docker cp %CONTAINER%:/output/. "%OUTPUT_DIR%"
docker rm %CONTAINER% >nul 2>&1

echo.
echo [3/3] Done!
echo.
echo Plugin zip is ready in:
echo   %OUTPUT_DIR%
echo.
dir /b "%OUTPUT_DIR%\*.zip" 2>nul
echo.
echo Install in Rider:
echo   Settings - Plugins - gear icon - Install Plugin from Disk...
echo.
