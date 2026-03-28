#!/bin/bash
set -e

if [ ! -d "td" ]; then
    echo "Cloning TDLib repository..."
    git clone https://github.com/tdlib/td.git
fi

echo "Starting the build process..."
cd td/example/android

./check-environment.sh
./fetch-sdk.sh
./build-openssl.sh
./build-tdlib.sh

cd ../../../

ZIP_PATH="td/example/android/tdlib/tdlib.zip"

if [ ! -f "$ZIP_PATH" ]; then
    echo "Error: Archive $ZIP_PATH not found."
    exit 1
fi

echo "Extracting and copying files..."

unzip -q "$ZIP_PATH" -d tdlib_temp

mkdir -p data/src/main/jniLibs
mkdir -p data/src/main/java

cp -r tdlib_temp/tdlib/lib/* data/src/main/jniLibs/
cp -r tdlib_temp/tdlib/java/* data/src/main/java/

rm -rf tdlib_temp

echo "Done!"