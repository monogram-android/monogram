@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "SOURCE_SELECTION="
set "RUNTIME_SELECTION="
set "BUILD_TYPE_SELECTION="
set "ABI_SELECTION="
set "HAS_ARGS=0"
set "EARLY_EXIT=0"

pushd "%SCRIPT_DIR%" >nul || (
    echo Error: failed to open the repository directory.
    exit /b 1
)

if not exist "%SCRIPT_DIR%gradlew.bat" (
    echo Error: gradlew.bat was not found in %SCRIPT_DIR%.
    popd >nul
    exit /b 1
)

:parse_loop
if "%~1"=="" goto after_parse
set "HAS_ARGS=1"
call :parse_arg "%~1"
if errorlevel 1 (
    popd >nul
    exit /b 1
)
if "%EARLY_EXIT%"=="1" (
    popd >nul
    exit /b 0
)
shift
goto parse_loop

:after_parse
if "%HAS_ARGS%"=="0" (
    call :prompt_source
    if errorlevel 1 goto fail
    call :prompt_runtime
    if errorlevel 1 goto fail
    call :prompt_build_type
    if errorlevel 1 goto fail
    call :prompt_abi
    if errorlevel 1 goto fail
)

call :resolve_sources
if errorlevel 1 goto fail
call :resolve_runtimes
if errorlevel 1 goto fail
call :resolve_build_types
if errorlevel 1 goto fail
call :resolve_abi
if errorlevel 1 goto fail

call :ensure_android_sdk
if errorlevel 1 goto fail

call :ensure_google_services_if_needed
if errorlevel 1 goto fail

call :print_selection_summary
if errorlevel 1 goto fail

for %%S in (!SOURCES!) do (
    for %%R in (!RUNTIMES!) do (
        for %%B in (!BUILD_TYPES!) do (
            set "SOURCE_CAP="
            set "RUNTIME_CAP="
            set "TYPE_CAP="
            set "TASK="
            set "VARIANT_LABEL=%%S-%%R-%%B"
            set "OUTPUT_FLAVOR=%%S"
            set "OUTPUT_DIR="

            if /I "%%S"=="official" set "SOURCE_CAP=Official"
            if /I "%%S"=="telemt" set "SOURCE_CAP=Telemt"
            if /I "%%R"=="google" set "RUNTIME_CAP=Firebase"
            if /I "%%R"=="foss" set "RUNTIME_CAP=Libre"
            if /I "%%B"=="debug" set "TYPE_CAP=Debug"
            if /I "%%B"=="release" set "TYPE_CAP=Release"
            if not defined SOURCE_CAP goto fail
            if not defined RUNTIME_CAP goto fail
            if not defined TYPE_CAP goto fail

            set "TASK=:app:assemble!SOURCE_CAP!!RUNTIME_CAP!!TYPE_CAP!"
            if /I "%%R"=="google" set "OUTPUT_FLAVOR=%%SFirebase"
            set "OUTPUT_DIR=%SCRIPT_DIR%app\build\outputs\apk\!OUTPUT_FLAVOR!\%%B"

            set "VERIFY_TASK=verify!SOURCE_CAP!!RUNTIME_CAP!!TYPE_CAP!BeforeAssemble"

            echo Verifying tests and building !VARIANT_LABEL!
            echo Gradle tasks: !VERIFY_TASK! !TASK!
            call "%SCRIPT_DIR%gradlew.bat" !VERIFY_TASK! !TASK!
            if errorlevel 1 (
                echo Build failed.
                goto fail
            )
            echo.

            echo Artifacts for !VARIANT_LABEL!:
            if not exist "!OUTPUT_DIR!" (
                echo   Output directory not found: !OUTPUT_DIR!
                echo.
            ) else (
                set "FOUND_ANY=0"
                for %%F in ("!OUTPUT_DIR!\*.apk") do (
                    if exist "%%~fF" (
                        set "ARTIFACT_MATCH=0"
                        set "FILE_NAME=%%~nxF"
                        if /I "!ABI_FILTER!"=="all" set "ARTIFACT_MATCH=1"
                        if /I not "!ABI_FILTER!"=="all" (
                            set "ABI_NEEDLE=-!ABI_FILTER!-"
                            if not "!FILE_NAME:%ABI_NEEDLE%=!"=="!FILE_NAME!" set "ARTIFACT_MATCH=1"
                        )
                        if "!ARTIFACT_MATCH!"=="1" (
                            echo   %%~fF
                            set "FOUND_ANY=1"
                        )
                    )
                )
                if "!FOUND_ANY!"=="0" echo   No APK files matched ABI filter '!ABI_FILTER!'.
                if /I "%%B"=="release" echo   Release copies are also placed in: %SCRIPT_DIR%app\releases
                echo.
            )
        )
    )
)

echo Build finished successfully.
popd >nul
exit /b 0

:fail
popd >nul
exit /b 1

:parse_arg
set "ARG=%~1"
if /I "%ARG%"=="--help" set "EARLY_EXIT=1" & call :print_usage & exit /b 0
if /I "%ARG%"=="-h" set "EARLY_EXIT=1" & call :print_usage & exit /b 0
if /I "%ARG%"=="--list" set "EARLY_EXIT=1" & call :print_list & exit /b 0

if /I "%ARG%"=="-o" set "SOURCE_SELECTION=official" & exit /b 0
if /I "%ARG%"=="-official" set "SOURCE_SELECTION=official" & exit /b 0
if /I "%ARG%"=="--official" set "SOURCE_SELECTION=official" & exit /b 0
if /I "%ARG%"=="-t" set "SOURCE_SELECTION=telemt" & exit /b 0
if /I "%ARG%"=="-telemt" set "SOURCE_SELECTION=telemt" & exit /b 0
if /I "%ARG%"=="--telemt" set "SOURCE_SELECTION=telemt" & exit /b 0
if /I "%ARG%"=="-tdlib-all" set "SOURCE_SELECTION=all" & exit /b 0
if /I "%ARG%"=="--tdlib-all" set "SOURCE_SELECTION=all" & exit /b 0
if /I "%ARG%"=="-src-all" set "SOURCE_SELECTION=all" & exit /b 0
if /I "%ARG%"=="--src-all" set "SOURCE_SELECTION=all" & exit /b 0

if /I "%ARG%"=="-g" set "RUNTIME_SELECTION=google" & exit /b 0
if /I "%ARG%"=="-google" set "RUNTIME_SELECTION=google" & exit /b 0
if /I "%ARG%"=="--google" set "RUNTIME_SELECTION=google" & exit /b 0
if /I "%ARG%"=="-firebase" set "RUNTIME_SELECTION=google" & exit /b 0
if /I "%ARG%"=="--firebase" set "RUNTIME_SELECTION=google" & exit /b 0
if /I "%ARG%"=="-f" set "RUNTIME_SELECTION=foss" & exit /b 0
if /I "%ARG%"=="-foss" set "RUNTIME_SELECTION=foss" & exit /b 0
if /I "%ARG%"=="--foss" set "RUNTIME_SELECTION=foss" & exit /b 0
if /I "%ARG%"=="-libre" set "RUNTIME_SELECTION=foss" & exit /b 0
if /I "%ARG%"=="--libre" set "RUNTIME_SELECTION=foss" & exit /b 0
if /I "%ARG%"=="-runtime-all" set "RUNTIME_SELECTION=all" & exit /b 0
if /I "%ARG%"=="--runtime-all" set "RUNTIME_SELECTION=all" & exit /b 0
if /I "%ARG%"=="-rt-all" set "RUNTIME_SELECTION=all" & exit /b 0
if /I "%ARG%"=="--rt-all" set "RUNTIME_SELECTION=all" & exit /b 0

if /I "%ARG%"=="-d" set "BUILD_TYPE_SELECTION=debug" & exit /b 0
if /I "%ARG%"=="-debug" set "BUILD_TYPE_SELECTION=debug" & exit /b 0
if /I "%ARG%"=="--debug" set "BUILD_TYPE_SELECTION=debug" & exit /b 0
if /I "%ARG%"=="-r" set "BUILD_TYPE_SELECTION=release" & exit /b 0
if /I "%ARG%"=="-release" set "BUILD_TYPE_SELECTION=release" & exit /b 0
if /I "%ARG%"=="--release" set "BUILD_TYPE_SELECTION=release" & exit /b 0
if /I "%ARG%"=="-type-all" set "BUILD_TYPE_SELECTION=all" & exit /b 0
if /I "%ARG%"=="--type-all" set "BUILD_TYPE_SELECTION=all" & exit /b 0
if /I "%ARG%"=="-build-all" set "BUILD_TYPE_SELECTION=all" & exit /b 0
if /I "%ARG%"=="--build-all" set "BUILD_TYPE_SELECTION=all" & exit /b 0
if /I "%ARG%"=="-bt-all" set "BUILD_TYPE_SELECTION=all" & exit /b 0
if /I "%ARG%"=="--bt-all" set "BUILD_TYPE_SELECTION=all" & exit /b 0

if /I "%ARG%"=="-a64" set "ABI_SELECTION=arm64-v8a" & exit /b 0
if /I "%ARG%"=="-arm64" set "ABI_SELECTION=arm64-v8a" & exit /b 0
if /I "%ARG%"=="--arm64" set "ABI_SELECTION=arm64-v8a" & exit /b 0
if /I "%ARG%"=="--arm64-v8a" set "ABI_SELECTION=arm64-v8a" & exit /b 0
if /I "%ARG%"=="-a32" set "ABI_SELECTION=armeabi-v7a" & exit /b 0
if /I "%ARG%"=="-arm32" set "ABI_SELECTION=armeabi-v7a" & exit /b 0
if /I "%ARG%"=="--arm32" set "ABI_SELECTION=armeabi-v7a" & exit /b 0
if /I "%ARG%"=="-armv7" set "ABI_SELECTION=armeabi-v7a" & exit /b 0
if /I "%ARG%"=="--armv7" set "ABI_SELECTION=armeabi-v7a" & exit /b 0
if /I "%ARG%"=="--armeabi-v7a" set "ABI_SELECTION=armeabi-v7a" & exit /b 0
if /I "%ARG%"=="-x64" set "ABI_SELECTION=x86_64" & exit /b 0
if /I "%ARG%"=="--x64" set "ABI_SELECTION=x86_64" & exit /b 0
if /I "%ARG%"=="--x86_64" set "ABI_SELECTION=x86_64" & exit /b 0
if /I "%ARG%"=="-u" set "ABI_SELECTION=universal" & exit /b 0
if /I "%ARG%"=="-universal" set "ABI_SELECTION=universal" & exit /b 0
if /I "%ARG%"=="--universal" set "ABI_SELECTION=universal" & exit /b 0
if /I "%ARG%"=="-abi-all" set "ABI_SELECTION=all" & exit /b 0
if /I "%ARG%"=="--abi-all" set "ABI_SELECTION=all" & exit /b 0

if /I "%ARG%"=="-all" (
    set "SOURCE_SELECTION=all"
    set "RUNTIME_SELECTION=all"
    set "BUILD_TYPE_SELECTION=all"
    set "ABI_SELECTION=all"
    exit /b 0
)
if /I "%ARG%"=="--all" (
    set "SOURCE_SELECTION=all"
    set "RUNTIME_SELECTION=all"
    set "BUILD_TYPE_SELECTION=all"
    set "ABI_SELECTION=all"
    exit /b 0
)
if /I "%ARG%"=="-all-official" (
    set "SOURCE_SELECTION=official"
    set "RUNTIME_SELECTION=all"
    set "BUILD_TYPE_SELECTION=all"
    set "ABI_SELECTION=all"
    exit /b 0
)
if /I "%ARG%"=="--all-official" (
    set "SOURCE_SELECTION=official"
    set "RUNTIME_SELECTION=all"
    set "BUILD_TYPE_SELECTION=all"
    set "ABI_SELECTION=all"
    exit /b 0
)
if /I "%ARG%"=="-all-telemt" (
    set "SOURCE_SELECTION=telemt"
    set "RUNTIME_SELECTION=all"
    set "BUILD_TYPE_SELECTION=all"
    set "ABI_SELECTION=all"
    exit /b 0
)
if /I "%ARG%"=="--all-telemt" (
    set "SOURCE_SELECTION=telemt"
    set "RUNTIME_SELECTION=all"
    set "BUILD_TYPE_SELECTION=all"
    set "ABI_SELECTION=all"
    exit /b 0
)

echo Error: unsupported argument '%ARG%'.
echo.
call :print_usage
exit /b 1

:prompt_source
echo Step 1/4: Select TDLib source
echo   1^) official
echo   2^) telemt
echo   3^) both
choice /C 123 /M "Choice"
if errorlevel 3 set "SOURCE_SELECTION=all" & exit /b 0
if errorlevel 2 set "SOURCE_SELECTION=telemt" & exit /b 0
if errorlevel 1 set "SOURCE_SELECTION=official" & exit /b 0
echo Error: failed to read the TDLib choice.
exit /b 1

:prompt_runtime
echo Step 2/4: Select runtime
echo   1^) FOSS / libre
echo   2^) Google / Firebase
echo   3^) both
choice /C 123 /M "Choice"
if errorlevel 3 set "RUNTIME_SELECTION=all" & exit /b 0
if errorlevel 2 set "RUNTIME_SELECTION=google" & exit /b 0
if errorlevel 1 set "RUNTIME_SELECTION=foss" & exit /b 0
echo Error: failed to read the runtime choice.
exit /b 1

:prompt_build_type
echo Step 3/4: Select build type
echo   1^) debug
echo   2^) release
echo   3^) both
choice /C 123 /M "Choice"
if errorlevel 3 set "BUILD_TYPE_SELECTION=all" & exit /b 0
if errorlevel 2 set "BUILD_TYPE_SELECTION=release" & exit /b 0
if errorlevel 1 set "BUILD_TYPE_SELECTION=debug" & exit /b 0
echo Error: failed to read the build type choice.
exit /b 1

:prompt_abi
echo Step 4/4: Select ABI filter
echo   1^) arm64-v8a
echo   2^) armeabi-v7a
echo   3^) x86_64
echo   4^) universal
echo   5^) all
choice /C 12345 /M "Choice"
if errorlevel 5 set "ABI_SELECTION=all" & exit /b 0
if errorlevel 4 set "ABI_SELECTION=universal" & exit /b 0
if errorlevel 3 set "ABI_SELECTION=x86_64" & exit /b 0
if errorlevel 2 set "ABI_SELECTION=armeabi-v7a" & exit /b 0
if errorlevel 1 set "ABI_SELECTION=arm64-v8a" & exit /b 0
echo Error: failed to read the ABI choice.
exit /b 1

:resolve_sources
if not defined SOURCE_SELECTION set "SOURCE_SELECTION=all"
if /I "%SOURCE_SELECTION%"=="official" set "SOURCES=official" & exit /b 0
if /I "%SOURCE_SELECTION%"=="telemt" set "SOURCES=telemt" & exit /b 0
if /I "%SOURCE_SELECTION%"=="all" set "SOURCES=official telemt" & exit /b 0
echo Error: invalid TDLib selection '%SOURCE_SELECTION%'.
exit /b 1

:resolve_runtimes
if not defined RUNTIME_SELECTION set "RUNTIME_SELECTION=all"
if /I "%RUNTIME_SELECTION%"=="foss" set "RUNTIMES=foss" & exit /b 0
if /I "%RUNTIME_SELECTION%"=="google" set "RUNTIMES=google" & exit /b 0
if /I "%RUNTIME_SELECTION%"=="all" set "RUNTIMES=foss google" & exit /b 0
echo Error: invalid runtime selection '%RUNTIME_SELECTION%'.
exit /b 1

:resolve_build_types
if not defined BUILD_TYPE_SELECTION set "BUILD_TYPE_SELECTION=all"
if /I "%BUILD_TYPE_SELECTION%"=="debug" set "BUILD_TYPES=debug" & exit /b 0
if /I "%BUILD_TYPE_SELECTION%"=="release" set "BUILD_TYPES=release" & exit /b 0
if /I "%BUILD_TYPE_SELECTION%"=="all" set "BUILD_TYPES=debug release" & exit /b 0
echo Error: invalid build type selection '%BUILD_TYPE_SELECTION%'.
exit /b 1

:resolve_abi
if not defined ABI_SELECTION set "ABI_SELECTION=all"
if /I "%ABI_SELECTION%"=="arm64-v8a" set "ABI_FILTER=arm64-v8a" & exit /b 0
if /I "%ABI_SELECTION%"=="armeabi-v7a" set "ABI_FILTER=armeabi-v7a" & exit /b 0
if /I "%ABI_SELECTION%"=="x86_64" set "ABI_FILTER=x86_64" & exit /b 0
if /I "%ABI_SELECTION%"=="universal" set "ABI_FILTER=universal" & exit /b 0
if /I "%ABI_SELECTION%"=="all" set "ABI_FILTER=all" & exit /b 0
echo Error: invalid ABI selection '%ABI_SELECTION%'.
exit /b 1

:ensure_android_sdk
set "SDK_DIR_RAW="
if exist "%SCRIPT_DIR%local.properties" (
    for /f "usebackq tokens=1,* delims==" %%A in ("%SCRIPT_DIR%local.properties") do (
        if "%%A"=="sdk.dir" set "SDK_DIR_RAW=%%B"
    )
)

if defined SDK_DIR_RAW (
    set "SDK_DIR=!SDK_DIR_RAW:\:=:!"
    set "SDK_DIR=!SDK_DIR:\\=\!"
    if exist "!SDK_DIR!\" exit /b 0
    echo Error: Android SDK path from local.properties does not exist: !SDK_DIR!
    exit /b 1
)

if defined ANDROID_HOME (
    if exist "%ANDROID_HOME%\" exit /b 0
)
if defined ANDROID_SDK_ROOT (
    if exist "%ANDROID_SDK_ROOT%\" exit /b 0
)

echo Error: Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or configure sdk.dir in local.properties.
exit /b 1

:ensure_google_services_if_needed
for %%R in (%RUNTIMES%) do (
    if /I "%%R"=="google" (
        if not exist "%SCRIPT_DIR%app\google-services.json" (
            echo Error: Google/Firebase build selected, but app\google-services.json is missing.
            exit /b 1
        )
    )
)
exit /b 0

:print_selection_summary
set /a TASK_COUNT=0
for %%S in (%SOURCES%) do (
    for %%R in (%RUNTIMES%) do (
        for %%B in (%BUILD_TYPES%) do (
            set /a TASK_COUNT+=1
        )
    )
)
echo Build plan:
echo   TDLib: %SOURCES%
echo   Runtime: %RUNTIMES%
echo   Build type: %BUILD_TYPES%
echo   ABI filter: %ABI_FILTER%
echo   Build variants: %TASK_COUNT%
echo.
exit /b 0

:print_usage
echo Usage:
echo   build.bat
echo   build.bat [filters...]
echo   build.bat --help
echo   build.bat --list
echo.
echo Interactive mode:
echo   1. Select TDLib source: official / telemt / both
echo   2. Select runtime: FOSS ^(libre^) / Google ^(Firebase^) / both
echo   3. Select build type: debug / release / both
echo   4. Select ABI: arm64-v8a / armeabi-v7a / x86_64 / universal / all
echo.
echo Short filters:
echo   -o, -official          Build only official TDLib variants
echo   -t, -telemt            Build only telemt TDLib variants
echo   -g, -google            Build only Google/Firebase variants
echo   -f, -foss              Build only FOSS/libre variants
echo   -d, -debug             Build only debug variants
echo   -r, -release           Build only release variants
echo   -a64, -arm64           Show only arm64-v8a APKs
echo   -a32, -armv7           Show only armeabi-v7a APKs
echo   -x64                   Show only x86_64 APKs
echo   -u, -universal         Show only universal APKs
echo   -all                   Build everything
echo   -all-official          Build all official combinations
echo   -all-telemt            Build all telemt combinations
echo.
echo Extra filters:
echo   -tdlib-all             Build official and telemt
echo   -runtime-all           Build FOSS and Google
echo   -type-all              Build debug and release
echo   -abi-all               Show all generated APKs
echo.
echo Examples:
echo   build.bat -o -f -r -a64
echo   build.bat -t -g -d
echo   build.bat -all-official
echo   build.bat -all
echo.
echo Notes:
echo   - If you pass filters, any group you do not specify defaults to "all".
echo   - The script validates the Android SDK path before starting Gradle.
echo   - Google builds require app\google-services.json.
echo   - Unit-test verification runs before each APK assemble task.
echo   - ABI selection filters the APK files shown after the build.
echo     Gradle still produces the split outputs configured by the project.
exit /b 0

:print_list
echo Build matrix:
echo   TDLib sources:
echo     official
echo     telemt
echo.
echo   Runtimes:
echo     foss    ^(maps to Gradle Libre^)
echo     google  ^(maps to Gradle Firebase^)
echo.
echo   Build types:
echo     debug
echo     release
echo.
echo   ABI filters:
echo     arm64-v8a
echo     armeabi-v7a
echo     x86_64
echo     universal
echo     all
echo.
echo Concrete variants:
echo   official-google-debug
echo   official-google-release
echo   official-foss-debug
echo   official-foss-release
echo   telemt-google-debug
echo   telemt-google-release
echo   telemt-foss-debug
echo   telemt-foss-release
exit /b 0
