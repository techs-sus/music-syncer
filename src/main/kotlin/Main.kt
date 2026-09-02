package com.github.techs_sus

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

class InitCommand : SuspendingCliktCommand() {
	val playlist: Playlist by requireObject()
	val upstream: String by option("-u", "--upstream", help = "upstream playlist id").required()

	override fun help(context: Context) = "Initializes a database with an upstream YouTube playlist"

	override suspend fun run() {
		playlist.setYoutubeUpstream(upstream)
	}
}

class SyncCommand : SuspendingCliktCommand() {
	val playlist: Playlist by requireObject()

	override fun help(context: Context) =
		"Syncs a database with an upstream YouTube playlist by ensuring all tracks and their thumbnails are downloaded"

	override suspend fun run() {
		playlist.syncFromUpstream();
	}
}

class WriteToM3uCommand : SuspendingCliktCommand() {
	val playlist: Playlist by requireObject()
	val m3uPath by option(help = "optional output m3u path").path()

	override fun help(context: Context) = "Uses a database to write an M3U file"

	override suspend fun run() {
		playlist.writeToM3u(m3uPath);
	}
}

class Cli : SuspendingCliktCommand() {
	val path by option("-p", "--path", help = "sqlite database path").path(canBeDir = false).required()

	override suspend fun run() {
		currentContext.obj = Playlist.createFromPath(path);
	}
}


suspend fun main(args: Array<String>) {
	val downloader = DownloaderImpl.init(OkHttpClient.Builder())
	NewPipe.init(downloader, Localization("en", "US"))

	Cli().subcommands(InitCommand(), SyncCommand(), WriteToM3uCommand()).main(args)
}
