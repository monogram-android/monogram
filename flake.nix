{
  description = "Native Telegram client for Android based on TDLib.";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
    systems.url = "github:nix-systems/default";
  };

  outputs =
    inputs:
    inputs.flake-parts.lib.mkFlake { inherit inputs; } {
      systems = import inputs.systems;

      perSystem =
        { pkgs, system, ... }:
        let
          actualBuildTools = "36.0.0";
          androidComposition =
            pkgs:
            (pkgs.androidenv.composeAndroidPackages {
              platformVersions = [
                "35"
                "36"
              ];

              buildToolsVersions = [
                "35.0.0"
                actualBuildTools
              ];

              cmakeVersions = [
                "3.22.1"
              ];

              ndkVersions = [
                "27.0.12077973"
                "28.2.13676358"
              ];

              abiVersions = [
                "armeabi-v7a"
                "arm64-v8a"
                "x86_64"
              ];

              systemImageTypes = [ "google_apis_playstore" ];
              includeNDK = true;
              includeCmake = true;
              includeSources = true;
              includeExtras = [ "extras;google;auto" ];
            }).androidsdk;
        in
        {
          _module.args.pkgs = import inputs.nixpkgs {
            inherit system;
            config = {
              android_sdk.accept_license = true;
              allowUnfree = true;
            };
          };

          devShells.default = pkgs.mkShell {
            name = "monogram-sdk";

            nativeBuildInputs = [
              (androidComposition pkgs)
              pkgs.glibc
              pkgs.jdk21
              pkgs.android-studio
            ];

            JAVA_HOME = "${pkgs.jdk21}";
            ANDROID_HOME = "${androidComposition pkgs}/libexec/android-sdk";
          };

          apps = {
            buildRelease = {
              type = "app";
              program = "${pkgs.writeShellScriptBin "buildRelease" ''
                if [ -z "$LOCAL_PROPERTIES" ] || [ -z "$GOOGLE_SERVICES_JSON" ]; then
                  echo "LOCAL_PROPERTIES and GOOGLE_SERVICES_JSON environment variables must be set"
                  exit 1
                fi

                if ! ${pkgs.git}/bin/git submodule update --init --recursive; then
                  echo "failed getting submodules"
                fi

                echo $LOCAL_PROPERTIES >local.properties
                echo $GOOGLE_SERVICES_JSON >app/google-services.json

                export ANDROID_HOME="${androidComposition pkgs}/libexec/android-sdk/"
                export ANDROID_SDK_ROOT="${androidComposition pkgs}/libexec/android-sdk/"
                export ANDROID_NDK_HOME="$ANDROID_HOME/ndk-bundle/"
                export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidComposition pkgs}/libexec/android-sdk/build-tools/${actualBuildTools}/aapt2"
                export JAVA_HOME="${pkgs.jdk21}"

                cd presentation/src/main/cpp/
                if ! [ -d libvpx_build ]; then
                  sed -e "s|#!/bin/bash|#!${pkgs.bash}/bin/bash|g" \
                    -e "s|make clean|${pkgs.gnumake}/bin/make clean|g" \
                    -e "s|make -j|${pkgs.gnumake}/bin/make -j|g" build.sh >new_build.sh
                  chmod +x new_build.sh
                  if ! ${pkgs.bash}/bin/bash new_build.sh; then
                    echo "libvpx build failed"
                    exit 1
                  fi
                  rm new_build.sh
                fi 

                cd ../../../..
                ./gradlew assembleRelease
              ''}/bin/buildRelease";
            };
          };
        };
    };
}
