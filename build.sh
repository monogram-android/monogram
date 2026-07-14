#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=$SCRIPT_DIR
GRADLEW="$REPO_DIR/gradlew"

SOURCE_SELECTION=""
RUNTIME_SELECTION=""
BUILD_TYPE_SELECTION=""
ABI_SELECTION=""
HAS_ARGS=0

print_usage() {
    cat <<'EOF'
Usage:
  ./build.sh
  ./build.sh [filters...]
  ./build.sh --help
  ./build.sh --list

Interactive mode:
  1. Select TDLib source: official / telemt / both
  2. Select runtime: FOSS (libre) / Google (Firebase) / both
  3. Select build type: debug / release / both
  4. Select ABI: arm64-v8a / armeabi-v7a / x86_64 / universal / all

Short filters:
  -o, -official          Build only official TDLib variants
  -t, -telemt            Build only telemt TDLib variants
  -g, -google            Build only Google/Firebase variants
  -f, -foss              Build only FOSS/libre variants
  -d, -debug             Build only debug variants
  -r, -release           Build only release variants
  -a64, -arm64           Show only arm64-v8a APKs
  -a32, -armv7           Show only armeabi-v7a APKs
  -x64                   Show only x86_64 APKs
  -u, -universal         Show only universal APKs
  -all                   Build everything
  -all-official          Build all official combinations
  -all-telemt            Build all telemt combinations

Extra filters:
  -tdlib-all             Build official and telemt
  -runtime-all           Build FOSS and Google
  -type-all              Build debug and release
  -abi-all               Show all generated APKs

Examples:
  ./build.sh -o -f -r -a64
  ./build.sh -t -g -d
  ./build.sh -all-official
  ./build.sh -all

Notes:
  - If you pass filters, any group you do not specify defaults to "all".
  - The script validates the Android SDK path before starting Gradle.
  - Google builds require app/google-services.json.
  - Unit-test verification runs before each APK assemble task.
  - ABI selection filters the APK files shown after the build.
    Gradle still produces the split outputs configured by the project.
EOF
}

print_list() {
    cat <<'EOF'
Build matrix:
  TDLib sources:
    official
    telemt

  Runtimes:
    foss    (maps to Gradle Libre)
    google  (maps to Gradle Firebase)

  Build types:
    debug
    release

  ABI filters:
    arm64-v8a
    armeabi-v7a
    x86_64
    universal
    all

Concrete variants:
  official-google-debug
  official-google-release
  official-foss-debug
  official-foss-release
  telemt-google-debug
  telemt-google-release
  telemt-foss-debug
  telemt-foss-release
EOF
}

parse_arg() {
    case "$1" in
        --help|-h)
            print_usage
            exit 0
            ;;
        --list)
            print_list
            exit 0
            ;;
        -o|-official|--official)
            SOURCE_SELECTION="official"
            ;;
        -t|-telemt|--telemt)
            SOURCE_SELECTION="telemt"
            ;;
        -tdlib-all|--tdlib-all|-src-all|--src-all)
            SOURCE_SELECTION="all"
            ;;
        -g|-google|--google|-firebase|--firebase)
            RUNTIME_SELECTION="google"
            ;;
        -f|-foss|--foss|-libre|--libre)
            RUNTIME_SELECTION="foss"
            ;;
        -runtime-all|--runtime-all|-rt-all|--rt-all)
            RUNTIME_SELECTION="all"
            ;;
        -d|-debug|--debug)
            BUILD_TYPE_SELECTION="debug"
            ;;
        -r|-release|--release)
            BUILD_TYPE_SELECTION="release"
            ;;
        -type-all|--type-all|-build-all|--build-all|-bt-all|--bt-all)
            BUILD_TYPE_SELECTION="all"
            ;;
        -a64|-arm64|--arm64|--arm64-v8a)
            ABI_SELECTION="arm64-v8a"
            ;;
        -a32|-arm32|--arm32|-armv7|--armv7|--armeabi-v7a)
            ABI_SELECTION="armeabi-v7a"
            ;;
        -x64|--x64|--x86_64)
            ABI_SELECTION="x86_64"
            ;;
        -u|-universal|--universal)
            ABI_SELECTION="universal"
            ;;
        -abi-all|--abi-all)
            ABI_SELECTION="all"
            ;;
        -all|--all)
            SOURCE_SELECTION="all"
            RUNTIME_SELECTION="all"
            BUILD_TYPE_SELECTION="all"
            ABI_SELECTION="all"
            ;;
        -all-official|--all-official)
            SOURCE_SELECTION="official"
            RUNTIME_SELECTION="all"
            BUILD_TYPE_SELECTION="all"
            ABI_SELECTION="all"
            ;;
        -all-telemt|--all-telemt)
            SOURCE_SELECTION="telemt"
            RUNTIME_SELECTION="all"
            BUILD_TYPE_SELECTION="all"
            ABI_SELECTION="all"
            ;;
        *)
            echo "Error: unsupported argument '$1'." >&2
            print_usage >&2
            exit 1
            ;;
    esac
}

prompt_source() {
    cat <<'EOF'
Step 1/4: Select TDLib source
  1) official
  2) telemt
  3) both
EOF
    printf "Choice [1-3]: "
    IFS= read -r choice

    case "$choice" in
        1) SOURCE_SELECTION="official" ;;
        2) SOURCE_SELECTION="telemt" ;;
        3) SOURCE_SELECTION="all" ;;
        *)
            echo "Error: invalid TDLib choice '$choice'." >&2
            exit 1
            ;;
    esac
}

prompt_runtime() {
    cat <<'EOF'
Step 2/4: Select runtime
  1) FOSS / libre
  2) Google / Firebase
  3) both
EOF
    printf "Choice [1-3]: "
    IFS= read -r choice

    case "$choice" in
        1) RUNTIME_SELECTION="foss" ;;
        2) RUNTIME_SELECTION="google" ;;
        3) RUNTIME_SELECTION="all" ;;
        *)
            echo "Error: invalid runtime choice '$choice'." >&2
            exit 1
            ;;
    esac
}

prompt_build_type() {
    cat <<'EOF'
Step 3/4: Select build type
  1) debug
  2) release
  3) both
EOF
    printf "Choice [1-3]: "
    IFS= read -r choice

    case "$choice" in
        1) BUILD_TYPE_SELECTION="debug" ;;
        2) BUILD_TYPE_SELECTION="release" ;;
        3) BUILD_TYPE_SELECTION="all" ;;
        *)
            echo "Error: invalid build type choice '$choice'." >&2
            exit 1
            ;;
    esac
}

prompt_abi() {
    cat <<'EOF'
Step 4/4: Select ABI filter
  1) arm64-v8a
  2) armeabi-v7a
  3) x86_64
  4) universal
  5) all
EOF
    printf "Choice [1-5]: "
    IFS= read -r choice

    case "$choice" in
        1) ABI_SELECTION="arm64-v8a" ;;
        2) ABI_SELECTION="armeabi-v7a" ;;
        3) ABI_SELECTION="x86_64" ;;
        4) ABI_SELECTION="universal" ;;
        5) ABI_SELECTION="all" ;;
        *)
            echo "Error: invalid ABI choice '$choice'." >&2
            exit 1
            ;;
    esac
}

resolve_sources() {
    case "${SOURCE_SELECTION:-all}" in
        official) echo "official" ;;
        telemt) echo "telemt" ;;
        all|"") echo "official telemt" ;;
        *)
            echo "Error: invalid TDLib selection '$SOURCE_SELECTION'." >&2
            exit 1
            ;;
    esac
}

resolve_runtimes() {
    case "${RUNTIME_SELECTION:-all}" in
        google) echo "google" ;;
        foss) echo "foss" ;;
        all|"") echo "foss google" ;;
        *)
            echo "Error: invalid runtime selection '$RUNTIME_SELECTION'." >&2
            exit 1
            ;;
    esac
}

resolve_build_types() {
    case "${BUILD_TYPE_SELECTION:-all}" in
        debug) echo "debug" ;;
        release) echo "release" ;;
        all|"") echo "debug release" ;;
        *)
            echo "Error: invalid build type selection '$BUILD_TYPE_SELECTION'." >&2
            exit 1
            ;;
    esac
}

resolve_abi() {
    case "${ABI_SELECTION:-all}" in
        arm64-v8a|armeabi-v7a|x86_64|universal|all|"")
            if [ -n "${ABI_SELECTION:-}" ]; then
                echo "$ABI_SELECTION"
            else
                echo "all"
            fi
            ;;
        *)
            echo "Error: invalid ABI selection '$ABI_SELECTION'." >&2
            exit 1
            ;;
    esac
}

source_capitalized() {
    case "$1" in
        official) echo "Official" ;;
        telemt) echo "Telemt" ;;
        *)
            echo "Error: invalid TDLib source '$1'." >&2
            exit 1
            ;;
    esac
}

runtime_capitalized() {
    case "$1" in
        google) echo "Firebase" ;;
        foss) echo "Libre" ;;
        *)
            echo "Error: invalid runtime '$1'." >&2
            exit 1
            ;;
    esac
}

build_type_capitalized() {
    case "$1" in
        debug) echo "Debug" ;;
        release) echo "Release" ;;
        *)
            echo "Error: invalid build type '$1'." >&2
            exit 1
            ;;
    esac
}

build_task_for() {
    source_value=$(source_capitalized "$1")
    runtime_value=$(runtime_capitalized "$2")
    build_type_value=$(build_type_capitalized "$3")

    printf ':app:assemble%s%s%s' "$source_value" "$runtime_value" "$build_type_value"
}

verify_task_for() {
    source_value=$(source_capitalized "$1")
    runtime_value=$(runtime_capitalized "$2")
    build_type_value=$(build_type_capitalized "$3")

    printf 'verify%s%s%sBeforeAssemble' "$source_value" "$runtime_value" "$build_type_value"
}

variant_label_for() {
    printf '%s-%s-%s' "$1" "$2" "$3"
}

variant_output_dir_for() {
    source_value=$1
    runtime_value=$2
    build_type_value=$3

    flavor_dir=$source_value
    if [ "$runtime_value" = "google" ]; then
        flavor_dir="${source_value}Firebase"
    fi

    printf '%s/app/build/outputs/apk/%s/%s' "$REPO_DIR" "$flavor_dir" "$build_type_value"
}

matches_abi() {
    file_name=$1
    abi_filter=$2

    case "$abi_filter" in
        all) return 0 ;;
        *)
            case "$file_name" in
                *-"$abi_filter"-*.apk) return 0 ;;
                *) return 1 ;;
            esac
            ;;
    esac
}

ensure_google_services_if_needed() {
    runtimes=$1

    case " $runtimes " in
        *" google "*)
            if [ ! -f "$REPO_DIR/app/google-services.json" ]; then
                echo "Error: Google/Firebase build selected, but app/google-services.json is missing." >&2
                exit 1
            fi
            ;;
    esac
}

decode_properties_path() {
    printf '%s' "$1" | sed 's/\\:/:/g; s#\\\\#/#g'
}

path_exists() {
    if [ -d "$1" ]; then
        return 0
    fi

    case "$1" in
        [A-Za-z]:/*)
            windows_path=$(printf '%s' "$1" | sed 's#/#\\#g')

            if command -v cmd.exe >/dev/null 2>&1; then
                if cmd.exe /c "if exist \"$windows_path\\\" (exit 0) else (exit 1)" >/dev/null 2>&1; then
                    return 0
                fi
                return 1
            fi

            if command -v powershell.exe >/dev/null 2>&1; then
                powershell_path=$(printf "%s" "$1" | sed "s/'/''/g")
                if powershell.exe -NoProfile -Command "[System.IO.Directory]::Exists('$powershell_path')" 2>/dev/null | tr -d '\r' | grep -qx 'True'; then
                    return 0
                fi
                return 1
            fi

            drive_letter=$(printf '%s' "$1" | cut -c1 | tr '[:upper:]' '[:lower:]')
            remainder=$(printf '%s' "$1" | cut -c3-)
            msys_path="/$drive_letter/$remainder"
            if [ -d "$msys_path" ]; then
                return 0
            fi
            ;;
    esac

    return 1
}

ensure_android_sdk() {
    if [ -f "$REPO_DIR/local.properties" ]; then
        sdk_dir_raw=$(sed -n 's/^sdk\.dir=//p' "$REPO_DIR/local.properties" | head -n 1)
        if [ -n "$sdk_dir_raw" ]; then
            sdk_dir=$(decode_properties_path "$sdk_dir_raw")

            if command -v powershell.exe >/dev/null 2>&1; then
                powershell_path=$(printf "%s" "$sdk_dir" | sed "s/'/''/g")
                if powershell.exe -NoProfile -Command "if (Test-Path -LiteralPath '$powershell_path' -PathType Container) { exit 0 } else { exit 1 }" >/dev/null 2>&1; then
                    return 0
                fi

                echo "Error: Android SDK path from local.properties does not exist: $sdk_dir" >&2
                exit 1
            fi

            if path_exists "$sdk_dir"; then
                return 0
            fi

            echo "Error: Android SDK path from local.properties does not exist: $sdk_dir" >&2
            exit 1
        fi

        if command -v powershell.exe >/dev/null 2>&1; then
            local_properties_path=$(printf "%s" "$REPO_DIR/local.properties" | sed "s/'/''/g")
            if sdk_check_output=$(
                powershell.exe -NoProfile -Command "\$line = Get-Content -LiteralPath '$local_properties_path' | Where-Object { \$_ -like 'sdk.dir=*' } | Select-Object -First 1; if (\$null -eq \$line) { exit 2 }; \$path = (\$line.Substring(8) -replace '\\\\:', ':' -replace '\\\\\\\\', '\\'); if (Test-Path -LiteralPath \$path -PathType Container) { exit 0 } else { Write-Output \$path; exit 1 }" 2>/dev/null
            ); then
                sdk_check_status=0
            else
                sdk_check_status=$?
            fi

            if [ "$sdk_check_status" -eq 0 ]; then
                return 0
            fi

            if [ "$sdk_check_status" -eq 1 ]; then
                echo "Error: Android SDK path from local.properties does not exist: $(printf '%s' "$sdk_check_output" | tr -d '\r')" >&2
                exit 1
            fi
        fi
    fi

    if [ -n "${ANDROID_HOME:-}" ] && path_exists "$ANDROID_HOME"; then
        return 0
    fi

    if [ -n "${ANDROID_SDK_ROOT:-}" ] && path_exists "$ANDROID_SDK_ROOT"; then
        return 0
    fi

    echo "Error: Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or configure sdk.dir in local.properties." >&2
    exit 1
}

print_selection_summary() {
    sources=$1
    runtimes=$2
    build_types=$3
    abi_filter=$4
    task_count=0

    for source_value in $sources; do
        for runtime_value in $runtimes; do
            for build_type_value in $build_types; do
                task_count=$((task_count + 1))
            done
        done
    done

    echo "Build plan:"
    echo "  TDLib: $sources"
    echo "  Runtime: $runtimes"
    echo "  Build type: $build_types"
    echo "  ABI filter: $abi_filter"
    echo "  Build variants: $task_count"
    echo
}

print_artifacts_for_variant() {
    source_value=$1
    runtime_value=$2
    build_type_value=$3
    abi_filter=$4
    output_dir=$(variant_output_dir_for "$source_value" "$runtime_value" "$build_type_value")
    found_any=0

    echo "Artifacts for $(variant_label_for "$source_value" "$runtime_value" "$build_type_value"):"

    if [ ! -d "$output_dir" ]; then
        echo "  Output directory not found: $output_dir"
        return
    fi

    for apk_file in "$output_dir"/*.apk; do
        if [ ! -f "$apk_file" ]; then
            continue
        fi

        file_name=$(basename "$apk_file")
        if matches_abi "$file_name" "$abi_filter"; then
            echo "  $apk_file"
            found_any=1
        fi
    done

    if [ "$found_any" -eq 0 ]; then
        echo "  No APK files matched ABI filter '$abi_filter'."
    fi

    if [ "$build_type_value" = "release" ]; then
        echo "  Release copies are also placed in: $REPO_DIR/app/releases"
    fi

    echo
}

if [ ! -f "$GRADLEW" ]; then
    echo "Error: gradlew was not found in $REPO_DIR." >&2
    exit 1
fi

while [ $# -gt 0 ]; do
    HAS_ARGS=1
    parse_arg "$1"
    shift
done

if [ "$HAS_ARGS" -eq 0 ]; then
    prompt_source
    prompt_runtime
    prompt_build_type
    prompt_abi
fi

SOURCES=$(resolve_sources)
RUNTIMES=$(resolve_runtimes)
BUILD_TYPES=$(resolve_build_types)
ABI_FILTER=$(resolve_abi)

ensure_android_sdk
ensure_google_services_if_needed "$RUNTIMES"
print_selection_summary "$SOURCES" "$RUNTIMES" "$BUILD_TYPES" "$ABI_FILTER"

cd "$REPO_DIR"

for source_value in $SOURCES; do
    for runtime_value in $RUNTIMES; do
        for build_type_value in $BUILD_TYPES; do
            task=$(build_task_for "$source_value" "$runtime_value" "$build_type_value")
            verify_task=$(verify_task_for "$source_value" "$runtime_value" "$build_type_value")
            label=$(variant_label_for "$source_value" "$runtime_value" "$build_type_value")

            echo "Verifying tests and building $label"
            echo "Gradle tasks: $verify_task $task"
            "$GRADLEW" "$verify_task" "$task"
            echo

            print_artifacts_for_variant "$source_value" "$runtime_value" "$build_type_value" "$ABI_FILTER"
        done
    done
done

echo "Build finished successfully."
