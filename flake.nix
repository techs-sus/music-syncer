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
          gradle = pkgs.gradleFromWrapper {
            wrapperPropertiesPath = ./gradle/wrapper/gradle-wrapper.properties;
            defaultJava = pkgs.jdk25;
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

          devShells.default =

            pkgs.mkShell {
              packages = with pkgs; [
                jdk25
                gradle
                kotlin
                ffmpeg
              ];
            };

          packages.default = pkgs.callPackage ./package.nix { inherit gradle; };

          formatter = pkgs.nixfmt-tree;
        };
    };
}
