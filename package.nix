{
  lib,
  jdk25,
  gradle,
  buildGradleApplication,
  ffmpeg,
}:
(buildGradleApplication {
  pname = "music-syncer-kotlin";
  version = "git";
  jdk = jdk25;

  inherit gradle;

  src = lib.cleanSourceWith {
    src = lib.cleanSource ./.;
    filter =
      path: type:
      let
        ignore = builtins.elem (baseNameOf path);
      in
      !ignore [
        "package.nix"
        "gradlew.bat"
        "gradlew"
      ];
  };

  buildInputs = [ ffmpeg ];

  repositories = [
    "https://plugins.gradle.org/m2/"
    "https://maven.google.com"
    "https://repo1.maven.org/maven2/"
    "https://jitpack.io"
  ];

  meta = with lib; {
    description = "lightweight music player";
    homepage = "https://github.com/techs-sus/music-syncer";
    license = licenses.asl20;
    maintainers = [
      {
        name = "techs-sus";
        github = "techs-sus";
        githubId = 92276908;
      }
    ];
    platforms = platforms.unix;
    mainProgram = "music-syncer-kotlin";
  };
}).overrideAttrs
  (old: {
    postFixup = old.postFixup + ''
      wrapProgram $out/bin/music-syncer-kotlin --prefix PATH : "${lib.getBin ffmpeg}/bin"
    '';
  })
