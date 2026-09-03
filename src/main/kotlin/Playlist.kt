package com.github.techs_sus

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.github.ajalt.mordant.animation.coroutines.animateInCoroutine
import com.github.ajalt.mordant.animation.progress.MultiProgressBarAnimation
import com.github.ajalt.mordant.animation.progress.advance
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Spinner
import com.github.ajalt.mordant.widgets.progress.percentage
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.progressBarContextLayout
import com.github.ajalt.mordant.widgets.progress.spinner
import com.github.ajalt.mordant.widgets.progress.text
import com.github.ajalt.mordant.widgets.progress.timeElapsed
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.PngWriter
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqldelight.Sqlx4kSqldelightDriver
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.OkHttpClient
import okhttp3.brotli.Brotli
import okhttp3.coroutines.executeAsync
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.StandardArtwork
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections.emptyList
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

private enum class ProgressBarStatus {
	Syncing,
	Synced
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
	private val terminal: Terminal = Terminal(),
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

	private data class LocalTrackInfo(
		val thumbnailPath: Path?,
		val audioPath: Path?,
	)

	private data class MinimalStream(
		val position: Int,
		val title: String,
		val thumbnails: List<Image>,
		var alreadyExistsInDatabase: Boolean,
	)

	private suspend fun getExistingFilesForTrack(id: String): LocalTrackInfo = withContext(Dispatchers.IO) {
		var thumbnailPath: Path? = null
		var audioPath: Path? = null

		// knownFinalThumbnailExtension should always be the highest priority
		listOf("webp", "jpg", knownFinalThumbnailExtension).forEach {
			val path = thumbnailFolder.resolve("$id.$it")
			if (path.exists()) thumbnailPath = path
		}

		val knownFinalM4aPath = audioFolder.resolve("$id.$knownFinalAudioExtension")
		if (knownFinalM4aPath.exists()) audioPath = knownFinalM4aPath

		return@withContext LocalTrackInfo(
			thumbnailPath = thumbnailPath,
			audioPath = audioPath
		)
	}

	suspend fun setYoutubeUpstream(upstreamPlaylistId: String) = withContext(Dispatchers.IO) {
		database.playlistMetadataQueries.setUpstreamPlaylistId(youtube_playlist_id = upstreamPlaylistId)
	}

	// Returns the path that the thumbnail was downloaded to.
	private suspend fun syncSingleTrackThumbnail(id: String, url: String): Path = withContext(Dispatchers.IO) {
		val response =
			http.newCall(
				okhttp3.Request.Builder()
					.url(url)
					.header("User-Agent", DownloaderImpl.USER_AGENT)
					.header("Referer", "https://music.youtube.com/").build()
			)
				.executeAsync()

		response.use {
			if (!it.isSuccessful) throw FailedFetchingThumbnail()

			val fileExtension = when (it.header("content-type")) {
				"image/png" -> "png"
				"image/jpeg" -> "jpg"
				"image/webp" -> "webp"

				else -> throw FailedDecodingMime()
			}

			val path = thumbnailFolder.resolve("$id.$fileExtension")
			path.writeBytes(
				it.body.bytes(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.SYNC, StandardOpenOption.TRUNCATE_EXISTING
			)
			return@withContext path
		}
	}

	// Returns the path that the audio was downloaded to.
	private suspend fun syncSingleTrackAudio(id: String, url: String): Path = withContext(Dispatchers.IO) {
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

		response.use {
			if (!it.isSuccessful) throw FailedFetchingAudio()

			val fileExtension = when (it.header("content-type")) {
				"audio/webm" -> "webm"
				"audio/mp4" -> "m4a"

				else -> throw FailedDecodingMime()
			}

			val downloadedAudioPath = audioFolder.resolve("$id.$fileExtension")
			downloadedAudioPath.writeBytes(
				it.body.bytes(),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.SYNC, StandardOpenOption.TRUNCATE_EXISTING
			)
			return@withContext downloadedAudioPath
		}
	}

	private suspend fun ensureAudioIsTaggable(inputFile: Path): Path =
		withContext(Dispatchers.IO) {
			// already taggable, avoid invoking FFmpeg and deleting the file
			if (inputFile.extension.equals("m4a", ignoreCase = true)) {
				return@withContext inputFile
			}

			val outputFile = inputFile.resolveSibling(
				"${inputFile.nameWithoutExtension}.m4a"
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

	private suspend fun syncSingleTrackFromUpstream(
		id: String,

		stream: MinimalStream,
	): Unit =
		coroutineScope {
			val existingTrackFiles = getExistingFilesForTrack(id)

			// if this track is already fully synced, do not call the YouTube api
			if (existingTrackFiles.thumbnailPath != null && existingTrackFiles.audioPath != null && stream.alreadyExistsInDatabase) {
				return@coroutineScope
			}

			val streamExtractorLazy by lazy {
				async(Dispatchers.IO) {
					val extractor = service.getStreamExtractor(service.streamLHFactory.fromId(id))
					extractor.fetchPage()
					return@async extractor
				}
			}

			val thumbnailPathLazy = async(Dispatchers.IO) {
				if (existingTrackFiles.thumbnailPath !== null) return@async existingTrackFiles.thumbnailPath

				// try using the thumbnails from the minimalStream first
				// else use the streamExtractor's thumbnails
				// if those fail, throw an error
				val bestThumbnail = stream.thumbnails.maxByOrNull { it.width * it.height }
					?: streamExtractorLazy.await().thumbnails.maxByOrNull { it.width * it.height }
					?: throw FailedFindingThumbnail()

				runCatching { ensureThumbnailIsPng(syncSingleTrackThumbnail(id = id, url = bestThumbnail.url)) }.getOrNull()
			}

			val audioPathLazy =
				async(Dispatchers.IO) {
					if (existingTrackFiles.audioPath != null) {
						// if thumbnail is newly created but audio already exists, retag the audio
						if (existingTrackFiles.thumbnailPath == null) {
							tagAudio(
								audioPath = existingTrackFiles.audioPath,
								thumbnailPath = thumbnailPathLazy.await(),
								extractor = streamExtractorLazy.await()
							)
						}

						// both audioPath and thumbnailPath are not null, so that means we shouldn't waste time retagging
						return@async existingTrackFiles.audioPath
					}

					val bestAudioStream =
						streamExtractorLazy.await().audioStreams.maxByOrNull { it.bitrate } ?: throw FailedFindingAudioStream()
					if (!bestAudioStream.isUrl) throw FailedFindingAudioStream()

					val audioPath = ensureAudioIsTaggable(syncSingleTrackAudio(id = id, url = bestAudioStream.content))
					tagAudio(
						audioPath = audioPath,
						thumbnailPath = thumbnailPathLazy.await(),
						extractor = streamExtractorLazy.await()
					)

					return@async audioPath
				}

			val audioPath = audioPathLazy.await()
			val thumbnailPath = thumbnailPathLazy.await()

			withContext(Dispatchers.IO) {
				database.trackQueries.insertOrUpdate(
					title = stream.title,
					audio_path = audioPath.relativeTo(folder).toString(),
					thumbnail_path = thumbnailPath?.relativeTo(folder).toString(),
					position = stream.position.toLong(),
					youtube_video_id = id,
				)
			}
		}

	suspend fun syncFromUpstream() = coroutineScope {
		val upstream = withContext(Dispatchers.IO) {
			database.playlistMetadataQueries.getUpstreamPlaylistId().awaitAsOneOrNull()?.youtube_playlist_id
		} ?: throw NoUpstreamPlaylistId()

		val extractor = service.getPlaylistExtractor(upstream, emptyList(), "")

		// fetch the page so that we can get streamCount
		withContext(Dispatchers.IO) {
			extractor.fetchPage()
		}

		val streamCount = extractor.streamCount.toInt()

		val upstreamIdSet = HashMap<String, MinimalStream>(if (streamCount > 0) streamCount else 16)

		withContext(Dispatchers.IO) {
			// ensure no leftover tracks are in the incoming
			database.incomingTrackQueries.clear()

			extractor.asIterator().withIndex().forEach { (position, item) ->
				val videoId = service.streamLHFactory.getId(item.url)
				upstreamIdSet[videoId] =
					MinimalStream(
						position = position,
						title = item.name,
						alreadyExistsInDatabase = false,
						thumbnails = item.thumbnails
					)

				database.incomingTrackQueries.insertOrUpdate(youtube_video_id = videoId, position = position.toLong())
			}

			val addedTracks = database.trackQueries.selectTracksOnlyInIncoming().awaitAsList().sortedBy { it.position }
			addedTracks.forEach {
				terminal.println(
					terminal.theme.success(
						"+ track \"${
							upstreamIdSet[it.youtube_video_id]?.title ?: it.youtube_video_id
						}\" was added at position ${it.position}, as it exists in the upstream"
					)
				)
			}

			val deletedTracks =
				database.trackQueries.deleteTracksAbsentFromIncoming().awaitAsList().sortedBy { it.position }
			deletedTracks.forEach {
				terminal.println(
					terminal.theme.danger(
						"- track \"${it.title}\" was deleted locally at position ${it.position}, as it does not exist in the upstream"
					)
				)
			}

			// don't leave any leftovers
			database.incomingTrackQueries.clear()

			// handle position updates for existing tracks
			val existingTracks = database.trackQueries.selectIdsAndPositionsAscending().awaitAsList()

			coroutineScope {
				existingTracks.forEach { existing ->
					launch {
						val id = existing.youtube_video_id
						val incoming = upstreamIdSet[id] ?: return@launch

						incoming.alreadyExistsInDatabase = true

						// only output if the track was moved, otherwise the terminal will be spammed
						if (existing.position.toInt() != incoming.position) {
							terminal.println(terminal.theme.info("~ track \"${incoming.title}\" moved from position ${existing.position} to ${incoming.position}"))

							database.trackQueries.updatePosition(
								position = incoming.position.toLong(),
								youtube_video_id = id
							)
						}
					}
				}
			}
		}

		val overallLayout = progressBarContextLayout<ProgressBarStatus>(alignColumns = false) {
			text {
				when (context) {
					ProgressBarStatus.Synced -> "Done syncing!"
					ProgressBarStatus.Syncing -> "Syncing..."
				}
			}
			percentage()
			progressBar(width = 40)
			timeElapsed(compact = false)
		}

		val taskLayout = progressBarContextLayout {
			spinner(spinner = Spinner.Dots())
			timeElapsed(compact = true)
			text(align = TextAlign.LEFT) { context }
		}

		val progress = MultiProgressBarAnimation(terminal).animateInCoroutine()
		val overall = progress.addTask(overallLayout, context = ProgressBarStatus.Syncing, total = streamCount.toLong())

		launch { progress.execute() }

		val semaphore = Semaphore(8)

		// always call sync on tracks in the upstream
		// why? audio_path and/or thumbnail_path may have been deleted
		// this lets us reify those values if they were deleted
		coroutineScope {
			upstreamIdSet.forEach { (id, stream) ->
				launch(Dispatchers.Default) {
					semaphore.withPermit {
						val task =
							progress.addTask(taskLayout, context = stream.title, total = 1)

						val exception =
							runCatching {
								syncSingleTrackFromUpstream(
									id = id,

									stream = stream,
								)
							}.exceptionOrNull()

						if (exception != null) {
							terminal.println(terminal.theme.danger("! track \"${stream.title}\" failed to sync: ${exception.message ?: exception.toString()}"))
						}

						task.advance()
						overall.advance()

						progress.removeTask(task.id)
					}
				}
			}
		}

		overall.update {
			context = ProgressBarStatus.Synced
			completed = streamCount.toLong()
		}

		return@coroutineScope
	}

	suspend fun writeToM3u(path: Path?) = withContext(Dispatchers.IO) {
		val path = path ?: folder.resolve("$name.m3u")

		val bufferedWriter = path.toFile().bufferedWriter()
		val query = database.trackQueries.getPathAndTitlesAscending().awaitAsList()

		bufferedWriter.use {
			it.write("#EXTM3U\n")

			query.forEach { track ->
				it.write("#EXTINF:0,${track.title}\n")
				it.write("${track.audio_path}\n")
			}
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

		private suspend fun createDatabaseFromPath(path: Path): Pair<Database, Sqlx4kSqldelightDriver<ISQLite>> {
			val path = path.toAbsolutePath().normalize()

			val options = ConnectionPool.Options.builder()
				.minConnections(4)
				.maxConnections(16)
				.build()

			val sqlx4kDriver = sqlite(
				url = "jdbc:sqlite:file:$path?mode=rwc",
				options = options
			)

			val driver = Sqlx4kSqldelightDriver(sqlx4kDriver)
			val version = driver.getVersion()
			val schema = Database.Schema

			// this runs our migrations for us
			if (version == 0L) {
				schema.create(driver).await()
				driver.setVersion(schema.version)
			} else if (version < schema.version) {
				schema.migrate(driver, version, schema.version).await()
				driver.setVersion(schema.version)
			}

			val database = Database(driver)

			val applicationId =
				driver.executeQuery(
					null,
					"PRAGMA application_id;",
					mapper = { cursor -> QueryResult.AsyncValue { cursor.next().await(); cursor.getLong(0) } },
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

private suspend fun Sqlx4kSqldelightDriver<ISQLite>.getVersion(): Long {
	return executeQuery(
		null, "PRAGMA user_version;", mapper = {
			QueryResult.AsyncValue {
				it.next().await()
				it.getLong(0)
			}
		}, 0, null
	).await() ?: 0
}

private suspend fun Sqlx4kSqldelightDriver<ISQLite>.setVersion(version: Long) {
	execute(null, "PRAGMA user_version = $version", 0, null).await()
}
