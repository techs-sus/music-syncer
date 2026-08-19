#[derive(thiserror::Error, Debug)]
pub enum LoftyError {
	#[error("allocation error: {0}")]
	Allocation(#[from] lofty::error::AllocationError),
	#[error("fake tag error: {0}")]
	FakeTag(#[from] lofty::error::FakeTagError),
	#[error("file encoding error: {0}")]
	FileEncoding(#[from] lofty::error::FileEncodingError),
	#[error("file parse error: {0}")]
	FileParse(#[from] lofty::error::FileParseError),
	#[error("not enough data error: {0}")]
	NotEnoughData(#[from] lofty::error::NotEnoughDataError),
	#[error("size mismatch error: {0}")]
	SizeMismatch(#[from] lofty::error::SizeMismatchError),
	#[error("tag encoding error: {0}")]
	TagEncoding(#[from] lofty::error::TagEncodingError),
	#[error("tag parse error: {0}")]
	TagParse(#[from] lofty::error::TagParseError),
	#[error("text encoding error: {0}")]
	TextEncoding(#[from] lofty::error::TextEncodingError),
	#[error("text decoding error: {0}")]
	TextDecoding(#[from] lofty::error::TextDecodingError),
	#[error("too much data error: {0}")]
	TooMuchData(#[from] lofty::error::TooMuchDataError),
	#[error("unknown format error: {0}")]
	UnknownFormat(#[from] lofty::error::UnknownFormatError),
	#[error("unsupported tag error: {0}")]
	UnsupportedTag(#[from] lofty::error::UnsupportedTagError),
	#[error("picture parse error: {0}")]
	PictureParse(#[from] lofty::picture::error::PictureParseError),
}

#[derive(thiserror::Error, Debug)]
pub enum Error {
	#[error("sqlx sqlite error: {0}")]
	SqlxSqlite(#[from] sqlx::Error),

	#[error("sqlx sqlite migration error: {0}")]
	SqlxMigrationSqlite(#[from] sqlx::migrate::MigrateError),

	#[error("reqwest error: {0}")]
	Reqwest(#[from] reqwest::Error),

	#[error("tokio io error: {0}")]
	Io(#[from] tokio::io::Error),

	#[error("lofty error: {0}")]
	Lofty(#[from] LoftyError),

	#[error("sqlite schema.application_id != MAGIC")]
	NotOurMusicDatabase,

	#[error("there is no upstream playlist for this playlist")]
	NoUpstreamPlaylist,

	#[error("failed to find or create a primary tag")]
	NoPrimaryTag,

	#[error("the playlist has no parent folder for its tracks and thumbnails")]
	PlaylistHasNoParentFolder,

	#[error("failed getting a track's audio stream")]
	UpstreamGettingAudioStream,

	#[error("failed getting a track's audio stream's url")]
	UpstreamGettingAudioStreamUrl,

	#[error("failed to remux a track to its m4a counterpart with ffmpeg: {0}")]
	FailedToRemuxAsM4a(std::io::Error),

	#[error("failed fetching audio stream for {video_id}: unexpected HTTP status {status}")]
	UpstreamAudioFetchFailed {
		video_id: String,
		status: reqwest::StatusCode,
	},

	#[error("rustypipe error: {0}")]
	Rustypipe(#[from] rustypipe::error::Error),

	#[error("failed getting the file extension from the content-type header")]
	FailedToExtractMimeType,

	#[error("failed getting the content-type header")]
	FailedGettingContentTypeHeader,
}
