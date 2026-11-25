{
  lib,
  stdenv,
  java,
  gradle,
  buildGradleApplication,
  ffmpeg,
  patchelf,
  removeReferencesTo,
  bzip2,
  brotli,
  freetype,
  libpng,
  zlib,
  ...
}:
(buildGradleApplication {
  pname = "music-syncer-kotlin";
  version = "git";
  jdk = java;

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

  repositories = [
    "https://plugins.gradle.org/m2/"
    "https://maven.google.com"
    "https://repo1.maven.org/maven2/"
    "https://jitpack.io"
  ];

  buildTask = ":nativeCompile";

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
    nativeBuildInputs = old.nativeBuildInputs ++ [
      patchelf
      removeReferencesTo
    ];

    buildInputs = old.buildInputs or [ ] ++ [
      bzip2
      brotli
      freetype
      libpng
      zlib
      stdenv.cc.cc.lib
    ];

    # graal native image emits required JDK shared libraries (such as AWT) alongside the executable
    # libawt_xawt.so pulls X11 and our app is headless, so we skip it
    installPhase = ''
      install -Dm755 \
      	build/native/nativeCompile/music-syncer-kotlin \
      		$out/bin/music-syncer-kotlin

      mkdir -p $out/lib
      for lib in build/native/nativeCompile/*.so; do
        name="$(basename "$lib")"
        [ "$name" = "libawt_xawt.so" ] && continue
        install -Dm644 "$lib" "$out/lib/$name"
      done
    '';

    # the copied JDK libraries will likely have RPATHs pointing into the graalvm store
    # replace them with $out/lib references
    postFixup = ''
      rpath="$out/lib:${
        lib.makeLibraryPath [
          bzip2
          brotli
          freetype
          libpng
          zlib
          stdenv.cc.cc.lib
        ]
      }"
      for lib in $out/lib/*.so; do
        patchelf --set-rpath "$rpath" "$lib"
        remove-references-to -t ${java} "$lib" || true
      done

      wrapProgram $out/bin/music-syncer-kotlin \
        --prefix PATH : "${lib.getBin ffmpeg}/bin" \
        --prefix LD_LIBRARY_PATH : "$out/lib"
    '';
  })
