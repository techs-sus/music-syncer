plugins {
	kotlin("jvm") version "2.4.10"
	alias(libs.plugins.sqldelight)
	application
}

application {
	mainClass.set("com.github.techs_sus.MainKt")
	applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

group = "com.github.techs_sus"
version = "1.0-SNAPSHOT"

dependencies {
	implementation(libs.newpipeextractor)

	implementation(libs.okhttp)
	implementation(libs.okhttp.brotli)
	implementation(libs.okhttp.coroutines)

	implementation(libs.sqldelight.jvm)
	implementation(libs.sqldelight.runtime)

	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.kotlinx.io.core)

	implementation(libs.jaudiotagger)
	implementation(libs.scrimage)
	implementation(libs.scrimage.webp)

	implementation(libs.clikt)

	implementation(libs.progressbar)

	testImplementation(kotlin("test"))
}

kotlin {
	jvmToolchain(25)
}

tasks.test {
	useJUnitPlatform()
}

sqldelight {
	databases {
		register("Database") {
			packageName.set("com.github.techs_sus")
			srcDirs("src/main/sqldelight")
			dialect(libs.sqldelight.dialect.sqlite)
		}
	}
}
