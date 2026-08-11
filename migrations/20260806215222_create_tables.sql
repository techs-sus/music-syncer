CREATE TABLE IF NOT EXISTS playlist_metadata(
	singleton_key integer primary key check (singleton_key = 1),
	youtube_playlist_id text
);

CREATE TABLE IF NOT EXISTS tracks(
  youtube_video_id text PRIMARY KEY,
	title text NOT NULL,

  audio_path text NOT NULL,
  thumbnail_path text,

	position integer NOT NULL
);
