package com.github.techs_sus

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.absolute

class Playlist(val database: Database) {
	constructor(path: Path) : this {
		val path = path.absolute();

		val driver: SqlDriver = JdbcSqliteDriver(buildString {
			append("jdbc:sqlite:").append(path.toString())
		}, Properties(), Database.Schema);

		val database = Database(driver);
	}
}

suspend fun main() {
	val client = InnerTube(
		httpClient = HttpClient(OkHttp),
	)

	client.close()
}
