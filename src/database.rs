use std::{collections::HashMap, io::Write, path::Path, rc::Rc};

use crate::error::Error;
use clap::value_parser;
use reader::Track;
use sqlx::{
	AssertSqlSafe, Row, SqlitePool,
	sqlite::{SqliteConnectOptions, SqlitePoolOptions},
};

/// https://sqlite.org/pragma.html#pragma_application_id
pub const SQLITE_APPLICATION_ID: i32 = 0x7D8A4B83;

#[derive(clap::Args, Clone)]
pub struct PoolConcurrencyOptions {
	/// How many database connections are guaranteed to exist at any time.
	#[clap(long = "pool_min_connections", default_value_t = 8, value_parser = value_parser!(u32).range(1..))]
	min_connections: u32,

	/// How many database connections may be reached if database load is high.
	#[clap(long = "pool_max_connections", default_value_t = 16, value_parser = value_parser!(u32).range(1..))]
	max_connections: u32,
}

pub struct Database {
	pool: SqlitePool,
}

#[derive(Clone)]
pub enum TrackStatus {
	/// incoming tracks have it and db also does
	/// does not guarantee that the audio_path exists
	AlreadyExists(/* position in database */ i64),
	/// only the database has it
	Removed,
	/// incoming tracks have it but db does not
	Added,

	/// the sync call failed, likely age restriction or unplayable
	Error(Rc<Error>),
}

// pub fn track_id_as_str<'track>(track: &'track Track) -> &'track str {
// 	match track.id {
// 		TrackId::Local(..) => unreachable!("kopuz's local features are unused"),
// 		TrackId::Server { ref item_id, .. } => item_id,
// 	}
// }

impl Database {
	pub async fn open<P: AsRef<Path>>(
		path: P,
		pool_concurrency_options: &PoolConcurrencyOptions,
	) -> Result<Self, Error> {
		let pool = SqlitePoolOptions::new()
			.min_connections(pool_concurrency_options.min_connections)
			.max_connections(pool_concurrency_options.max_connections)
			.connect_with(
				SqliteConnectOptions::new()
					.filename(path.as_ref())
					.create_if_missing(true),
			)
			.await?;

		let pragmas = sqlx::query!("PRAGMA application_id;")
			.fetch_one(&pool)
			.await?;

		let application_id = pragmas.application_id.unwrap_or(0);

		// "set the 32-bit signed big-endian" (https://sqlite.org/pragma.html#pragma_application_id)
		match application_id as i32 {
			0 => {
				// mark the database as ours
				// cannot be statically done at compile time because sqlx doesn't support pragmas properly
				// this is safe because SQLITE_APPLICATION_ID is guaranteed to be an integer. and no user
				// input is involved with this query
				sqlx::query(AssertSqlSafe(format!(
					"PRAGMA application_id = {SQLITE_APPLICATION_ID}"
				)))
				.execute(&pool)
				.await?;

				sqlx::migrate!("./migrations")
					.run(&mut pool.acquire().await?)
					.await?;
			}

			SQLITE_APPLICATION_ID => {
				// no migrations, so don't do anything
			}

			// another application set a unique application_id, close and error so we don't nuke any data
			_ => {
				pool.close().await;

				return Err(Error::NotOurMusicDatabase);
			}
		}

		Ok(Self { pool })
	}

	pub async fn write_m3a_playlist(&self, writer: &mut impl Write) -> Result<(), Error> {
		let tracks = sqlx::query!("SELECT audio_path, title FROM tracks ORDER BY position ASC")
			.fetch_all(&self.pool)
			.await?;

		write!(writer, "#EXTM3U\n")?;

		for track in tracks.into_iter() {
			write!(writer, "#EXTINF:0,{}\n", track.title)?;
			// this should work because db should only store relative paths
			write!(writer, "{}\n", track.audio_path)?;
		}

		writer.flush()?;

		Ok(())
	}

	pub async fn set_upstream(&self, upstream_id: &str) -> Result<(), sqlx::Error> {
		sqlx::query!(
			"INSERT INTO playlist_metadata (singleton_key, youtube_playlist_id) VALUES (1, ?1) ON CONFLICT(singleton_key) DO UPDATE SET youtube_playlist_id = excluded.youtube_playlist_id",
			upstream_id
		).execute(&self.pool).await?;

		Ok(())
	}

	pub async fn get_upstream(&self) -> Result<String, sqlx::Error> {
		Ok(
			sqlx::query!("SELECT youtube_playlist_id as \"youtube_playlist_id!\" from playlist_metadata")
				.fetch_one(&self.pool)
				.await?
				.youtube_playlist_id,
		)
	}

	/// Returns a [`HashSet`] of the already existing tracks in the database.
	pub async fn diff_tracks(
		&self,
		incoming_tracks: &[Track],
	) -> Result<HashMap<String, TrackStatus>, sqlx::Error> {
		// acquire a connection from the pool
		// must use a transaction because temp table is per connection not per database
		let mut tx = self.pool.begin().await?;

		sqlx::query(
			"CREATE TEMP TABLE IF NOT EXISTS incoming_tracks(
				youtube_video_id text NOT NULL PRIMARY KEY
			);",
		)
		.execute(&mut *tx)
		.await?;

		let mut tracks = HashMap::with_capacity(incoming_tracks.len());

		for track in incoming_tracks {
			// sqlx has an automatic prepare cache, so no need to prepare here
			sqlx::query("INSERT OR IGNORE INTO incoming_tracks (youtube_video_id) VALUES (?1)")
				.bind(track.id.key())
				.execute(&mut *tx)
				.await?;

			tracks.insert(track.id.key().to_string(), TrackStatus::Added);
		}

		let already_existing_track_ids = sqlx::query(
			"SELECT s.youtube_video_id, s.position
				FROM tracks s
				JOIN incoming_tracks t ON t.youtube_video_id = s.youtube_video_id;
			",
		)
		.fetch_all(&mut *tx)
		.await?
		.into_iter()
		.map(|row| (row.get::<String, _>(0), row.get::<i64, _>(1)));

		for (key, position) in already_existing_track_ids {
			tracks.insert(key, TrackStatus::AlreadyExists(position));
		}

		// incoming_tracks has it, tracks doesn't
		let new_track_ids = sqlx::query(
			"
			SELECT t.youtube_video_id FROM incoming_tracks t
			LEFT JOIN tracks s
				ON s.youtube_video_id = t.youtube_video_id
			WHERE s.youtube_video_id IS NULL;
			",
		)
		.fetch_all(&mut *tx)
		.await?
		.into_iter()
		.map(|row| row.get::<String, _>(0));

		for key in new_track_ids {
			tracks.insert(key, TrackStatus::Added);
		}

		// tracks has it, incoming_tracks doesn't
		let removed_track_ids = sqlx::query(
			"
			SELECT s.youtube_video_id FROM tracks s
			LEFT JOIN tracks t
				ON t.youtube_video_id = s.youtube_video_id
			WHERE t.youtube_video_id IS NULL;
			",
		)
		.fetch_all(&mut *tx)
		.await?
		.into_iter()
		.map(|row| row.get::<String, _>(0));

		for key in removed_track_ids {
			tracks.insert(key, TrackStatus::Removed);
		}

		// clean temporary table to increase performance
		sqlx::query("DELETE FROM incoming_tracks;")
			.execute(&mut *tx)
			.await?;

		tx.commit().await?;

		Ok(tracks)
	}

	pub async fn insert_or_update_track(
		&self,
		title: &str,
		audio_path: &str,
		thumbnail_path: Option<&str>,
		position: i64,
		youtube_video_id: &str,
	) -> Result<(), sqlx::Error> {
		sqlx::query!(
			"
			INSERT INTO tracks (title, audio_path, thumbnail_path, position, youtube_video_id) VALUES (?1, ?2, ?3, ?4, ?5)
			ON CONFLICT (youtube_video_id) DO UPDATE SET
				title = excluded.title,
				audio_path = excluded.audio_path,
				thumbnail_path = excluded.thumbnail_path,
				position = excluded.position
			", title, audio_path, thumbnail_path, position, youtube_video_id
		).execute(&self.pool).await?;

		Ok(())
	}

	pub async fn remove_track(&self, youtube_video_id: &str) -> Result<(), sqlx::Error> {
		sqlx::query!(
			"DELETE FROM tracks WHERE youtube_video_id = ?1",
			youtube_video_id
		)
		.execute(&self.pool)
		.await?;

		Ok(())
	}

	#[tracing::instrument(skip(self))]
	pub async fn update_track_position(
		&self,
		position: i64,
		youtube_video_id: &str,
	) -> Result<(), sqlx::Error> {
		sqlx::query!(
			"UPDATE tracks SET position = ?1 WHERE youtube_video_id = ?2",
			position,
			youtube_video_id
		)
		.execute(&self.pool)
		.await?;

		Ok(())
	}
}
