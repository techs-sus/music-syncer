package com.github.techs_sus

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.absolute
import kotlin.io.path.name

private const val SQLITE_APPLICATION_ID = 0x7D8A4B83;

open class ProjectException(val string: String) : Exception(string);
class DatabaseIsNotOurs : ProjectException("database is not ours");
class NoUpstreamPlaylistId : ProjectException("no upstream playlist id");


class Playlist(private val database: Database, val name: String, val folder: Path) {
	suspend fun syncFromUpstream() {
		val upstream = database.playlistMetadataQueries.getUpstreamPlaylistId().executeAsOneOrNull()?.youtube_playlist_id
			?: throw NoUpstreamPlaylistId();
	}

	companion object {
		suspend fun createFromPath(path: Path): Playlist {
			val database = createDatabaseFromPath(path);

			return Playlist(
				database = database, name = path.name, folder = path.parent
			)
		}

		private suspend fun createDatabaseFromPath(path: Path): Database {
			val path = path.absolute();

			val driver: SqlDriver = JdbcSqliteDriver(buildString {
				append("jdbc:sqlite:").append(path.toString())
			}, Properties(), Database.Schema);

			val database = Database(driver);

			val applicationId =
				driver.executeQuery<Long?>(
					null,
					"PRAGMA application_id;",
					mapper = { cursor -> QueryResult.Value(cursor.getLong(0)) },
					0
				).await();

			if (applicationId == null || applicationId.toInt() == 0) {
				// mark it as our own
				driver.executeQuery<Int?>(
					null,
					"PRAGMA application_id = ${SQLITE_APPLICATION_ID};",
					mapper = { cursor -> QueryResult.Value(null) },
					0
				).await();

				// run migrations
				Database.Schema.migrate(
					driver = driver,
					oldVersion = 0,
					newVersion = Database.Schema.version,
				)
			} else {
				// this is not our database
				throw DatabaseIsNotOurs();
			}

			return database;
		}
	}
}
