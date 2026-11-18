package com.github.techs_sus

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection
import java.util.zip.ZipFile
import kotlin.io.path.extension

private const val CLASS_PREFIX = "org/jcodec/containers/mp4/boxes/"
private const val BOX_PACKAGE = "org.jcodec.containers.mp4.boxes"

/**
 * jcodec parses and rewrites MP4 boxes reflectively.
 *
 * jcodec's box methods resolve box types through `Class#getConstructor(Header.class)`, so we must declare every concrete box
 * to be registered for reflection or else `AudioFileIO.read` will throw a `NoSuchMethodException`.
 *
 * Since reachability-metadata.json has no wildcard syntax for reflection types, and the tracing agent can only
 * record the box types that happen to appear in the playlist it traced against, this is the best way
 * to track all possible mp4 boxes.
 */
class JcodecBoxReflectionFeature : Feature {
	override fun getDescription() =
		"registers every org.jcodec.containers.mp4.boxes type for reflection"

	override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
		val header = access.findClassByName("$BOX_PACKAGE.Header") ?: return

		val registered = boxTypeNames(access)
			// findClassByName resolves against the image class loader, so this also filters out classes
			// that this jaudiotagger version does not actually ship.
			.mapNotNull(access::findClassByName)
			.mapNotNull { type ->
				runCatching { type.getConstructor(header) }.getOrNull()
			}
			.onEach(RuntimeReflection::register)

		println("JcodecBoxReflectionFeature: registered ${registered.size} MP4 box types for reflection")
	}

	private fun boxTypeNames(access: Feature.BeforeAnalysisAccess): List<String> {
		return access.applicationClassPath
			.filter { it.extension == "jar" }
			.flatMap { jar ->
				ZipFile(jar.toFile()).use { zip ->
					// skip inner classes, jcodec looks them up by their outer type
					zip.entries()
						.asSequence()
						.filter { it.name.startsWith(CLASS_PREFIX) }
						.filter { it.name.endsWith(".class") }
						.filterNot { it.name.contains('$') }
						.map {
							it.name.removeSuffix(".class").replace('/', '.')
						}
						.toList()
				}
			}
			.distinct()
			.sorted()
	}
}
