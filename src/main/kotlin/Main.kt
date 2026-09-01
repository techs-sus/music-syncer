package com.github.techs_sus

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.localization.Localization

suspend fun main() {
	val downloader = DownloaderImpl.init(OkHttpClient.Builder());
	NewPipe.init(downloader as Downloader, Localization("en", "US"));


	val youtubeService = ServiceList.YouTube;
	val playlistExtractor =
		youtubeService.getPlaylistExtractor("https://music.youtube.com/playlist?list=OLAK5uy_nFiS1SeXBnJII-kBfpg7kGRB0JeE_tot8");

	println("got name: ${playlistExtractor.name}");
}
