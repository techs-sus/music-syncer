package com.github.techs_sus

import kotlinx.io.files.SystemTemporaryDirectory
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import java.nio.file.Path
import kotlin.io.path.createDirectory

suspend fun main() {
	val downloader = DownloaderImpl.init(OkHttpClient.Builder())
	NewPipe.init(downloader, Localization("en", "US"))

	val temporaryFolder = Path.of(SystemTemporaryDirectory.toString(), "music-syncer-kotlin-native-agent")

	runCatching {
		temporaryFolder.createDirectory()
	}

	Playlist.createFromPath(temporaryFolder.resolve("test.db")).use {
		// PLGH9mkC270ac is guaranteed to only have 1 song
		it.setYoutubeUpstream("PLGH9mkC270ac").await()
		it.syncFromUpstream()
	}

	downloader.close()
}
