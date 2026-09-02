plugins {
	kotlin("jvm") version "2.4.10"
	alias(libs.plugins.sqldelight)
	application
}

application {
	mainClass.set("com.github.techs_sus.MainKt")
}

group = "com.github.techs_sus"
version = "1.0-SNAPSHOT"

repositories {
	google()
	mavenCentral()

	maven("https://jitpack.io") {}
}

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

	testImplementation(kotlin("test"))
}

kotlin {
	jvmToolchain(21)
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
