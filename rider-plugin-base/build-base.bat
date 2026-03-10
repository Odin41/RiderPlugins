@echo off
echo.
echo =====================================================
echo  Building rider-plugin-base image
echo  This downloads Rider SDK (~2 GB) — runs ONCE.
echo  All future plugin builds will be fast after this.
echo =====================================================
echo.

docker info >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo ERROR: Docker is not running.
    exit /b 1
)

docker build -t rider-plugin-base .
if %ERRORLEVEL% neq 0 (
    echo ERROR: Base image build failed.
    exit /b 1
)

echo.
echo Base image "rider-plugin-base" is ready!
echo All your Rider plugin projects can now use it.
echo.
