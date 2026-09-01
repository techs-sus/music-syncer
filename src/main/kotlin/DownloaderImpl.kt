package com.github.techs_sus

import io.ktor.http.contentRangeHeaderValue
import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.brotli.Brotli
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.stream.Collectors
import java.util.stream.Stream

class DownloaderImpl constructor(builder: OkHttpClient.Builder) : Downloader() {
	private val mCookies: MutableMap<String?, String?>

	val client: OkHttpClient

	init {
		this.client = builder
			.readTimeout(
				30,
				TimeUnit.SECONDS
			) //                .cache(new Cache(new File(context.getExternalCacheDir(), "okhttp"),
			//                        16 * 1024 * 1024))
			.addInterceptor(
				CompressionInterceptor(
					Brotli,
					Gzip
				)
			)
			.build()
		this.mCookies = HashMap<String?, String?>()
	}

	fun getCookies(url: String): String {
		val youtubeCookie: String? = (if (url.contains(DownloaderImpl.Companion.YOUTUBE_DOMAIN))
			getCookie(DownloaderImpl.Companion.YOUTUBE_RESTRICTED_MODE_COOKIE_KEY)
		else
			null);

		// Recaptcha cookie is always added TODO: not sure if this is necessary
		return Stream.of<String?>(youtubeCookie)
			.filter { obj: String? -> Objects.nonNull(obj) }
			.flatMap<String> { cookies: String? ->
				Arrays.stream<String>(
					cookies!!.split("; *".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
				)
			}
			.distinct()
			.collect(Collectors.joining("; "))
	}

	fun getCookie(key: String?): String? {
		return mCookies.get(key)
	}

	fun setCookie(key: String?, cookie: String?) {
		mCookies.put(key, cookie)
	}

	fun removeCookie(key: String?) {
		mCookies.remove(key)
	}

//	fun updateYoutubeRestrictedModeCookies(context: Context) {
//		val restrictedModeEnabledKey: String? =
//			context.getString(R.string.youtube_restricted_mode_enabled)
//		val restrictedModeEnabled: Boolean = PreferenceManager.getDefaultSharedPreferences(context)
//			.getBoolean(restrictedModeEnabledKey, false)
//		updateYoutubeRestrictedModeCookies(restrictedModeEnabled)
//	}

	fun updateYoutubeRestrictedModeCookies(youtubeRestrictedModeEnabled: Boolean) {
		if (youtubeRestrictedModeEnabled) {
			setCookie(
				YOUTUBE_RESTRICTED_MODE_COOKIE_KEY,
				YOUTUBE_RESTRICTED_MODE_COOKIE
			)
		} else {
			removeCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY)
		}
	}

	/**
	 * Get the size of the content that the url is pointing by firing a HEAD request.
	 *
	 * @param url an url pointing to the content
	 * @return the size of the content, in bytes
	 */
	@Throws(IOException::class)
	fun getContentLength(url: String?): Long {
		try {
			val response = head(url)
			return response.getHeader("Content-Length")!!.toLong()
		} catch (e: NumberFormatException) {
			throw IOException("Invalid content length", e)
		} catch (e: ReCaptchaException) {
			throw IOException(e)
		}
	}

	@Throws(IOException::class, ReCaptchaException::class)
	override fun execute(request: Request): Response {
		val httpMethod = request.httpMethod()
		val url = request.url()
		val headers = request.headers()
		val dataToSend = request.dataToSend()

		var requestBody: RequestBody? = null
		if (dataToSend != null) {
			requestBody = dataToSend.toRequestBody();
		}

		val requestBuilder = okhttp3.Request.Builder()
			.method(httpMethod, requestBody)
			.url(url)
			.addHeader("User-Agent", USER_AGENT)

		val cookies = getCookies(url)
		if (!cookies.isEmpty()) {
			requestBuilder.addHeader("Cookie", cookies)
		}

		headers.forEach { (headerName: String?, headerValueList: MutableList<String?>?) ->
			requestBuilder.removeHeader(headerName!!)
			headerValueList!!.forEach(Consumer { headerValue: String? ->
				requestBuilder.addHeader(
					headerName,
					headerValue!!
				)
			})
		}

		client.newCall(requestBuilder.build()).execute().use { response ->
			if (response.code == 429) {
				throw ReCaptchaException("reCaptcha Challenge requested", url)
			}
			var responseBodyToReturn: String? = null
			response.body.use { body ->
				responseBodyToReturn = body.string()
			}
			val latestUrl = response.request.url.toString()
			return Response(
				response.code,
				response.message,
				response.headers.toMultimap(),
				responseBodyToReturn,
				latestUrl
			)
		}
	}

	companion object {
		const val USER_AGENT: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
		const val YOUTUBE_RESTRICTED_MODE_COOKIE_KEY: String = "youtube_restricted_mode_key"
		const val YOUTUBE_RESTRICTED_MODE_COOKIE: String = "PREF=f2=8000000"
		const val YOUTUBE_DOMAIN: String = "youtube.com"

		private var instance: DownloaderImpl? = null

		/**
		 * It's recommended to call exactly once in the entire lifetime of the application.
		 *
		 * @param builder if null, default builder will be used
		 * @return a new instance of [DownloaderImpl]
		 */
		fun init(builder: OkHttpClient.Builder?): DownloaderImpl {
			instance = DownloaderImpl(
				if (builder != null) builder else OkHttpClient.Builder()
			)
			return instance!!
		}

		fun getInstance(): DownloaderImpl {
			return instance!!
		}
	}
}
