# music-syncer

Fast playlist syncer written in safe Rust using YouTube's internal API
(Innertube) to sync playlists incredibly quickly.

Relies on the
[`feat/deobf-extractor`](https://codeberg.org/ThetaDev/rustypipe/src/branch/feat/deobf-extractor)
branch of [rustypipe](https://codeberg.org/ThetaDev/rustypipe) so there is no
cargo release.

## Usage

Try it with
[Nix flakes](https://nix.dev/manual/nix/2.34/command-ref/new-cli/nix3-flake):

```bash
nix run github:techs-sus/music-syncer -- <args>

# or use it like its installed in your shell

nix shell github:techs-sus/music-syncer

music-syncer <args>
```

Try with cargo (you need ffmpeg installed in your PATH, and may also need
rustypipe-botguard):

```bash
cargo install --locked --git https://github.com/techs-sus/music-syncer.git

music-syncer <args>
```

### Commands/arguments

All commands currently take in `-p <playlist-db-location>`. For example:
`~/Music/folder/name.db`.

> [!NOTE]
> You should manually create the parents of the folder containing the database
> with `mkdir -p` on Unix derivations, or alternatives to do so on Windows.

#### `init -p <playlist-db-location> -u <upstream-youtube-playlist-id>`

Initializes a playlist at `<playlist-db-location>` and sets its upstream to
`<upstream-youtube-playlist-id>`.

#### `sync -p <playlist-db-location>`

Synchronizes a local playlist at `<playlist-db-location>` with its upstream set
inside the database. Downloads all tracks and their thumbnails if not already
present.

> [!NOTE]
> This does not automatically recreate the playlist's m3u file. To do that, run
> the write-to-m3u command.

#### `write-to-m3u -p <playlist-db-location>`

> [!NOTE]
> This relies on metadata created by the sync command. Running this without
> running a sync command may lead to outdated info being printed to the output
> m3u file.

Writes an m3u playlist to `{folder}/{name}.m3u`. Provides positions and names of
tracks for music players. Without this, your playlist will look like very messy.
