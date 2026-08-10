use lofty::{
	file::TaggedFileExt,
	tag::{Accessor, TagExt},
};
use reader::Track;
use tracing::warn;

use crate::error::{Error, LoftyError};
use std::{
	io::BufReader,
	path::{Path, PathBuf},
};

/// Ensure an audio file can be tagged by reencoding it as a widely supported m4a and giving back
/// the result. Note that the original path given is not deleted by this function, and it is up to
/// the caller to clean up the original path given.
///
/// This function has a quite high performance penalty, as reencoding with ffmpeg is quite slow.
pub async fn ensure_audio_is_taggable<P: AsRef<Path>>(input_file: P) -> Result<PathBuf, Error> {
	let input_file = input_file.as_ref();
	let output_file = input_file.with_extension("m4a");

	// linking against and using the ffmpeg-next api bloats the build
	tokio::process::Command::new("ffmpeg")
		.args(["-y", "-i"])
		.args([input_file, &output_file])
		.output()
		.await
		.map_err(Error::FailedToRemuxAsM4a)?;

	Ok(output_file)
}

/// Embeds title, artist, album and optionally thumbnail metadata into the audio file at `audio_path`.
pub fn tag<P: AsRef<Path>>(
	track: &Track,
	audio_path: P,
	thumbnail_path: Option<P>,
) -> Result<(), Error> {
	let audio_path = audio_path.as_ref();

	let mut tagged_file =
		lofty::probe::Probe::open(&audio_path).map_err(LoftyError::from)?.read().map_err(LoftyError::from)?;

	let tag = match tagged_file.primary_tag_mut() {
		Some(primary_tag) => primary_tag,
		None => {
			if let Some(first_tag) = tagged_file.first_tag_mut() {
				first_tag
			} else {
				let tag_type = tagged_file.primary_tag_type();

				warn!("no tags found, creating a new tag of type `{tag_type:?}`");
				tagged_file.insert_tag(lofty::tag::Tag::new(tag_type));

				tagged_file.primary_tag_mut().ok_or(Error::NoPrimaryTag)?
			}
		}
	};

	tag.set_title(track.title.clone());
	tag.set_artist(track.artist.clone());
	tag.set_album(track.album.clone());

	if let Some(thumbnail_path) = thumbnail_path.as_ref() {
		let mut cover = lofty::picture::Picture::from_reader(&mut BufReader::new(
			std::fs::File::options().read(true).open(thumbnail_path)?,
		))
		.map_err(LoftyError::from)?;

		cover.set_pic_type(lofty::picture::PictureType::CoverFront);

		tag.set_picture(0, cover);
	}

	tag.save_to_path(&audio_path, lofty::config::WriteOptions::default())
		.map_err(LoftyError::from)?;

	Ok(())
}
