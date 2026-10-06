package com.github.techs_sus

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection
import java.util.zip.ZipFile

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
		"Registers every org.jcodec.containers.mp4.boxes type for reflection, which jcodec requires"

	override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
		val boxPackage = "org.jcodec.containers.mp4.boxes"
		val header = access.findClassByName("$boxPackage.Header") ?: return

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
		val prefix = "org/jcodec/containers/mp4/boxes/"

		return access.applicationClassPath
			.filter { it.toString().endsWith(".jar") }
			.flatMap { jar ->
				ZipFile(jar.toFile()).use { zip ->
					buildList {
						val entries = zip.entries()
						while (entries.hasMoreElements()) {
							val entry = entries.nextElement()
							val name = entry.name
							// skip inner classes, jcodec looks them up by their outer type
							if (name.startsWith(prefix) && name.endsWith(".class") && !name.contains('$')) {
								add(name.removeSuffix(".class").replace('/', '.'))
							}
						}
					}
				}
			}
			.distinct()
			.sorted()
	}
}
