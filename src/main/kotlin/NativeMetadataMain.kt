package com.github.techs_sus

import kotlinx.io.files.SystemTemporaryDirectory
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteRecursively

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
suspend fun main() {
	val downloader = PipeDownloaderImpl.init(OkHttpClient.Builder())
	NewPipe.init(downloader, Localization("en", "US"))

	// scanning for plugins requires the xawt jdk library
	// we do this to ensure all plugins that can be used have verification metadata written for them
	// we cannot do this in Main.kt because xawt should never be required to run the actual program
	ImageIO.scanForPlugins()

	val temporaryFolder = Path.of(SystemTemporaryDirectory.toString(), "music-syncer-kotlin-native-agent")

	runCatching {
		temporaryFolder.deleteRecursively()
	}

	runCatching {
		temporaryFolder.createDirectory()
	}

	Playlist.createFromDatabasePath(temporaryFolder.resolve("test.db")).use {
		// this has webp conversion so we use it
		it.setYoutubeUpstream("PLBNgsu_4XNgU").await()
		it.syncFromUpstream()
	}

	downloader.close()
}
