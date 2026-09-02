package com.github.techs_sus

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.OkHttpClient
import okhttp3.brotli.Brotli
import okhttp3.coroutines.executeAsync
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.generic.AudioFileWriter
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.Artwork
import org.jaudiotagger.tag.images.ArtworkFactory
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections.emptyList
import java.util.Properties
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeBytes

private const val SQLITE_APPLICATION_ID = 0x7D8A4B83L

open class ProjectException(val string: String) : Exception(string)
class DatabaseIsNotOurs : ProjectException("database is not ours")
class NoUpstreamPlaylistId : ProjectException("no upstream playlist id")
class FailedFindingThumbnail : ProjectException("failed finding thumbnail")
class FailedDecodingMime : ProjectException("failed decoding mime type")
class FailedFetchingThumbnail : ProjectException("failed fetching thumbnail")
class FailedFindingAudioStream : ProjectException("failed finding audio stream")
class FailedToRemuxAsM4a(exception: IOException) : ProjectException("failed to remux as m4a: $exception")

fun <T : InfoItem> ListExtractor<T>.asIterator(): Iterator<T> {
	return iterator {
		this@asIterator.fetchPage()

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
	val name: String,
	val folder: Path,
) {
	private val audioFolder = folder.resolve("audio")
	private val thumbnailFolder = folder.resolve("thumbnail")
	private val knownFinalAudioExtension = "m4a"

	suspend fun setYoutubeUpstream(upstreamPlaylistId: String) {
		database.playlistMetadataQueries.setUpstreamPlaylistId(youtube_playlist_id = upstreamPlaylistId).await();
	}

	// Returns the path that the thumbnail was downloaded to.
	private suspend fun syncSingleTrackThumbnail(id: String, url: String): Path {
		listOf("png", "jpg").forEach {
			val path = thumbnailFolder.resolve("$id.$it")
			if (path.exists()) return path
		}

		val response =
			http.newCall(
				okhttp3.Request.Builder().url(url).addHeader("User-Agent", DownloaderImpl.USER_AGENT).build()
			)
				.executeAsync()

		if (!response.isSuccessful) throw FailedFetchingThumbnail()

		val fileExtension = when (response.header("content-type")) {
			"image/png" -> "png"
			"image/jpeg" -> "jpg"

			else -> throw FailedDecodingMime()
		}

		return withContext(Dispatchers.IO) {
			val path = thumbnailFolder.resolve("$id.$fileExtension")
			path.writeBytes(response.body.bytes())
			return@withContext path
		}
	}

	// Returns the path that the audio was downloaded to.
	private suspend fun syncSingleTrackAudio(id: String, url: String): Path {
		val knownFinalM4aPath = audioFolder.resolve("$id.$knownFinalAudioExtension")
		if (knownFinalM4aPath.exists()) return knownFinalM4aPath

		val response =
			http.newCall(
				okhttp3.Request.Builder().url(url).addHeader("User-Agent", DownloaderImpl.USER_AGENT)
					.addHeader("Range", "bytes=0-").build()
			)
				.executeAsync()

		if (!response.isSuccessful) throw FailedFetchingThumbnail()

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

	private suspend fun tagAudio(audioPath: Path, thumbnailPath: Path?, extractor: StreamExtractor) =
		withContext(Dispatchers.IO) {
			val audioFile = AudioFileIO.read(audioPath.toFile());
			val tag = audioFile.tagOrCreateAndSetDefault;

			// add the thumbnail if it exists
			if (thumbnailPath != null) tag.addField(ArtworkFactory.createArtworkFromFile(thumbnailPath.toFile()))

			tag.addField(FieldKey.TITLE, extractor.name);
			tag.addField(FieldKey.ARTIST, extractor.uploaderName);
			tag.addField(FieldKey.YEAR, extractor.uploadDate?.offsetDateTime()?.year.toString())

			// writes the tag to disk
			audioFile.commit();
		}

	private suspend fun syncSingleTrackFromUpstream(id: String, title: String, position: Int) {
		val streamExtractor = service.getStreamExtractor(service.streamLHFactory.fromId(id))
		streamExtractor.fetchPage()

		val bestThumbnail = streamExtractor.thumbnails.maxByOrNull { it.estimatedResolutionLevel }
			?: throw FailedFindingThumbnail()

		val thumbnailPath = runCatching {
			syncSingleTrackThumbnail(id = id, url = bestThumbnail.url)
		}.getOrNull()

		val bestAudioStream = streamExtractor.audioStreams.maxByOrNull { it.bitrate } ?: throw FailedFindingAudioStream()
		if (!bestAudioStream.isUrl) throw FailedFindingAudioStream()

		val audioPath = ensureAudioIsTaggable(syncSingleTrackAudio(id = id, url = bestAudioStream.content))

		tagAudio(audioPath = audioPath, thumbnailPath = thumbnailPath, extractor = streamExtractor);

		database.trackQueries.insertOrUpdate(
			youtube_video_id = id,
			title = title,
			position = position.toLong(),
			thumbnail_path = thumbnailPath?.toString(),
			audio_path = audioPath.toString()
		).await()
	}

	suspend fun syncFromUpstream() {
		val upstream = database.playlistMetadataQueries.getUpstreamPlaylistId().executeAsOneOrNull()?.youtube_playlist_id
			?: throw NoUpstreamPlaylistId()

		data class Stream(val position: Int, val title: String, var existsInDatabase: Boolean)

		val extractor = service.getPlaylistExtractor(upstream, emptyList(), "")

		extractor.fetchPage()

		// id -> exists
		val upstreamIdSet = HashMap<String, Stream>(extractor.streamCount.toInt())

		extractor.asIterator().withIndex().forEach { (index, item) ->
			val videoId = service.streamLHFactory.getId(item.url)
			upstreamIdSet[videoId] = Stream(position = index, title = item.name, existsInDatabase = false)
		}

		var lastPosition: Long = 0

		while (true) {
			val items = database.trackQueries.getAllStreaming(
				limit = 50,
				lastPosition = lastPosition
			).executeAsList()

			if (items.isEmpty()) break

			items.forEach {
				when (upstreamIdSet.contains(it.youtube_video_id)) {
					true -> {
						upstreamIdSet[it.youtube_video_id]!!.existsInDatabase = true
					}

					false -> database.trackQueries.remove(it.youtube_video_id).await()
				}
			}

			lastPosition += items.size
		}

		upstreamIdSet.forEach { (id, stream) ->
			when (stream.existsInDatabase) {
				true -> {
					// this track already exists in the database, but maybe its position changed, so update
					database.trackQueries.updatePosition(position = stream.position.toLong(), youtube_video_id = id).await()
				}

				false -> {
					// this track does not exist in the database, but does exist in the upstream
					// so sync it!
					syncSingleTrackFromUpstream(id = id, title = stream.title, position = stream.position)
				}
			}
		}
	}

	suspend fun writeToM3u(path: Path?) {
		val path = path ?: folder.resolve("$name.m3u");

		val bufferedWriter = path.toFile().bufferedWriter();
		val query = database.trackQueries.getPathAndTitlesAscending();
		val tracksIterator = query.execute { cursor ->
			QueryResult.Value(iterator {
				while (cursor.next().value) yield(query.mapper(cursor))
			})
		}.value;

		withContext(Dispatchers.IO) {
			bufferedWriter.write("#EXTM3U");

			tracksIterator.forEach {
				bufferedWriter.write("#EXTINF:0,${it.title}");
				bufferedWriter.write(it.audio_path);
			};

			bufferedWriter.close();
		};
	}

	companion object {
		suspend fun createFromPath(path: Path): Playlist {
			val playlist = Playlist(
				database = createDatabaseFromPath(path), name = path.name, folder = path.parent
			)

			withContext(Dispatchers.IO) {
				runCatching { Files.createDirectory(playlist.thumbnailFolder) }
				runCatching { Files.createDirectory(playlist.audioFolder) }
			};

			return playlist
		}

		private suspend fun createDatabaseFromPath(path: Path): Database {
			val path = path.toAbsolutePath().normalize();

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

					// run migrations
					Database.Schema.migrate(
						driver = driver,
						oldVersion = 0,
						newVersion = Database.Schema.version,
					).await()
				}

				SQLITE_APPLICATION_ID -> {
					// already ours
				}

				// not ours
				else -> throw DatabaseIsNotOurs()
			}

			return database
		}
	}
}
