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
use reader::Track;
use reqwest::{
	Client,
	header::{CONTENT_TYPE, ORIGIN, RANGE, REFERER, USER_AGENT},
};
use server::ytmusic::YouTubeMusicClient;
use tracing::{info, warn};

use crate::database::{Database, PoolConcurrencyOptions, TrackStatus};

// folder will be structured like so:
// - audio
// - thumbnail
// - playlist.db
/// A playlist on disk.
struct Playlist {
	name: String,
	folder: PathBuf,
	database: Database,
	client: YouTubeMusicClient,
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

		let folder = tokio::fs::canonicalize(folder).await?;

		let _ = tokio::fs::create_dir(folder.join("audio")).await;
		let _ = tokio::fs::create_dir(folder.join("thumbnail")).await;

		Ok(Self {
			database: Database::open(path.as_ref(), &concurrency_options.pool).await?,
			folder,
			client: YouTubeMusicClient::new(),
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

	/// Downloads the track's thumbnail and gives back the path to the thumbnail with the proper
	/// extension.
	async fn download_thumbnail(
		&self,
		video_id: &str,
		cover_url: Option<&str>,
		user_agent: String,
	) -> Result<Option<PathBuf>, Error> {
		let Some(cover_url) = cover_url else {
			return Ok(None);
		};

		let response = self
			.reqwest_client
			.get(cover_url)
			.header(USER_AGENT, user_agent)
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
					.with_extension(extension)
					.to_path_buf();

				tokio::fs::write(&final_path, response.bytes().await?).await?;

				return Ok(Some(final_path));
			}
		}
	}

	/// Downloads the track and gives back the audio_base_path with the proper extension and the user
	/// agent used to download it. The audio path returned will always be an m4a file.
	#[tracing::instrument(skip(self))]
	async fn download_single_track(&self, video_id: &str) -> Result<(PathBuf, String), Error> {
		let stream_info = self
			.client
			.get_stream(video_id)
			.await
			.map_err(Error::UpstreamGettingStreamInfo)?;

		let audio_path = self
			.folder
			.join("audio")
			.join(video_id)
			.with_extension(stream_info.format.extension());

		// if the file was already downloaded, don't refetch it
		match tokio::fs::try_exists(&audio_path).await {
			Ok(true) => {}

			_ => {
				let response = self
					.reqwest_client
					.get(&stream_info.url)
					.header(USER_AGENT, &stream_info.user_agent)
					.header(REFERER, "https://music.youtube.com/")
					.header(ORIGIN, "https://music.youtube.com")
					// this header is REQUIRED for near instant downloads
					.header(RANGE, "bytes=0-")
					.send()
					.await?;

				let start = std::time::Instant::now();
				tokio::fs::write(&audio_path, response.bytes().await?).await?;
				info!(
					"downloaded track in {:#?}",
					std::time::Instant::now() - start
				);
			}
		};

		let taggable_audio_path = metadata::ensure_audio_is_taggable(&audio_path).await?;

		tokio::fs::remove_file(&audio_path).await?;

		Ok((taggable_audio_path, stream_info.user_agent))
	}

	async fn sync_from_youtube_single_track(
		&self,
		playlist_ordered_position: i64,
		track: &Track,
		track_result: TrackStatus,
	) -> Result<(), Error> {
		// let playlist_ordered_position = playlist_ordered_position as i64;
		let youtube_video_id = track.id.key();

		if let TrackStatus::AlreadyExists(..) = track_result
			&& let Ok(true) = tokio::fs::try_exists(
				self
					.folder
					.join("audio")
					.join(&*youtube_video_id)
					.with_extension("m4a"),
			)
			.await
		{
			// update track position as it has changed in the upstream
			info!(
				"track id {} already exists, not fetching from youtube, updating track position",
				&youtube_video_id
			);

			self
				.database
				.update_track_position(playlist_ordered_position, &youtube_video_id)
				.await?;

			return Ok(());
		}

		info!("downloading {youtube_video_id:?}");

		let (resulting_audio_path, user_agent) = self.download_single_track(&youtube_video_id).await?;

		let thumbnail_path = self
			.download_thumbnail(
				&youtube_video_id,
				track.cover.as_ref().map(String::as_str),
				user_agent,
			)
			.await
			.unwrap_or(None);

		{
			// all database paths must be relative for portability
			let resulting_audio_path = pathdiff::diff_paths(&resulting_audio_path, &self.folder).unwrap();
			let thumbnail_path = thumbnail_path
				.as_ref()
				.map(|path| pathdiff::diff_paths(path, &self.folder));

			self
				.database
				.insert_or_update_track(
					&track.title,
					&*resulting_audio_path.to_string_lossy(),
					thumbnail_path
						.as_ref()
						.and_then(|maybe_path| maybe_path.as_ref().map(|path| path.to_string_lossy()))
						.as_deref(),
					playlist_ordered_position,
					&*youtube_video_id,
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

		let tracks = self
			.client
			.get_playlist_entries(&playlist_id)
			.await
			.map_err(Error::UpstreamGettingPlaylistEntries)?;

		let mut diff = self.database.diff_tracks(&tracks).await?;

		let mut successes: usize = 0;
		let mut failures: usize = 0;

		for res in futures::stream::iter(tracks.iter().enumerate().map(
			async |(playlist_ordered_position, track)| {
				let result = diff.get(&*track.id.key()).unwrap().clone();

				self
					.sync_from_youtube_single_track(
						// usize -> i64 is usually non overflowing for music playlists
						playlist_ordered_position as i64,
						track,
						result,
					)
					.await
					.map_err(|e| (track.id.key(), e))
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

					let id = diff.remove_entry(&*id).unwrap().0;
					diff.insert(id, TrackStatus::Error(Rc::new(error)));
				}
			}
		}

		let tracks = tracks
			.into_iter()
			.enumerate()
			.map(|(position, track)| (track.id.key().to_string(), (position as i64, track)))
			.collect::<HashMap<_, _>>();

		info!("Summary:");
		for (track_id, (incoming_position, track)) in tracks {
			let Some(result) = diff.get(&track_id) else {
				continue;
			};

			let track_identifier = format!("{} by {}", track.title, track.artist);

			match result {
				TrackStatus::AlreadyExists(old_position) => {
					// TODO: detect position changes
					if *old_position != incoming_position {
						info!("~@ pos {old_position} -> @pos {incoming_position}: {track_identifier}")
					}
				}
				TrackStatus::Added => {
					info!("+ @pos {incoming_position}: {track_identifier}")
				}
				TrackStatus::Removed => {
					info!("- @pos {incoming_position}: {track_identifier}")
				}
				TrackStatus::Error(error) => {
					warn!("! @pos {incoming_position}: {track_identifier}: {error:?}")
				}
			}
		}

		info!("Successfully synced {successes} tracks from youtube");
		warn!("Failed syncing {failures} tracks from youtube");

		Ok(())
	}

	async fn write_playlist_to_m3a(&self) -> Result<(), Error> {
		let mut buf = BufWriter::new(std::fs::File::create(
			self.folder.join(&self.name).with_extension("m3a"),
		)?);

		self.database.write_m3a_playlist(&mut buf).await?;

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

	/// Writes a playlist's m3a file.
	WriteToM3a {
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
		| Command::WriteToM3a {
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
		Command::WriteToM3a { .. } => {
			playlist
				.write_playlist_to_m3a()
				.await
				.expect("failed writing playlist to m3a");
			info!("successfully wrote playlist m3a");
		}
	};
}
