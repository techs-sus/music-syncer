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

        commonArgs = {
          pname = "music-syncer";
          version = "0.1.0";

          src = craneLib.cleanCargoSource ./.;
          strictDeps = true;

          buildFeatures = [ ];

          nativeBuildInputs = with pkgs; [
            pkg-config
            # fontconfig
            makeWrapper
          ];

          buildInputs = with pkgs; [
            # openssl
            ffmpeg-headless
            libclang
          ];
        };

        cargoArtifacts = craneLib.buildDepsOnly commonArgs;

        finalPackage = craneLib.buildPackage (
          commonArgs
          // {
            inherit cargoArtifacts;

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
