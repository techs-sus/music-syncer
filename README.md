# music-syncer

Fast playlist syncer written in Kotlin/JVM using YouTube's internal API
(Innertube) to sync playlists incredibly quickly.

Uses the actively maintained library
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) to get
stream urls.

## Usage

Try it with
[Nix flakes](https://nix.dev/manual/nix/2.34/command-ref/new-cli/nix3-flake):

```bash
nix run github:techs-sus/music-syncer -- <args>

# or use it like its installed in your shell

nix shell github:techs-sus/music-syncer

music-syncer-kotlin <args>
```

Releases are also available at
<https://github.com/techs-sus/music-syncer/releases>.

### Commands/arguments

All commands currently require `--playlist <playlist-db-location>`. `--playlist`
can also be substituted for `-p`. For example:
`music-syncer-kotlin --playlist ~/Music/folder/name.db`.

> [!NOTE]
> You should manually create the parents of the folder containing the database
> with `mkdir -p` on Unix derivations, or alternatives to do so on Windows.

#### `init --upstream <upstream-youtube-playlist-id>`

Initializes a playlist at `<playlist-db-location>` and sets its upstream to
`<upstream-youtube-playlist-id>`. You can also pass in `-u` instead of
`--upstream`.

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
