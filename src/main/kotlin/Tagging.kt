package com.github.techs_sus

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.PngWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.StandardArtwork
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

object Tagging {
	suspend fun ensureAudioIsTaggable(inputFile: Path): Path =
		withContext(Dispatchers.IO) {
			// already taggable, avoid invoking FFmpeg and deleting the file
			if (inputFile.extension.equals(knownFinalAudioExtension, ignoreCase = true)) {
				return@withContext inputFile
			}

			val outputFile = inputFile.resolveSibling(
				"${inputFile.nameWithoutExtension}.$knownFinalAudioExtension"
			)

			val process = try {
				ProcessBuilder(
					"ffmpeg",
					"-y",
					"-i",
					inputFile.toString(),
					outputFile.toString()
				)
					.redirectErrorStream(false)
					.start()
			} catch (e: IOException) {
				throw FailedToRemuxAsM4a(e)
			}

			val stderr = process.errorStream.bufferedReader().use { it.readText() }
			val exitCode = process.waitFor()

			if (exitCode != 0) {
				throw FailedToRemuxAsM4a(
					IOException(stderr.ifBlank { "FFmpeg exited with code $exitCode" })
				)
			}

			// no need to keep the webm around
			inputFile.deleteExisting()

			outputFile
		}


	suspend fun ensureThumbnailIsUsableInTag(thumbnailPath: Path): Path = withContext(Dispatchers.IO) {
		if (thumbnailPath.extension == knownFinalThumbnailExtension) return@withContext thumbnailPath

		val image = ImmutableImage.loader().fromPath(thumbnailPath)
		val outputPath = image.output(
			// this is lossless compression
			PngWriter.MaxCompression,
			thumbnailPath.resolveSibling("${thumbnailPath.nameWithoutExtension}.$knownFinalThumbnailExtension")
		)

		thumbnailPath.deleteIfExists()

		return@withContext outputPath
	}

	suspend fun tagAudio(audioPath: Path, thumbnailPath: Path?, extractor: StreamExtractor) =
		withContext(Dispatchers.IO) {
			val audioFile = AudioFileIO.read(audioPath.toFile())
			val tag = audioFile.tagAndConvertOrCreateAndSetDefault

			// add the thumbnail if it exists
			if (thumbnailPath != null) {
				val artwork = StandardArtwork.createArtworkFromFile(thumbnailPath.toFile())

				tag.setField(artwork)
			}

			tag.setField(FieldKey.TITLE, extractor.name)
			tag.setField(FieldKey.ARTIST, extractor.uploaderName)
			tag.setField(FieldKey.YEAR, extractor.uploadDate?.offsetDateTime()?.year.toString())

			// writes the tag to disk
			audioFile.commit()
		}
}
