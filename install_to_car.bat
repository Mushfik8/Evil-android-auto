@echo off
echo ============================================
echo   AutoFreedom - Android Auto Install Script
echo ============================================
echo.
echo This script installs AutoFreedom so that Android Auto
echo recognizes it (spoofs Play Store installer).
echo.
echo Make sure your phone is connected via USB
echo and USB debugging is enabled!
echo.

REM Try common ADB locations
set ADB_PATH=
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set ADB_PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
) else if exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    set ADB_PATH=%ANDROID_HOME%\platform-tools\adb.exe
) else (
    where adb >nul 2>nul
    if %errorlevel%==0 (
        set ADB_PATH=adb
    ) else (
        echo ERROR: ADB not found! Please install Android SDK Platform Tools.
        echo Download from: https://developer.android.com/studio/releases/platform-tools
        echo.
        echo Or add ADB to your PATH environment variable.
        pause
        exit /b 1
    )
)

echo Found ADB: %ADB_PATH%
echo.

REM Check for connected device
"%ADB_PATH%" devices | findstr /r "device$" >nul
if %errorlevel% neq 0 (
    echo ERROR: No Android device found!
    echo Make sure:
    echo   1. Your phone is connected via USB
    echo   2. USB debugging is enabled
    echo   3. You accepted the debugging prompt on your phone
    echo.
    pause
    exit /b 1
)

echo Device connected!
echo.

REM Find the APK
set APK_PATH=
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    set APK_PATH=app\build\outputs\apk\debug\app-debug.apk
) else if exist "app-debug.apk" (
    set APK_PATH=app-debug.apk
) else if exist "app-release.apk" (
    set APK_PATH=app-release.apk
) else (
    echo ERROR: APK not found!
    echo Build the project first with: gradlew assembleDebug
    echo Or place the APK in this directory.
    echo.
    pause
    exit /b 1
)

echo Found APK: %APK_PATH%
echo.

REM Uninstall old version (ignore errors if not installed)
echo [1/3] Removing old version (if installed)...
"%ADB_PATH%" uninstall com.autofreedom.app.debug >nul 2>nul
"%ADB_PATH%" uninstall com.autofreedom.app >nul 2>nul
echo Done.
echo.

REM ============================================
REM KEY BYPASS: Install with Play Store spoofing
REM ============================================
REM Android Auto REJECTS apps not installed from Google Play Store.
REM The -i flag tells Android that the app was installed by the
REM Play Store (com.android.vending), bypassing this restriction.
REM This is what KingInstaller does internally.
REM ============================================
echo [2/3] Installing with Play Store bypass...
echo (This makes Android Auto accept our app)
echo.
"%ADB_PATH%" install -i "com.android.vending" -r "%APK_PATH%"

if %errorlevel% neq 0 (
    echo.
    echo WARNING: Play Store spoof install failed.
    echo Trying alternative method...
    echo.
    
    REM Alternative: use shell pm install
    "%ADB_PATH%" push "%APK_PATH%" /data/local/tmp/autofreedom.apk
    "%ADB_PATH%" shell pm install -i "com.android.vending" -r /data/local/tmp/autofreedom.apk
    "%ADB_PATH%" shell rm /data/local/tmp/autofreedom.apk
    
    if %errorlevel% neq 0 (
        echo.
        echo ERROR: Installation failed!
        echo Try: Enable "Install from unknown sources" in settings
        pause
        exit /b 1
    )
)

echo.
echo [3/3] Verifying installation...
"%ADB_PATH%" shell pm list packages | findstr "autofreedom" >nul
if %errorlevel%==0 (
    echo.
    echo ============================================
    echo   SUCCESS! AutoFreedom installed!
    echo ============================================
    echo.
    echo Now:
    echo   1. Open Android Auto on your phone
    echo   2. AutoFreedom should appear in the app list
    echo   3. Tap it to open on your car screen
    echo.
    echo If it doesn't appear:
    echo   - Open Android Auto settings on your phone
    echo   - Scroll down and find AutoFreedom
    echo   - Make sure it's enabled
    echo.
) else (
    echo.
    echo WARNING: Could not verify installation.
    echo Check your phone to see if AutoFreedom is installed.
    echo.
)

pause
