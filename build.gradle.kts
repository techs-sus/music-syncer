plugins {
	kotlin("jvm") version libs.versions.kotlin
	alias(libs.plugins.graalvm.native)
	alias(libs.plugins.sqldelight)
	application
}

application {
	mainClass.set("com.github.techs_sus.MainKt")
	applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

graalvmNative {
	metadataRepository {
		enabled.set(true)
	}

	agent {
		defaultMode.set("standard")
	}

	binaries {
		named("main") {
			resources.autodetect()
			buildArgs.addAll(application.applicationDefaultJvmArgs)
			buildArgs.add("--no-fallback")
		}
	}
}

val nativeAgentOutput =
	layout.buildDirectory.dir("native/agent-output")

tasks.register<JavaExec>("generateNativeMetadata") {
	group = "native image"
	description = "Runs the application with the GraalVM tracing agent and saves metadata"

	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("com.github.techs_sus.NativeMetadataMainKt")

	jvmArgs(
		*application.applicationDefaultJvmArgs.toList().toTypedArray(),
		"-agentlib:native-image-agent=config-output-dir=${nativeAgentOutput.get().asFile.absolutePath}",
	)

	doFirst {
		val outputDir = nativeAgentOutput.get().asFile

		delete(outputDir)
		outputDir.mkdirs()

		logger.lifecycle("Native Image agent output: $outputDir")
	}

	doLast {
		val destination =
			layout.projectDirectory
				.dir("src/main/resources/META-INF/native-image")
				.asFile

		destination.mkdirs()

		copy {
			from(nativeAgentOutput)
			into(destination)
		}

		logger.lifecycle("Copied Native Image metadata to: $destination")
	}
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

	implementation(libs.mordant)
	implementation(libs.mordant.coroutines)

	implementation(libs.slf4j.nop)

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
