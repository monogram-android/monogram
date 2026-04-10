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
                    androidComposition =
                        pkgs:
                        (pkgs.androidenv.composeAndroidPackages {
                            platformVersions = [
                                "35"
                                "36"
                            ];

                            buildToolsVersions = [
                                "35.0.0"
                            ];

                            cmakeVersions = [
                                "3.22.1"
                            ];

                            ndkVersions = [
                                "27.0.12077973"
                            ];

                            abiVersions = [
                                "armeabi-v7a"
                                "arm64-v8a"
                                "x86_64"
                            ];

                            systemImageTypes = [ "google_apis_playstore" ];
                            includeNDK = true;
                            includeCmake = true;
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

                    devShells.default =
                        (pkgs.buildFHSEnv {
                            name = "monogram-sdk-env";

                            targetPkgs = pkgs: [
                                (androidComposition pkgs)
                                pkgs.glibc
                                pkgs.jdk21
                            ];

                            profile = ''
                                export JAVA_HOME="/usr/lib/openjdk"
                                export ANDROID_HOME="/usr/libexec/android-sdk"
                            '';

                            runScript = "bash";
                        }).env;
                };
        };
}
