package com.github.techs_sus

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
import java.io.Closeable
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

private const val SQLITE_APPLICATION_ID = 0x7D8A4B83L

open class ProjectException(val string: String) : Exception(string)

class DatabaseIsNotOurs : ProjectException("database is not ours")
class NoUpstreamPlaylistId : ProjectException("no upstream playlist id")
class FailedFindingThumbnail : ProjectException("failed finding thumbnail")
class FailedDecodingMime : ProjectException("failed decoding mime type")
class NoContentTypeHeader : ProjectException("there was no content type header")
class FailedFindingAudioStream : ProjectException("failed finding audio stream")
class FailedToRemuxAsM4a(exception: IOException) : ProjectException("failed to remux as m4a: $exception")
class FailedDownloadingFromUrl : ProjectException("failed downloading from url")

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

sealed class TrackStatus(val title: String, val currentPosition: Int) {
	class Added(title: String, currentPosition: Int) : TrackStatus(title, currentPosition)
	class Removed(title: String, currentPosition: Int) : TrackStatus(title, currentPosition)
	class Moved(title: String, currentPosition: Int, val oldPosition: Int) : TrackStatus(title, currentPosition)

	class Errored(
		title: String, currentPosition: Int, val exception: Throwable
	) : TrackStatus(title, currentPosition)
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
) : Closeable {
	override fun close() {
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

	private data class PlaylistStreamItem(
		val position: Int,
		val title: String,
		val thumbnails: List<Image>,
		val duration: Long,
	)

	private suspend fun getExistingFilesForTrack(id: String): LocalTrackInfo = withContext(Dispatchers.IO) {
		var audioPath: Path? = null

		// knownFinalThumbnailExtension should always be the highest priority
		val thumbnailPath =
			listOf(knownFinalThumbnailExtension, "jpg", "webp").map { thumbnailFolder.resolve("$id.$it") }.firstOrNull {
				it.exists()
			}

		val knownFinalM4aPath = audioFolder.resolve("$id.$knownFinalAudioExtension")
		if (knownFinalM4aPath.exists()) audioPath = knownFinalM4aPath

		return@withContext LocalTrackInfo(
			thumbnailPath = thumbnailPath,
			audioPath = audioPath
		)
	}

	suspend fun isValidPlaylist(upstreamPlaylistId: String): Boolean = withContext(Dispatchers.IO) {
		val extractor = service.getPlaylistExtractor(upstreamPlaylistId, emptyList(), "")

		extractor.fetchPage()

		return@withContext runCatching {
			extractor.streamCount
		}.isSuccess
	}

	suspend fun setYoutubeUpstream(upstreamPlaylistId: String) = withContext(Dispatchers.IO) {
		database.playlistMetadataQueries.setUpstreamPlaylistId(youtube_playlist_id = upstreamPlaylistId)
	}

	private suspend fun downloadFromUrl(
		url: String,
		outputPathForContentType: (contentType: String) -> Path,

		addSpecialRangeHeader: Boolean = false
	): Path = withContext(Dispatchers.IO) {
		var request = okhttp3.Request.Builder()
			.url(url)
			.header("User-Agent", DownloaderImpl.USER_AGENT)
			.header("Referer", "https://music.youtube.com/")

		var destination: Path

		if (addSpecialRangeHeader) request = request.header("Range", "bytes=0-")

		val response = http.newCall(request.build()).executeAsync()
		response.use {
			if (!it.isSuccessful) throw FailedDownloadingFromUrl()

			val contentType = it.header("content-type") ?: throw NoContentTypeHeader()

			destination = outputPathForContentType(contentType)

			it.body.byteStream().use { input ->
				Files.newOutputStream(
					destination, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.SYNC, StandardOpenOption.TRUNCATE_EXISTING
				).buffered().use { out ->
					input.copyTo(out)
				}
			}
		}

		return@withContext destination
	}

	// Returns the path that the thumbnail was downloaded to.
	private suspend fun syncSingleTrackThumbnail(id: String, url: String): Path =
		downloadFromUrl(url, {
			val fileExtension = when (it) {
				"image/png" -> "png"
				"image/jpeg" -> "jpg"
				"image/webp" -> "webp"

				else -> throw FailedDecodingMime()
			}

			thumbnailFolder.resolve("$id.$fileExtension")
		})

	// Returns the path that the audio was downloaded to.
	private suspend fun syncSingleTrackAudio(id: String, url: String): Path =
		downloadFromUrl(url, {
			val fileExtension = when (it) {
				"audio/webm" -> "webm"
				"audio/mp4" -> "m4a"

				else -> throw FailedDecodingMime()
			}

			audioFolder.resolve("$id.$fileExtension")
		}, addSpecialRangeHeader = true)


	private suspend fun ensureAudioIsTaggable(inputFile: Path): Path =
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

	private suspend fun ensureThumbnailIsUsableInTag(inputFile: Path): Path = withContext(Dispatchers.IO) {
		if (inputFile.extension == knownFinalThumbnailExtension) return@withContext inputFile

		val image = ImmutableImage.loader().fromPath(inputFile)
		val outputPath = image.output(
			// this is lossless compression
			PngWriter.MaxCompression,
			thumbnailFolder.resolve("${inputFile.nameWithoutExtension}.$knownFinalThumbnailExtension")
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

		playlistStreamItem: PlaylistStreamItem,
	): Unit =
		coroutineScope {
			val existingTrackFiles = getExistingFilesForTrack(id)

			// this is lazy to prevent unnecessary calls to InnerTube
			val streamExtractorLazy by lazy {
				async(Dispatchers.IO) {
					val extractor = service.getStreamExtractor(service.streamLHFactory.fromId(id))
					extractor.fetchPage()
					return@async extractor
				}
			}

			val thumbnailPathLazy = async(Dispatchers.IO) {
				if (existingTrackFiles.thumbnailPath !== null) return@async existingTrackFiles.thumbnailPath

				// try using the thumbnails from the streamExtractor first
				// else use the PlaylistStreamInfo's thumbnails
				// if those fail, throw an error
				val bestThumbnail = streamExtractorLazy.await().thumbnails.maxByOrNull { it.width * it.height }
					?: playlistStreamItem.thumbnails.maxByOrNull { it.width * it.height }
					?: throw FailedFindingThumbnail()

				runCatching {
					ensureThumbnailIsUsableInTag(
						syncSingleTrackThumbnail(
							id = id,
							url = bestThumbnail.url
						)
					)
				}.getOrNull()
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
					title = playlistStreamItem.title,
					audio_path = audioPath.relativeTo(folder).toString(),
					thumbnail_path = thumbnailPath?.relativeTo(folder).toString(),
					position = playlistStreamItem.position.toLong(),
					youtube_video_id = id,
					duration = playlistStreamItem.duration,
				)
			}
		}

	suspend fun syncFromUpstream() = coroutineScope {
		val upstream = withContext(Dispatchers.IO) {
			database.playlistMetadataQueries.getUpstreamPlaylistId().executeAsOneOrNull()?.youtube_playlist_id
		} ?: throw NoUpstreamPlaylistId()

		val extractor = service.getPlaylistExtractor(upstream, emptyList(), "")

		// fetch the page so that we can get streamCount
		withContext(Dispatchers.IO) {
			extractor.fetchPage()
		}

		val streamCount = extractor.streamCount.toInt()

		val upstreamIdSet = HashMap<String, PlaylistStreamItem>(if (streamCount > 0) streamCount else 16)

		withContext(Dispatchers.IO) {
			// ensure no leftover tracks are in the incoming
			database.incomingTrackQueries.clear()

			database.transaction {
				extractor.asIterator().withIndex().forEach { (position, item) ->
					val videoId = service.streamLHFactory.getId(item.url)

					upstreamIdSet[videoId] =
						PlaylistStreamItem(
							position = position,
							title = item.name,
							thumbnails = item.thumbnails,
							duration = item.duration,
						)

					database.incomingTrackQueries.insertOrUpdate(youtube_video_id = videoId, position = position.toLong())
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
		val progressMutex = Mutex()

		launch { progress.execute() }

		val statusFlow = channelFlow {
			launch(Dispatchers.IO) {
				val addedTracks = database.trackQueries.selectTracksOnlyInIncoming().executeAsList()

				addedTracks.map {
					TrackStatus.Added(
						title = upstreamIdSet[it.youtube_video_id]!!.title,
						currentPosition = it.position.toInt()
					)
				}.forEach {
					send(it)
				}
			}

			launch(Dispatchers.IO) {
				val deletedTracks =
					database.trackQueries.deleteTracksAbsentFromIncoming().executeAsList()
				deletedTracks.map {
					TrackStatus.Removed(
						title = it.title,
						currentPosition = it.position.toInt()
					)
				}.forEach {
					send(it)
				}
			}

			launch(Dispatchers.IO) {
				val existingTracks = database.trackQueries.selectIdsAndPositionsAscending().executeAsList()
				var previousNewPosition = -1
				var orderPreserved = true

				for ((id) in existingTracks) {
					val incoming = upstreamIdSet[id] ?: continue

					if (incoming.position < previousNewPosition) orderPreserved = false
					else if (orderPreserved) previousNewPosition = incoming.position
				}

				if (orderPreserved) return@launch

				for ((id, position) in existingTracks) {
					val incoming = upstreamIdSet[id] ?: continue
					if (position.toInt() == incoming.position) continue

					send(
						TrackStatus.Moved(
							title = incoming.title,
							currentPosition = incoming.position,
							oldPosition = position.toInt()
						)
					)
				}
			}

			launch {
				val semaphore = Semaphore(8)

				// always call sync on tracks in the upstream
				// why? audio_path and/or thumbnail_path may have been deleted
				// this lets us reify those values if they were deleted
				coroutineScope {
					upstreamIdSet.forEach { (id, stream) ->
						launch(Dispatchers.Default) {
							semaphore.withPermit {
								val task = progressMutex.withLock { progress.addTask(taskLayout, context = stream.title, total = 1) }

								runCatching {
									syncSingleTrackFromUpstream(
										id = id,

										playlistStreamItem = stream,
									)
								}.onFailure {
									send(
										TrackStatus.Errored(
											title = stream.title,
											currentPosition = stream.position,
											exception = it
										)
									)
								}

								progressMutex.withLock {
									overall.advance()
									task.advance()
									progress.removeTask(task.id)
								}
							}
						}
					}
				}
			}
		}

		// after this call everything is guaranteed to be finished
		val list = statusFlow.toList().sortedBy { it.currentPosition }

		// don't leave any leftovers
		database.incomingTrackQueries.clear()

		overall.update {
			context = ProgressBarStatus.Synced
			completed = streamCount.toLong()
			total = streamCount.toLong()
		}

		progressMutex.withLock {
			progress.refresh(refreshAll = true)
			progress.stop()
		}

		list.forEach {
			when (it) {
				is TrackStatus.Added -> terminal.println(
					terminal.theme.success(
						"+ track \"${it.title}\" was added at position ${it.currentPosition}, as it exists in the upstream"
					)
				)

				is TrackStatus.Removed -> terminal.println(
					terminal.theme.warning(
						"- track \"${it.title}\" was deleted locally at position ${it.currentPosition}, as it does not exist in the upstream"
					)
				)

				is TrackStatus.Moved -> terminal.println(
					terminal.theme.info(
						"~ track \"${it.title}\" moved from position ${it.oldPosition} to ${it.currentPosition}"
					)
				)

				is TrackStatus.Errored -> terminal.println(
					terminal.theme.danger(
						"! track \"${it.title}\" failed to sync: ${it.exception.message ?: it.exception.toString()}"
					)
				)
			}
		}
	}

	suspend fun writeToM3u(path: Path?) = withContext(Dispatchers.IO) {
		val path = path ?: folder.resolve("$name.m3u")

		val bufferedWriter = path.toFile().bufferedWriter()
		val query = database.trackQueries.getPathAndDurationAndTitlesAscending().executeAsList()

		bufferedWriter.use {
			it.write("#EXTM3U\n")

			query.forEach { track ->
				it.write("#EXTINF:${track.duration ?: 0},${track.title}\n")
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

		private suspend fun createDatabaseFromPath(path: Path): Pair<Database, SqlDriver> {
			val path = path.toAbsolutePath().normalize()

			// this runs our migrations for us
			val driver: SqlDriver = JdbcSqliteDriver(
				"jdbc:sqlite:file:$path?mode=rwc", Properties(), Database.Schema
			)

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

			val database = Database(driver)

			return Pair(database, driver)
		}
	}
}

