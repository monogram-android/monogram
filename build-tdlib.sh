#!/bin/bash
set -e

prompt_build_choice() {
    echo "Select TDLib build target:"
    echo "  1) official"
    echo "  2) telemt"
    echo "  3) both"
    printf "Enter choice [1-3]: "
    read -r choice

    case "$choice" in
        1) echo "official" ;;
        2) echo "telemt" ;;
        3) echo "both" ;;
        *) echo "Invalid choice: $choice" >&2; exit 1 ;;
    esac
}

TARGET_SELECTION="${1:-}"

if [ -z "$TARGET_SELECTION" ]; then
    TARGET_SELECTION="$(prompt_build_choice)"
fi

case "$TARGET_SELECTION" in
    official|telemt|both)
        ;;
    *)
        echo "Usage: $0 [official|telemt|both]"
        exit 1
        ;;
esac

ensure_repo() {
    local repo_dir="$1"
    local repo_url="$2"

    if [ ! -d "$repo_dir" ]; then
        echo "Cloning $repo_url into $repo_dir..."
        git clone "$repo_url" "$repo_dir"
    fi
}

build_variant() {
    local variant="$1"
    local repo_dir="$2"
    local repo_url="$3"
    local zip_path="$repo_dir/example/android/tdlib/tdlib.zip"
    local temp_dir="tdlib_temp_$variant"
    local native_lib_dir=""

    ensure_repo "$repo_dir" "$repo_url"

    echo "Starting TDLib build for $variant..."
    (
        cd "$repo_dir/example/android"
        ./check-environment.sh
        ./fetch-sdk.sh
        ./build-openssl.sh
        ./build-tdlib.sh
    )

    if [ ! -f "$zip_path" ]; then
        echo "Error: Archive $zip_path not found."
        exit 1
    fi

    echo "Extracting and copying files for $variant..."
    rm -rf "$temp_dir"
    unzip -q "$zip_path" -d "$temp_dir"

    if [ -d "$temp_dir/tdlib/lib" ]; then
        native_lib_dir="$temp_dir/tdlib/lib"
    elif [ -d "$temp_dir/tdlib/libs" ]; then
        native_lib_dir="$temp_dir/tdlib/libs"
    else
        echo "Error: Native libraries directory not found in $zip_path."
        exit 1
    fi

    mkdir -p "data/src/$variant/jniLibs"
    rm -rf "data/src/$variant/jniLibs"/*
    cp -r "$native_lib_dir/"* "data/src/$variant/jniLibs/"

    mkdir -p "data/src/$variant/java"
    rm -rf "data/src/$variant/java/org/drinkless/tdlib"
    cp -r "$temp_dir/tdlib/java/"* "data/src/$variant/java/"

    rm -rf "$temp_dir"
    echo "Done! TDLib prebuilts copied to data/src/$variant/jniLibs"
}

case "$TARGET_SELECTION" in
    official)
        build_variant "official" "td" "https://github.com/tdlib/td.git"
        ;;
    telemt)
        build_variant "telemt" "td-telemt" "https://github.com/telemt/tdlib-obf.git"
        ;;
    both)
        build_variant "official" "td" "https://github.com/tdlib/td.git"
        build_variant "telemt" "td-telemt" "https://github.com/telemt/tdlib-obf.git"
        ;;
esac
