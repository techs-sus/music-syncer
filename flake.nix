{
  description = "build the music player";

  inputs = {
    nixpkgs.url = "https://channels.nixos.org/nixos-unstable/nixexprs.tar.xz";
    crane.url = "github:ipetkov/crane";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      crane,
      flake-utils,
      ...
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        craneLib = crane.mkLib pkgs;
        inherit (pkgs) lib;

        unfilteredRoot = ./.;

        rustyV8Version = "149.2.0";
        rustyV8Targets = {
          "x86_64-linux" = "x86_64-unknown-linux-gnu";
          "aarch64-linux" = "aarch64-unknown-linux-gnu";
          "x86_64-darwin" = "x86_64-apple-darwin";
          "aarch64-darwin" = "aarch64-apple-darwin";
        };
        rustyV8Target = rustyV8Targets.${system};
        rustyV8 = pkgs.fetchurl {
          url = "https://github.com/denoland/rusty_v8/releases/download/v${rustyV8Version}/librusty_v8_simdutf_release_${rustyV8Target}.a.gz";
          sha256 =
            {
              "x86_64-linux" = "sha256-Uzfff0kIk7MAVpOZmFn7Pb24QPYsyTXJi/WcnL7kiVc=";
              "aarch64-linux" = "sha256-eWUL59b9TQH111GE1ZaUS9SS7XD5ruBheojHT7klTiY=";
              "x86_64-darwin" = "sha256-1yHm1WslC1CbXIjSxNI1mEJqqq7SRAVByO8BkS1Tk5s=";
              "aarch64-darwin" = "sha256-W8fpKRWz6LgAB8Tu1501Ky8L2FOn4rGocDVHV3UN7Jk=";
            }
            .${system};
        };

        botguardSrc = builtins.fetchGit {
          url = "https://codeberg.org/ThetaDev/rustypipe-botguard.git";
          rev = "f235ebc1ae89d0fa2c103ca705ff1a312b9be673";
          narHash = "sha256-5FtH5yfi9HSPQ7lnCu4KzQrIdBLLp1VvkuGgYrZt8Yw=";
        };

        botguardVendoredDeps = craneLib.vendorCargoDeps { src = botguardSrc; };

        # deno_core >= 0.400 no longer embeds its extension JS files into the binary, so revert that
        botguardVendoredDepsEmbedded = pkgs.runCommand "botguard-vendor-cargo-deps-embedded" { } ''
          cp -rL ${botguardVendoredDeps}/. $out/
          chmod -R u+w $out

          substituteInPlace "$out"/*/deno_core-0.403.0/extensions.rs \
            --replace-fail '__extension_include_js_files_inner!(mode=loaded, name=$name' '__extension_include_js_files_inner!(mode=included, name=$name' \
            --replace-fail '__extension_include_js_files_inner!(mode=loaded, $($rest)*)' '__extension_include_js_files_inner!(mode=included, $($rest)*)'

          substituteInPlace "$out/config.toml" \
            --replace-fail ${botguardVendoredDeps} $out
        '';

        rustypipe-botguard = craneLib.buildPackage {
          pname = "rustypipe-botguard";
          version = "0.1.2";

          src = botguardSrc;

          strictDeps = true;

          RUSTY_V8_ARCHIVE = rustyV8;

          buildFeatures = [ ];

          cargoVendorDir = botguardVendoredDepsEmbedded;

          nativeBuildInputs = with pkgs; [
            pkg-config
            makeWrapper
          ];

          buildInputs = with pkgs; [
            openssl
            libclang
          ];

          # disable tests
          doCheck = false;

          meta = {
            description = "Tool to run YouTube Botguard challenges and generate PO tokens";
            homepage = "https://codeberg.org/ThetaDev/rustypipe-botguard";
            license = lib.licenses.mit;
            platforms = lib.platforms.unix;
            mainProgram = "rustypipe-botguard";
          };
        };

        commonArgs = {
          pname = "music-syncer";
          version = "0.1.0";

          src = lib.fileset.toSource {
            root = unfilteredRoot;
            fileset = lib.fileset.unions [
              (craneLib.fileset.commonCargoSources unfilteredRoot)
              (lib.fileset.fileFilter (file: file.hasExt "md") unfilteredRoot)
              ./.sqlx
              ./migrations
            ];
          };

          strictDeps = true;

          SQLX_OFFLINE = "true";

          buildFeatures = [ ];

          nativeBuildInputs = with pkgs; [
            pkg-config
            # fontconfig
            makeWrapper
          ];

          buildInputs = with pkgs; [
            # openssl
            libclang

            # used to convert webm -> m4a
            ffmpeg-headless

            rustypipe-botguard
          ];
        };

        cargoArtifacts = craneLib.buildDepsOnly commonArgs;

        finalPackage = craneLib.buildPackage (
          commonArgs
          // {
            inherit cargoArtifacts;

            # disable tests
            doCheck = false;

            meta = {
              description = "lightweight music player";
              homepage = "https://github.com/techs-sus/music-player";
              license = pkgs.lib.licenses.asl20;
              maintainers = [
                {
                  name = "techs-sus";
                  github = "techs-sus";
                  githubId = 92276908;
                }
              ];
              platforms = pkgs.lib.platforms.unix;
              mainProgram = "music-syncer";
            };
          }
        );
      in
      {
        checks = {
          music-player = finalPackage;
        };

        packages.default = finalPackage;

        apps.default = flake-utils.lib.mkApp {
          drv = finalPackage;
        };

        apps.nix-update-rusty-v8 = {
          type = "app";
          program = toString (
            pkgs.writeShellScript "update-rusty-v8" ''
              set -euo pipefail

              function update() {
                sys=$1
                target=$2
                url="https://github.com/denoland/rusty_v8/releases/download/v${rustyV8Version}/librusty_v8_simdutf_release_$target.a.gz"
                hash=$(nix store prefetch-file --hash-type sha256 --json "$url" | ${pkgs.lib.getExe pkgs.jq} -r '.hash')
                sed -i "s|\"$sys\" = \"sha256-[^\"]*\";|\"$sys\" = \"$hash\";|" flake.nix
              }

              ${lib.concatStringsSep "\n" (
                lib.mapAttrsToList (sys: target: "update ${sys} ${target}") rustyV8Targets
              )}
            ''
          );
        };

        devShells.default = craneLib.devShell {
          checks = self.checks.${system};

          packages = with pkgs; [
            rust-analyzer
            libclang
          ];

          DATABASE_URL = "sqlite://dev.db";
        };

        formatter = pkgs.nixfmt-tree;
      }
    );
}
