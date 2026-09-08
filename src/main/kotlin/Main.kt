package com.github.techs_sus

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
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
	val m3uPath by option(help = "optional output m3u path").path(canBeDir = false)

	override fun help(context: Context) = "Uses a database to write an M3U file"

	override suspend fun run() {
		playlist.writeToM3u(m3uPath)
	}
}

object PlaylistHolder {
	var playlist: Playlist? = null
}

class MusicSyncerKotlin : SuspendingCliktCommand() {
	val path by option("-p", "--path", help = "sqlite database path").path(canBeDir = false).required()

	override fun help(context: Context) =
		"Allows for the incremental fetching of playlists using a SQLite database."

	override suspend fun run() {
		val playlist = Playlist.createFromPath(path)
		PlaylistHolder.playlist = playlist
		currentContext.obj = playlist
	}
}

suspend fun main(args: Array<String>) {
	val downloader = DownloaderImpl.init(OkHttpClient.Builder())
	NewPipe.init(downloader, Localization("en", "US"))

	try {
		MusicSyncerKotlin().subcommands(InitCommand(), SyncCommand(), WriteToM3uCommand()).main(args)
	} finally {
		PlaylistHolder.playlist?.close()
		DownloaderImpl.closeInstance()
	}
}
