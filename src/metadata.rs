use lofty::{
	file::TaggedFileExt,
	tag::{Accessor, TagExt},
};
use rustypipe::model::TrackItem;
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
/// Files that are already m4a are returned as-is, as remuxing them in place is not possible with
/// ffmpeg and not required to tag them.
///
/// Input files that are not m4a are deleted as they waste storage space and cannot be tagged.
///
/// This function has a quite high performance penalty, as reencoding with ffmpeg is quite slow.
pub async fn ensure_audio_is_taggable<P: AsRef<Path>>(input_file: P) -> Result<PathBuf, Error> {
	let input_file = input_file.as_ref();
	let output_file = input_file.with_extension("m4a");

	// see the documentation comment
	if input_file.extension().is_some_and(|ext| ext == "m4a") {
		return Ok(output_file);
	}

	// linking against and using the ffmpeg-next api bloats the build
	let output = tokio::process::Command::new("ffmpeg")
		.args(["-y", "-i"])
		.args([input_file, &output_file])
		.output()
		.await
		.map_err(Error::FailedToRemuxAsM4a)?;

	if !output.status.success() {
		let stderr = String::from_utf8_lossy(&output.stderr);
		return Err(Error::FailedToRemuxAsM4a(std::io::Error::other(stderr)));
	}

	// remove old file as it is not a taggable m4a
	tokio::fs::remove_file(input_file).await?;

	Ok(output_file)
}

/// Embeds title, artist, album and optionally thumbnail metadata into the audio file at `audio_path`.
pub fn tag<P: AsRef<Path>>(
	track: &TrackItem,
	audio_path: P,
	thumbnail_path: Option<P>,
) -> Result<(), Error> {
	let audio_path = audio_path.as_ref();

	let mut tagged_file = lofty::probe::Probe::open(audio_path)
		.map_err(LoftyError::from)?
		.read()
		.map_err(LoftyError::from)?;

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

	tag.set_title(track.name.clone());
	if let Some(artist) = track.artists.first().map(|artist| &artist.name) {
		tag.set_artist(artist.clone());
	}
	if let Some(ref album_id) = track.album {
		tag.set_album(album_id.name.clone());
	}

	if let Some(thumbnail_path) = thumbnail_path.as_ref() {
		let mut cover = lofty::picture::Picture::from_reader(&mut BufReader::new(
			std::fs::File::options().read(true).open(thumbnail_path)?,
		))
		.map_err(LoftyError::from)?;

		cover.set_pic_type(lofty::picture::PictureType::CoverFront);

		tag.set_picture(0, cover);
	}

	tag
		.save_to_path(audio_path, lofty::config::WriteOptions::default())
		.map_err(LoftyError::from)?;

	Ok(())
}
