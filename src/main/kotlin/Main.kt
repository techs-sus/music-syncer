package com.github.techs_sus

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.localization.Localization
import java.util.stream.Stream

suspend fun main() {
	val downloader = DownloaderImpl.init(OkHttpClient.Builder());
	NewPipe.init(downloader, Localization("en", "US"));
}
