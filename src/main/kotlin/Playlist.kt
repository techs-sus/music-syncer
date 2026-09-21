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
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections.emptyList
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo

private const val SQLITE_APPLICATION_ID = 0x7D8A4B83L

sealed class ProjectException(val string: String) : Exception(string)

class DatabaseIsNotOurs : ProjectException("database is not ours")
class NoUpstreamPlaylistId : ProjectException("no upstream playlist id")
class FailedFindingThumbnail : ProjectException("failed finding thumbnail")
class FailedDecodingMime(mime: String) : ProjectException("failed decoding mime type: $mime")
class NoContentTypeHeader : ProjectException("there was no content type header")
class FailedFindingAudioStream : ProjectException("failed finding audio stream")
class FailedDownloadingFromUrl : ProjectException("failed downloading from url")
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

sealed class TrackStatus(val title: String, val currentPosition: Int) {
	class Added(title: String, currentPosition: Int) : TrackStatus(title, currentPosition)
	class Removed(title: String, currentPosition: Int) : TrackStatus(title, currentPosition)
	class Moved(title: String, currentPosition: Int, val oldPosition: Int) : TrackStatus(title, currentPosition)

	class Errored(
		title: String, currentPosition: Int, val exception: Throwable
	) : TrackStatus(title, currentPosition)
}

const val knownFinalAudioExtension = "m4a"
const val knownFinalThumbnailExtension = "png"

private const val maximumSyncConcurrency = 8

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
) : AutoCloseable {
	override fun close() {
		http.dispatcher.executorService.shutdown()
		http.connectionPool.evictAll()
		http.cache?.close()
		driver.close()
	}

	private val audioFolder = folder.resolve("audio")
	private val thumbnailFolder = folder.resolve("thumbnail")

	companion object {
		suspend fun createFromDatabasePath(path: Path): Playlist {
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

			return Pair(Database(driver), driver)
		}
	}

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

	suspend fun isUpstreamPlaylistIdValid(upstreamPlaylistId: String): Boolean = withContext(Dispatchers.IO) {
		val extractor = service.getPlaylistExtractor(upstreamPlaylistId, emptyList(), "")

		extractor.fetchPage()

		return@withContext runCatching {
			extractor.streamCount
		}.isSuccess
	}

	suspend fun setYoutubeUpstream(upstreamPlaylistId: String) = withContext(Dispatchers.IO) {
		database.playlistMetadataQueries.setUpstreamPlaylistId(youtube_playlist_id = upstreamPlaylistId)
	}

	suspend fun hasTrackInDatabase(videoId: String) = withContext(Dispatchers.IO) {
		database.trackQueries.exists(videoId = videoId).executeAsOne()
	}

	suspend fun emitM3U8Playlist(bufferedWriter: BufferedWriter) = withContext(Dispatchers.IO) {
		val query = database.trackQueries.getPathAndDurationAndTitlesAscending().executeAsList()

		bufferedWriter.use {
			it.write("#EXTM3U\n")

			query.forEach { track ->
				it.write("#EXTINF:${track.duration ?: 0},${track.title}\n")
				it.write("${track.audio_path}\n")
			}
		}
	}

	private suspend fun downloadToDynamicPath(
		url: String,
		mapContentTypeToDestination: (contentType: String) -> Path,

		addSpecialRangeHeader: Boolean = false
	): Path = withContext(Dispatchers.IO) {
		var request = okhttp3.Request.Builder()
			.url(url)
			.header("User-Agent", PipeDownloaderImpl.USER_AGENT)
			.header("Referer", "https://music.youtube.com/")

		var destination: Path

		if (addSpecialRangeHeader) request = request.header("Range", "bytes=0-")

		http.newCall(request.build()).executeAsync().use { response ->
			if (!response.isSuccessful) throw FailedDownloadingFromUrl()

			val contentType = response.header("content-type") ?: throw NoContentTypeHeader()

			destination = mapContentTypeToDestination(contentType)

			response.body.byteStream().use { input ->
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
	private suspend fun downloadTrackThumbnail(id: String, url: String): Path =
		downloadToDynamicPath(url, {
			val fileExtension = when (it) {
				"image/png" -> "png"
				"image/jpeg" -> "jpg"
				"image/webp" -> "webp"

				else -> throw FailedDecodingMime(it)
			}

			thumbnailFolder.resolve("$id.$fileExtension")
		})

	// Returns the path that the audio was downloaded to.
	private suspend fun downloadTrackAudio(id: String, url: String): Path =
		downloadToDynamicPath(url, {
			val fileExtension = when (it) {
				"audio/webm" -> "webm"
				"audio/mp4" -> "m4a"

				else -> throw FailedDecodingMime(it)
			}

			audioFolder.resolve("$id.$fileExtension")
		}, addSpecialRangeHeader = true)

	private suspend fun syncTrackFromUpstream(
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
					extractor
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
					Tagging.ensureThumbnailIsUsableInTag(
						downloadTrackThumbnail(
							id = id,
							url = bestThumbnail.url
						)
					)
				}.getOrNull()
			}

			val audioPathLazy =
				async(Dispatchers.IO) {
					if (existingTrackFiles.audioPath != null) {
						// if thumbnail is newly created but audio already exists, retag the audio if possible
						// if the stream is unavailable, then the stream extractor will throw when we try to await it
						// and if the track is already downloaded, but stream is unavailable, then the db call would be skipped
						if (existingTrackFiles.thumbnailPath == null) {
							runCatching {
								Tagging.tagAudio(
									audioPath = existingTrackFiles.audioPath,
									thumbnailPath = thumbnailPathLazy.await(),
									extractor = streamExtractorLazy.await()
								)
							}
						}

						// both audioPath and thumbnailPath are not null, so that means we shouldn't waste time retagging
						return@async existingTrackFiles.audioPath
					}

					val bestAudioStream =
						streamExtractorLazy.await().audioStreams.maxByOrNull { it.bitrate }
							?: throw FailedFindingAudioStream()
					if (!bestAudioStream.isUrl) throw FailedFindingAudioStream()

					val audioPath = Tagging.ensureAudioIsTaggable(downloadTrackAudio(id = id, url = bestAudioStream.content))
					Tagging.tagAudio(
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

		val upstreamIdSet = HashMap<String, PlaylistStreamItem>(streamCount)

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
				val semaphore = Semaphore(maximumSyncConcurrency)

				// always call sync on tracks in the upstream
				// why? audio_path and/or thumbnail_path may have been deleted
				// this lets us reify those values if they were deleted
				coroutineScope {
					upstreamIdSet.forEach { (id, stream) ->
						launch(Dispatchers.Default) {
							semaphore.withPermit {
								val task = progressMutex.withLock { progress.addTask(taskLayout, context = stream.title, total = 1) }

								runCatching {
									syncTrackFromUpstream(
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
		val sortedTrackStatuses = statusFlow.toList().sortedBy { it.currentPosition }

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

		sortedTrackStatuses.forEach {
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
}
