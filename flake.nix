{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
    build-gradle-application.url = "github:raphiz/buildGradleApplication";
  };

  outputs =
    inputs@{ flake-parts, build-gradle-application, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      perSystem =
        { system, pkgs, ... }:
        let
          java = pkgs.graalvmPackages.graalvm-ce.overrideAttrs (old: {
            # native image links glibc's static libm.a which is incompatible
            # with the dynamically linked libc on glibc >= 2.38 as _dl_x86_cpu_features is in static libc
            postFixup = (old.postFixup or "") + ''
              substituteInPlace $out/bin/.native-image-wrapped_ \
                --replace-fail " -H:CLibraryPath=${pkgs.glibc.static}/lib" ""
            '';
          });

          gradle = pkgs.gradleFromWrapper {
            wrapperPropertiesPath = ./gradle/wrapper/gradle-wrapper.properties;
            defaultJava = java;
          };

          package = pkgs.callPackage ./package.nix {
            inherit gradle java;
            ffmpeg = pkgs.ffmpeg-headless;
          };
        in
        {
          _module.args.pkgs = import inputs.nixpkgs {
            inherit system;
            overlays = [
              build-gradle-application.overlays.default
            ];
            config = { };
          };

          devShells.default = pkgs.mkShell {
            packages = with pkgs; [
              java
              gradle
              kotlin
              ffmpeg

              package.updateVerificationMetadata
            ];

            JAVA_HOME = "${java}";
          };

          packages.default = package;

          formatter = pkgs.nixfmt-tree;
        };
    };
}
