# music-syncer

Fast playlist syncer written in Kotlin/JVM using YouTube's internal API
(Innertube) to sync playlists incredibly quickly.

Uses the actively maintained library
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) to get
stream urls.

Demo:
[![asciicast](https://asciinema.org/a/AU3l26RxAqslgQdZ.svg)](https://asciinema.org/a/AU3l26RxAqslgQdZ)

## Usage

Try it with
[Nix flakes](https://nix.dev/manual/nix/2.34/command-ref/new-cli/nix3-flake):

```bash
nix run github:techs-sus/music-syncer -- <args>

# or use it like its installed in your shell

nix shell github:techs-sus/music-syncer

# please see the section Commands/Arguments
music-syncer-kotlin <args>
```

Releases are also available at
<https://github.com/techs-sus/music-syncer/releases> and require either the
`JAVA_HOME` environment variable set or a `java` binary on the PATH. In simpler
terms, a Java installation is required to use the releases. You also need ffmpeg
properly available on the PATH or else audio remuxing will not work.

To run the releases, download the release artifact ending in a ".tar" or ".zip".
Extract the release artifact to a folder. This will contain two folders, `bin`
and `lib`.

Navigate to `bin` in a terminal and run `music-syncer-kotlin` on Linux/Mac/Unix
or `music-syncer-kotlin.bat` on Windows. The arguments are specified below.

### Commands/arguments

All commands currently require `--path <playlist-db-location>`. `--path` can
also be substituted for `-p`. For example:
`music-syncer-kotlin --path ~/Music/folder/name.db`.

> [!NOTE]
> You should manually create the parents of the folder containing the database
> with `mkdir -p` on Unix derivations, or alternatives to do so on Windows.

#### `init --upstream <upstream-youtube-playlist-id>`

Initializes a playlist at `<playlist-db-location>` and sets its upstream to
`<upstream-youtube-playlist-id>`. You can also pass in `-u` instead of
`--upstream`.

> [!NOTE]
> It may fail the first time saying SQLITE_BUSY, if so, you should run it again
> and it will work. This will be fixed soon.

**You must give a playlist id, and not a link.**

> How do I get a playlist id?

Given a link `https://music.youtube.com/playlist?list=VALUE`, the YouTube
playlist id would be `VALUE`.

#### `sync`

Synchronizes a local playlist at `<playlist-db-location>` with its upstream set
inside the database. Downloads all tracks and their thumbnails if not already
present.

> [!NOTE]
> This does not automatically recreate the playlist's m3u file. To do that, run
> the write-to-m3u command.

#### `write-to-m3u --m3u-path <optional-m3u-path>`

> [!NOTE]
> This relies on metadata created by the sync command. Running this without
> running a sync command may lead to outdated info being printed to the output
> m3u file.

Writes an m3u playlist to `<optional-m3u-path>`, defaulting to
`{folder}/{name}.m3u` if not given. Provides the positions and human readable
names of tracks for music players. Without this, your playlist will look very
messy.
