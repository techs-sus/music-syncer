rootProject.name = "music-syncer-kotlin"

pluginManagement {
	repositories {
		gradlePluginPortal()
		google()
		mavenCentral()
		maven("https://jitpack.io")
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
		maven("https://jitpack.io")
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
	id("io.github.ben-manes.versions.settings") version "0.61.0"
}
