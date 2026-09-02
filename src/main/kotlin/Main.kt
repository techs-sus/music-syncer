package com.github.techs_sus

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

suspend fun main() {
	val downloader = DownloaderImpl.init(OkHttpClient.Builder())
	NewPipe.init(downloader, Localization("en", "US"))
}
