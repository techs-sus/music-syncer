mod database;
mod error;
mod metadata;

use std::{
	collections::HashMap,
	io::BufWriter,
	path::{Path, PathBuf},
	rc::Rc,
};

use clap::{Parser, value_parser};
use error::Error;
use futures::StreamExt;
use reqwest::{
	Client,
	header::{CONTENT_TYPE, ORIGIN, RANGE, REFERER, USER_AGENT},
};
use rustypipe::{
	client::{ClientType, RustyPipe},
	model::{AudioFormat, MusicPlaylist, TrackItem},
	param::StreamFilter,
};
use tracing::{info, warn};

use crate::database::{Database, PoolConcurrencyOptions, TrackStatus};

/// Latest ESR release of Firefox. Pulled from <https://www.whatismybrowser.com/guides/the-latest-user-agent/firefox>.
/// Currently only used for fetching thumbnails as tracks are fecthed with a user agent matching
/// their client_type.
pub const MODERN_FIREFOX_USER_AGENT: &str =
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";

// folder will be structured like so:
// - audio
// - thumbnail
// - playlist.db
/// A playlist on disk.
struct Playlist {
	name: String,
	folder: PathBuf,
	database: Database,
	client: RustyPipe,
	reqwest_client: reqwest::Client,

	concurrency_options: ConcurrencyOptions,
}

#[derive(clap::Args, Clone)]
struct ConcurrencyOptions {
	/// See [`PoolConcurrencyOptions`].
	#[clap(flatten)]
	pool: PoolConcurrencyOptions,

	/// How many futures should be polled at once.
	#[arg(long = "futures", default_value_t = 32, value_parser = value_parser!(u32).range(1..))]
	worker_futures: u32,
}

impl Playlist {
	async fn from_path<P: AsRef<Path>>(
		path: P,
		concurrency_options: ConcurrencyOptions,
	) -> Result<Self, Error> {
		let Some(folder) = path.as_ref().parent().map(Path::to_path_buf) else {
			return Err(Error::PlaylistHasNoParentFolder);
		};

		let mut client_builder = RustyPipe::builder();

		if let Some(cache_dir) = dirs::cache_dir() {
			client_builder = client_builder.storage_dir(cache_dir);
		}

		let client = client_builder.build()?;

		let folder = tokio::fs::canonicalize(folder).await?;

		let _ = tokio::fs::create_dir(folder.join("audio")).await;
		let _ = tokio::fs::create_dir(folder.join("thumbnail")).await;

		Ok(Self {
			database: Database::open(path.as_ref(), &concurrency_options.pool).await?,
			folder,
			client,
			reqwest_client: Client::new(),
			name: path
				.as_ref()
				.with_extension("")
				.file_name()
				.expect("playlist should have name")
				.to_string_lossy()
				.to_string(),

			concurrency_options,
		})
	}

	fn get_final_audio_path(&self, id: &str) -> PathBuf {
		self.folder.join("audio").join(id).with_extension("m4a")
	}

	/// Downloads the track's thumbnail and gives back the path to the thumbnail with the proper
	/// extension.
	async fn download_thumbnail(
		&self,
		video_id: &str,
		cover_url: Option<&str>,
	) -> Result<Option<PathBuf>, Error> {
		let Some(cover_url) = cover_url else {
			return Ok(None);
		};

		let response = self
			.reqwest_client
			.get(cover_url)
			.header(USER_AGENT, MODERN_FIREFOX_USER_AGENT)
			.header(REFERER, "https://music.youtube.com/")
			.send()
			.await?;

		match response
			.headers()
			.get(CONTENT_TYPE)
			.map(|value| value.to_str())
		{
			None | Some(Err(..)) => Ok(None),
			Some(Ok(content_type)) => {
				let Some(extension) = mime2ext::mime2ext(content_type) else {
					return Ok(None);
				};

				let final_path = self
					.folder
					.join("thumbnail")
					.join(video_id)
					.with_extension(extension);

				tokio::fs::write(&final_path, response.bytes().await?).await?;

				Ok(Some(final_path))
			}
		}
	}

	/// Downloads the track and gives back the audio_base_path with the proper extension.
	/// The audio path returned will always be an m4a file.
	#[tracing::instrument(skip(self))]
	async fn download_single_track(&self, video_id: &str) -> Result<PathBuf, Error> {
		let final_audio_path = self.get_final_audio_path(video_id);

		// if the file was already downloaded as an m4a, don't refetch it
		if let Ok(true) = tokio::fs::try_exists(&final_audio_path).await {
			return Ok(final_audio_path);
		}

		let player = self
			.client
			.query()
			.player_from_clients(video_id, &[ClientType::AndroidVr])
			.await?;

		let Some(stream) = player.select_audio_stream(&StreamFilter::default()) else {
			return Err(Error::UpstreamGettingAudioStream);
		};

		let Some(ref url) = stream.url else {
			return Err(Error::UpstreamGettingAudioStreamUrl);
		};

		let extension = match &stream.format {
			AudioFormat::M4a => "m4a",
			AudioFormat::Webm => "webm",

			_ => unreachable!(),
		};

		let audio_path = final_audio_path.with_extension(extension);

		let response = self
			.reqwest_client
			.get(url)
			.header(
				USER_AGENT,
				&*self.client.query().user_agent(player.client_type),
			)
			.header(REFERER, "https://music.youtube.com/")
			.header(ORIGIN, "https://music.youtube.com")
			// this header is REQUIRED for near instant downloads
			.header(RANGE, "bytes=0-")
			.send()
			.await?;

		let status = response.status();
		if !status.is_success() {
			return Err(Error::UpstreamAudioFetchFailed {
				video_id: video_id.to_string(),
				status,
			});
		}

		let start = std::time::Instant::now();
		tokio::fs::write(&audio_path, response.bytes().await?).await?;
		info!("downloaded track in {:#?}", start.elapsed());

		let taggable_audio_path = metadata::ensure_audio_is_taggable(&audio_path).await?;

		Ok(taggable_audio_path)
	}

	async fn sync_from_youtube_single_track(
		&self,
		playlist_ordered_position: i64,
		track: &TrackItem,
		track_result: TrackStatus,
	) -> Result<(), Error> {
		let youtube_video_id = &track.id;

		if let TrackStatus::Removed = track_result {
			self.database.remove_track(youtube_video_id).await?;
			return Ok(());
		};

		if let TrackStatus::AlreadyExists(..) = track_result
			&& let Ok(true) = tokio::fs::try_exists(self.get_final_audio_path(youtube_video_id)).await
		{
			// update track position as it has changed in the upstream
			info!(
				"track id {} already exists, not fetching from youtube, updating track position",
				&youtube_video_id
			);

			self
				.database
				.update_track_position(playlist_ordered_position, youtube_video_id)
				.await?;

			return Ok(());
		}

		info!("downloading {youtube_video_id:?}");

		// TODO: fetch thumbnail and audio in parallel
		let resulting_audio_path = self.download_single_track(youtube_video_id).await?;

		let thumbnail_path = self
			.download_thumbnail(
				youtube_video_id,
				track.cover.first().map(|cover| cover.url.as_str()),
			)
			.await
			.unwrap_or(None);

		{
			// all database paths must be relative for portability
			let relative_audio_path = pathdiff::diff_paths(&resulting_audio_path, &self.folder).unwrap();
			let relative_thumbnail_path = thumbnail_path
				.as_ref()
				.map(|path| pathdiff::diff_paths(path, &self.folder));

			self
				.database
				.insert_or_update_track(
					&track.name,
					&relative_audio_path.to_string_lossy(),
					relative_thumbnail_path
						.as_ref()
						.and_then(|maybe_path| maybe_path.as_ref().map(|path| path.to_string_lossy()))
						.as_deref(),
					playlist_ordered_position,
					youtube_video_id,
				)
				.await?;
		}

		metadata::tag(track, resulting_audio_path, thumbnail_path)?;

		Ok(())
	}

	/// Sync from YouTube Music.
	///
	/// # Errors
	/// - [`Error::NoUpstreamPlaylist`] if the playlist has no upstream target to sync from.
	/// - [`Error::UpstreamGettingPlaylistEntries`]
	async fn sync_from_youtube(&self) -> Result<(), Error> {
		let playlist_id = self
			.database
			.get_upstream()
			.await
			.map_err(|_| Error::NoUpstreamPlaylist)?;

		let MusicPlaylist { mut tracks, .. } = self.client.query().music_playlist(playlist_id).await?;

		tracks.extend_all(self.client.query()).await?;

		let tracks = tracks.items;

		let mut diff = self.database.diff_tracks(&tracks).await?;

		let mut successes: usize = 0;
		let mut failures: usize = 0;

		for res in futures::stream::iter(tracks.iter().enumerate().map(
			async |(playlist_ordered_position, track)| {
				let result = diff.get(&track.id).unwrap().clone();

				self
					.sync_from_youtube_single_track(
						// usize -> i64 is usually non overflowing on 64 bit platforms for music playlists
						playlist_ordered_position as i64,
						track,
						result,
					)
					.await
					.map_err(|e| (&track.id, e))
			},
		))
		.buffer_unordered(self.concurrency_options.worker_futures as usize)
		.collect::<Vec<_>>()
		.await
		{
			match res {
				Ok(..) => {
					successes += 1;
				}
				Err((id, error)) => {
					failures += 1;

					let (id, _) = diff.remove_entry(id.as_str()).unwrap();
					diff.insert(id, TrackStatus::Error(Rc::new(error)));
				}
			}
		}

		let tracks = tracks
			.into_iter()
			.enumerate()
			.map(|(position, track)| (track.id.to_string(), (position as i64, track)))
			.collect::<HashMap<_, _>>();

		info!("Summary:");
		for (track_id, (incoming_position, track)) in tracks {
			let Some(result) = diff.get(&track_id) else {
				continue;
			};

			let track_identifier = format!(
				"{} by {}",
				track.name,
				track
					.artists
					.first()
					.map_or("<None>", |artist| artist.name.as_str())
			);

			match result {
				TrackStatus::AlreadyExists(old_position) => {
					if *old_position != incoming_position {
						info!("~@ pos {old_position} -> @pos {incoming_position}: {track_identifier}");
					}
				}
				TrackStatus::Added => {
					info!("+ @pos {incoming_position}: {track_identifier}");
				}
				TrackStatus::Removed => {
					info!("- @pos {incoming_position}: {track_identifier}");
				}
				TrackStatus::Error(error) => {
					warn!("! @pos {incoming_position}: {track_identifier}: {error:?}");
				}
			}
		}

		info!("Successfully synced {successes} tracks from youtube");
		warn!("Failed syncing {failures} tracks from youtube");

		Ok(())
	}

	async fn write_playlist_to_m3u(&self) -> Result<(), Error> {
		let mut writer = BufWriter::new(std::fs::File::create(
			self.folder.join(&self.name).with_extension("m3u"),
		)?);

		self.database.write_m3u_playlist(&mut writer).await?;

		Ok(())
	}

	async fn set_upstream(&self, youtube_playlist_id: &str) -> Result<(), Error> {
		self.database.set_upstream(youtube_playlist_id).await?;
		Ok(())
	}
}

#[derive(clap::Args)]
struct PlaylistOptions {
	/// The sqlite database file used to sync.
	#[arg(short = 'p', long = "playlist")]
	playlist_db_path: PathBuf,
}

#[derive(clap::Subcommand)]
enum Command {
	/// Initializes a playlist file.
	Init {
		/// The upstream playlist to init this local one.
		#[arg(short = 'u', long = "upstream")]
		youtube_upstream_playlist: String,

		#[clap(flatten)]
		options: PlaylistOptions,
		#[clap(flatten)]
		concurrency_options: ConcurrencyOptions,
	},

	/// Syncs music from a playlist file and produces an m3u8 file.
	Sync {
		#[clap(flatten)]
		options: PlaylistOptions,
		#[clap(flatten)]
		concurrency_options: ConcurrencyOptions,
	},

	/// Writes a playlist's m3u file.
	WriteToM3u {
		#[clap(flatten)]
		options: PlaylistOptions,
		#[clap(flatten)]
		concurrency_options: ConcurrencyOptions,
	},
}

#[derive(clap::Parser)]
#[command(author, version, about, long_about = None)]
struct Args {
	#[command(subcommand)]
	command: Command,
}

#[tokio::main(flavor = "multi_thread")]
async fn main() {
	tracing_subscriber::fmt()
		.compact()
		.with_target(false)
		.without_time()
		.with_level(true)
		.init();

	let args = Args::parse();

	let (playlist_options, concurrency_options) = match &args.command {
		Command::Init {
			options,
			concurrency_options,
			..
		}
		| Command::Sync {
			options,
			concurrency_options,
		}
		| Command::WriteToM3u {
			options,
			concurrency_options,
		} => (options, concurrency_options),
	};

	let playlist = Playlist::from_path(
		&*playlist_options.playlist_db_path,
		concurrency_options.clone(),
	)
	.await
	.expect("failed opening playlist");

	match args.command {
		Command::Init {
			youtube_upstream_playlist,
			..
		} => {
			playlist
				.set_upstream(&youtube_upstream_playlist)
				.await
				.expect("failed setting playlist upstream");

			info!("successfully set playlist upstream to {youtube_upstream_playlist}");
		}
		Command::Sync { .. } => {
			playlist
				.sync_from_youtube()
				.await
				.expect("failed syncing from youtube");
			info!("successfully synced from youtube");
		}
		Command::WriteToM3u { .. } => {
			playlist
				.write_playlist_to_m3u()
				.await
				.expect("failed writing playlist to m3u");
			info!("successfully wrote playlist m3u");
		}
	};
}

#[doc(hidden)]
#[cfg(test)]
mod tests {
	use crate::{Playlist, database::PoolConcurrencyOptions};

	#[tokio::test]
	async fn known_good_with_audio() {
		tracing_subscriber::fmt().init();

		let _ = tokio::fs::remove_dir_all("/tmp/music-syncer-test").await;
		let _ = tokio::fs::create_dir("/tmp/music-syncer-test").await;
		let playlist = Playlist::from_path(
			"/tmp/music-syncer-test/test.db",
			crate::ConcurrencyOptions {
				pool: PoolConcurrencyOptions {
					min_connections: 1,
					max_connections: 1,
				},
				worker_futures: 1,
			},
		)
		.await
		.expect("failed making playlist");

		let audio_path = playlist
			.download_single_track("-2OpiWEdBYI")
			.await
			.expect("failed downloading track");

		let file_size = tokio::fs::metadata(&audio_path)
			.await
			.expect("failed reading downloaded track metadata")
			.len();

		assert!(
			file_size > 1024,
			"downloaded track is implausibly small: {file_size} bytes at {audio_path:?}"
		);
	}
}
