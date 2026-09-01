plugins {
	kotlin("jvm") version "2.4.10"
	alias(libs.plugins.sqldelight)
}

group = "com.github.techs_sus"
version = "1.0-SNAPSHOT"

repositories {
	google()
	mavenCentral()

	maven("https://jitpack.io") {
		content { includeGroup("com.github.MetrolistGroup.innertubex") }
	}
}

dependencies {
	implementation(libs.innertubex)
	implementation(libs.ktor.client.okhttp)
	implementation(libs.sqldelight.jvm)
	implementation(libs.sqldelight.runtime)

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
