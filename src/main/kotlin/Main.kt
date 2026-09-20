package com.github.techs_sus

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.registerCloseable
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.stream.consumeAsFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo
import kotlin.system.exitProcess

class InitCommand : SuspendingCliktCommand() {
	val playlist: Playlist by requireObject()
	val upstream: String by option("-u", "--upstream", help = "upstream playlist id").required()

	override fun help(context: Context) =
		"Initializes a database with an upstream YouTube playlist. Must be given a playlist id and not a playlist URL"

	override suspend fun run() {
		val terminal = Terminal()

		if (upstream.startsWith("http") || !playlist.isValidPlaylist(upstream)) {
			terminal.println(terminal.theme.danger("error: invalid playlist id"))
			terminal.println()
			terminal.println(
				"You ${terminal.theme.warning("must")} give a playlist id, ${
					terminal.theme.warning(
						"NOT"
					)
				} a url."
			)
			terminal.println(
				"If you gave a url and it looks like this: https://music.youtube.com/playlist?list=${
					terminal.theme.success(
						"VALUE"
					)
				}"
			)
			terminal.println(
				"then please pass in the ${
					terminal.theme.success(
						"VALUE"
					)
				} part and ${
					terminal.theme.warning(
						"NOT"
					)
				} the full url"
			)

			exitProcess(1)
		}

		playlist.setYoutubeUpstream(upstream)

		terminal.println(terminal.theme.success("successfully set playlist upstream"))
	}
}

class SyncCommand : SuspendingCliktCommand() {
	val playlist: Playlist by requireObject()

	override fun help(context: Context) =
		"Syncs a database with an upstream YouTube playlist by ensuring all tracks and their thumbnails are downloaded"

	override suspend fun run() {
		playlist.syncFromUpstream()
	}
}

class WriteToM3uCommand : SuspendingCliktCommand() {
	val playlist: Playlist by requireObject()
	val m3uPath by option(help = "optional output m3u path").path(canBeDir = false).defaultLazy {
		playlist.folder.resolve("${playlist.name}.m3u")
	}

	override fun help(context: Context) = "Uses a database to write an M3U file"

	override suspend fun run() {
		playlist.writeToM3u(m3uPath)

		val terminal = Terminal()
		terminal.println(terminal.theme.success("Wrote an m3u playlist to \"$m3uPath\"!"))
	}
}

class CleanContainer : SuspendingCliktCommand() {
	val container by option("-c", "--container", help = "the folder containing all your playlists databases").path(
		mustExist = true,
		mustBeWritable = true,
		mustBeReadable = true,

		canBeDir = true,
		canBeFile = false,
		canBeSymlink = false,
	).required()

	override fun help(context: Context) =
		"Cleans up all tracks and thumbnails in a container that are not present in any playlist"

	override suspend fun run() {
		withContext(Dispatchers.IO) {
			val terminal = Terminal()

			terminal.println(terminal.theme.info("Cleaning container: \"$container\""))

			val playlists =
				Files.list(container).consumeAsFlow().filter { it.extension == "db" }
					.mapNotNull { runCatching { Playlist.createFromPath(it) }.getOrNull() }.toList()

			playlists.forEach {
				terminal.println(terminal.theme.info("Found valid playlist in container named \"${it.name}\""))
			}

			val files = merge(
				Files.list(container.resolve("audio")).consumeAsFlow()
					.filter { it.extension == knownFinalAudioExtension },

				Files.list(container.resolve("thumbnail")).consumeAsFlow()
					.filter { it.extension == knownFinalThumbnailExtension }
			)
				.toList().groupBy {
					it.nameWithoutExtension
				}
				// we want orphaned tracks
				.filter { (videoId) ->
					playlists.none { playlist ->
						playlist.hasTrackInDatabase(videoId)
					}
				}


			files.forEach { (id, paths) ->
				run {
					terminal.println(
						terminal.theme.danger(
							"orphan \"$id\" no longer has paths: ${
								paths.map {
									it.relativeTo(
										container
									)
								}
							}"
						)
					)

					paths.forEach { it.deleteIfExists() }
				}
			}

			terminal.println(terminal.theme.success("Cleaned container!"))
		}
	}
}

class MusicSyncerKotlin : SuspendingNoOpCliktCommand() {
	override fun help(context: Context) =
		"Locally sync and manage YouTube Music playlists quickly and easily"
}

class PlaylistCommand : SuspendingCliktCommand() {
	val path by option("-p", "--path", help = "required sqlite database path").path(canBeDir = false).required()

	override fun help(context: Context) =
		"Manage an incrementally fetched playlist with its SQLite database"

	override suspend fun run() {
		val playlist = Playlist.createFromPath(path)
		currentContext.obj = currentContext.registerCloseable(playlist)
	}
}

suspend fun main(args: Array<String>) {
	val downloader = DownloaderImpl.init(OkHttpClient.Builder())
	NewPipe.init(downloader, Localization("en", "US"))

	try {
		MusicSyncerKotlin().subcommands(
			PlaylistCommand().subcommands(InitCommand(), SyncCommand(), WriteToM3uCommand()),
			CleanContainer()
		)
			.main(args)
	} finally {
		DownloaderImpl.closeInstance()
	}
}
