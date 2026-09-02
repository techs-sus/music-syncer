package com.github.techs_sus

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.PngWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import me.tongfei.progressbar.ProgressBar
import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.OkHttpClient
import okhttp3.brotli.Brotli
import okhttp3.coroutines.executeAsync
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.StandardArtwork
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections.emptyList
import java.util.Properties
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo
import kotlin.io.path.writeBytes

private const val SQLITE_APPLICATION_ID = 0x7D8A4B83L

open class ProjectException(val string: String) : Exception(string)

class DatabaseIsNotOurs : ProjectException("database is not ours")
class NoUpstreamPlaylistId : ProjectException("no upstream playlist id")
class FailedFindingThumbnail : ProjectException("failed finding thumbnail")
class FailedFetchingThumbnail : ProjectException("failed fetching thumbnail")
class FailedDecodingMime : ProjectException("failed decoding mime type")
class FailedFetchingAudio : ProjectException("failed fetching audio")
class FailedFindingAudioStream : ProjectException("failed finding audio stream")
class FailedToRemuxAsM4a(exception: IOException) : ProjectException("failed to remux as m4a: $exception")

fun <T : InfoItem> ListExtractor<T>.asIterator(): Iterator<T> {
	return iterator {
		var page = this@asIterator.initialPage
		yieldAll(page.items)

		while (page.hasNextPage()) {
			page = this@asIterator.getPage(page.nextPage)
			yieldAll(page.items)
		}
	}
}

class Playlist(
	private val service: YoutubeService = ServiceList.YouTube,
	private val http: OkHttpClient = OkHttpClient().newBuilder().addInterceptor(
		CompressionInterceptor(
			Brotli,
			Gzip
		)
	).build(),

	private val database: Database,
	private val driver: SqlDriver,
	val name: String,
	val folder: Path,
) {
	fun close() {
		http.dispatcher.executorService.shutdown()
		http.connectionPool.evictAll()
		http.cache?.close()
		driver.close()
	}

	private val audioFolder = folder.resolve("audio")
	private val thumbnailFolder = folder.resolve("thumbnail")
	private val knownFinalAudioExtension = "m4a"
	private val knownFinalThumbnailExtension = "png"

	suspend fun setYoutubeUpstream(upstreamPlaylistId: String) {
		database.playlistMetadataQueries.setUpstreamPlaylistId(youtube_playlist_id = upstreamPlaylistId).await()
	}

	// Returns the path that the thumbnail was downloaded to.
	private suspend fun syncSingleTrackThumbnail(id: String, url: String): Path {
		listOf(knownFinalThumbnailExtension, "jpg", "webp").forEach {
			val path = thumbnailFolder.resolve("$id.$it")
			if (path.exists()) return path
		}

		val response =
			http.newCall(
				okhttp3.Request.Builder()
					.url(url)
					.header("User-Agent", DownloaderImpl.USER_AGENT)
					.header("Referer", "https://music.youtube.com/").build()
			)
				.executeAsync()

		if (!response.isSuccessful) throw FailedFetchingThumbnail()

		val fileExtension = when (response.header("content-type")) {
			"image/png" -> "png"
			"image/jpeg" -> "jpg"
			"image/webp" -> "webp"

			else -> throw FailedDecodingMime()
		}

		return withContext(Dispatchers.IO) {
			val path = thumbnailFolder.resolve("$id.$fileExtension")
			path.writeBytes(
				response.body.bytes(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.SYNC, StandardOpenOption.TRUNCATE_EXISTING
			)
			response.close()
			return@withContext path
		}
	}

	// Returns the path that the audio was downloaded to.
	private suspend fun syncSingleTrackAudio(id: String, url: String): Path {
		val knownFinalM4aPath = audioFolder.resolve("$id.$knownFinalAudioExtension")
		if (knownFinalM4aPath.exists()) return knownFinalM4aPath

		val response =
			http.newCall(
				okhttp3.Request.Builder()
					.url(url)
					.header("User-Agent", DownloaderImpl.USER_AGENT)
					// makes downloads way faster
					.header("Range", "bytes=0-")
					.build()
			)
				.executeAsync()

		if (!response.isSuccessful) throw FailedFetchingAudio()

		val fileExtension = when (response.header("content-type")) {
			"audio/webm" -> "webm"
			"audio/mp4" -> "m4a"

			else -> throw FailedDecodingMime()
		}

		return withContext(Dispatchers.IO) {
			val probablyNotGoodPath = audioFolder.resolve("$id.$fileExtension")
			probablyNotGoodPath.writeBytes(
				response.body.bytes(),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.SYNC, StandardOpenOption.TRUNCATE_EXISTING
			)
			response.close()
			return@withContext probablyNotGoodPath
		}
	}

	private suspend fun ensureAudioIsTaggable(inputFile: Path): Path =
		withContext(Dispatchers.IO) {
			val outputFile = inputFile.resolveSibling(
				"${inputFile.nameWithoutExtension}.m4a"
			)

			// already taggable, avoid invoking FFmpeg and deleting the file
			if (inputFile.extension.equals("m4a", ignoreCase = true)) {
				return@withContext outputFile
			}

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

	private suspend fun ensureThumbnailIsPng(inputFile: Path): Path = withContext(Dispatchers.IO) {
		if (inputFile.extension == "png") return@withContext inputFile

		val image = ImmutableImage.loader().fromPath(inputFile)
		val outputPath = image.output(
			PngWriter.NoCompression,
			thumbnailFolder.resolve("${inputFile.nameWithoutExtension}.png")
		)

		inputFile.deleteIfExists()

		return@withContext outputPath
	}

	private suspend fun tagAudio(audioPath: Path, thumbnailPath: Path?, extractor: StreamExtractor) =
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

	private suspend fun syncSingleTrackFromUpstream(id: String, position: Int) = coroutineScope {
		val streamExtractor = service.getStreamExtractor(service.streamLHFactory.fromId(id))

		withContext(Dispatchers.IO) {
			streamExtractor.fetchPage()
		}

		val bestThumbnail = streamExtractor.thumbnails.maxByOrNull { it.width * it.height }
			?: throw FailedFindingThumbnail()

		val thumbnailPathLazy = async(Dispatchers.IO) {
			runCatching { ensureThumbnailIsPng(syncSingleTrackThumbnail(id = id, url = bestThumbnail.url)) }.getOrNull()
		}

		val bestAudioStream = streamExtractor.audioStreams.maxByOrNull { it.bitrate } ?: throw FailedFindingAudioStream()
		if (!bestAudioStream.isUrl) throw FailedFindingAudioStream()

		val audioPathLazy =
			async(Dispatchers.IO) { ensureAudioIsTaggable(syncSingleTrackAudio(id = id, url = bestAudioStream.content)) }

		val audioPath = audioPathLazy.await()
		val thumbnailPath = thumbnailPathLazy.await()

		tagAudio(audioPath = audioPath, thumbnailPath = thumbnailPath, extractor = streamExtractor)

		withContext(Dispatchers.IO) {
			database.trackQueries.insertOrUpdate(
				title = streamExtractor.name,
				audio_path = audioPath.relativeTo(folder).toString(),
				thumbnail_path = thumbnailPath?.relativeTo(folder).toString(),
				position = position.toLong(),
				youtube_video_id = id,
			).await()
		}
	}

	suspend fun syncFromUpstream() = coroutineScope {
		val upstream = withContext(Dispatchers.IO) {
			database.playlistMetadataQueries.getUpstreamPlaylistId().executeAsOneOrNull()?.youtube_playlist_id
		} ?: throw NoUpstreamPlaylistId()

		data class Stream(val position: Int, val title: String)

		val extractor = service.getPlaylistExtractor(upstream, emptyList(), "")

		// fetch the page so that we can get streamCount
		withContext(Dispatchers.IO) {
			extractor.fetchPage()
		}

		val streamCount = extractor.streamCount.toInt()

		val upstreamIdSet = HashMap<String, Stream>(if (streamCount > 0) streamCount else 16)

		withContext(Dispatchers.IO) {
			// ensure no leftover tracks are in the incoming
			database.incomingTrackQueries.clear().await()

			extractor.asIterator().withIndex().forEach { (position, item) ->
				val videoId = service.streamLHFactory.getId(item.url)
				upstreamIdSet[videoId] = Stream(position = position, title = item.name)

				database.incomingTrackQueries.insertOrUpdate(youtube_video_id = videoId, position = position.toLong())
					.await()
			}

			val addedTracks = database.trackQueries.selectTracksOnlyInIncoming().executeAsList().sortedBy { it.position }
			addedTracks.forEach {
				println("+ track ${it.youtube_video_id} was added at position ${it.position}, as it exists in the upstream")
			}

			val deletedTracks =
				database.trackQueries.deleteTracksAbsentFromIncoming().executeAsList().sortedBy { it.position }
			deletedTracks.forEach {
				println("- track ${it.youtube_video_id} was deleted locally at position ${it.position}, as it does not exist in the upstream")
			}

			// don't leave any leftovers
			database.incomingTrackQueries.clear().await()

			// handle position updates for existing tracks without re-downloading
			val existingTracks = database.trackQueries.selectIdsAndPositionsAscending().executeAsList()
			val existingPosMap = existingTracks.associateBy { it.youtube_video_id }
			upstreamIdSet.forEach { (incomingId, incoming) ->
				val existing = existingPosMap[incomingId] ?: return@forEach

				if (existing.position.toInt() != incoming.position) {
					println("~ track $incomingId moved from position ${existing.position} to ${incoming.position}")

					database.trackQueries.updatePosition(
						position = incoming.position.toLong(),
						youtube_video_id = incomingId
					).await()
				} else {
					println("~ track $incomingId already exists locally at position ${existing.position}")
				}
			}
		}

		ProgressBar("Syncing", streamCount.toLong()).use { progressBar ->
			// always call sync on tracks in the upstream
			// why? audio_path and/or thumbnail_path may have been deleted
			// this lets us reify those values if they were deleted
			withContext(Dispatchers.IO.limitedParallelism(8, "syncWorkerDispatcher")) {
				coroutineScope {
					upstreamIdSet.forEach { (id, track) ->
						launch {
							syncSingleTrackFromUpstream(id = id, position = track.position)
							progressBar.step()
						}
					}
				}
			}

			progressBar.stepTo(streamCount.toLong())
		}

		return@coroutineScope
	}

	suspend fun writeToM3u(path: Path?) {
		val path = path ?: folder.resolve("$name.m3u")

		val bufferedWriter = path.toFile().bufferedWriter()
		val query = database.trackQueries.getPathAndTitlesAscending().executeAsList()

		withContext(Dispatchers.IO) {
			bufferedWriter.write("#EXTM3U\n")

			query.forEach {
				bufferedWriter.write("#EXTINF:0,${it.title}\n")
				bufferedWriter.write("${it.audio_path}\n")
			}

			bufferedWriter.close()
		}
	}

	companion object {
		suspend fun createFromPath(path: Path): Playlist {
			val (database, driver) = createDatabaseFromPath(path)
			val playlist = Playlist(
				database = database, driver = driver, name = path.nameWithoutExtension, folder = path.parent
			)

			withContext(Dispatchers.IO) {
				runCatching { Files.createDirectory(playlist.thumbnailFolder) }
				runCatching { Files.createDirectory(playlist.audioFolder) }
			}

			return playlist
		}

		private suspend fun createDatabaseFromPath(path: Path): Pair<Database, SqlDriver> {
			val path = path.toAbsolutePath().normalize()

			// this runs our migrations for us
			val driver: SqlDriver = JdbcSqliteDriver(
				"jdbc:sqlite:file:$path?mode=rwc", Properties(), Database.Schema
			)

			val database = Database(driver)

			val applicationId =
				driver.executeQuery(
					null,
					"PRAGMA application_id;",
					mapper = { cursor -> QueryResult.Value(cursor.getLong(0)) },
					0
				).await()

			when (applicationId) {
				null, 0L -> {
					// mark it as our own, get back the application_id
					driver.execute(
						null,
						"PRAGMA application_id = ${SQLITE_APPLICATION_ID};",
						parameters = 0,
					).await()
				}

				SQLITE_APPLICATION_ID -> {
					// already ours
				}

				// not ours
				else -> throw DatabaseIsNotOurs()
			}

			return Pair(database, driver)
		}
	}
}
