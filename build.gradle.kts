import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

plugins {
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-tiatoolbox"
    version = "0.4.0"
    group = "io.github.qupath"
    description = "Run TIAToolbox models from QuPath via a Python sidecar."
    automaticModule = "qupath.extension.tiatoolbox"
}

dependencies {
    implementation(libs.bundles.qupath)
    implementation(libs.bundles.logging)
    implementation(libs.qupath.fxtras)
    implementation("net.sf.py4j:py4j:0.10.9.7")

    testImplementation(libs.junit)
}

// Bundle py4j into the extension jar — QuPath doesn't ship it. Gson and
// JavaFX are already provided by QuPath at runtime.
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("py4j-") }
            .map { zipTree(it) }
    })
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// ---------------------------------------------------------------------------
// Bundled Python runtime: ship uv binaries + sidecar source as JAR resources.
// ---------------------------------------------------------------------------

val uvVersion = "0.11.15"

// Per-platform: archive name on the uv release + the bundled file name we
// expose under RES_BASE in the JAR (matches RuntimeInstaller.uvResourceName).
val uvPlatforms = listOf(
    UvPlatform("uv-x86_64-unknown-linux-gnu.tar.gz",  "uv-linux-x86_64",   false),
    UvPlatform("uv-aarch64-apple-darwin.tar.gz",      "uv-macos-arm64",    false),
    UvPlatform("uv-x86_64-apple-darwin.tar.gz",       "uv-macos-x86_64",   false),
    UvPlatform("uv-x86_64-pc-windows-msvc.zip",       "uv-win-x86_64.exe", true),
)

val generatedRuntimeDir = layout.buildDirectory.dir("generated/runtime")
val runtimeResourcePrefix = "qupath/ext/tiatoolbox/runtime"

val downloadUvBinaries by tasks.registering {
    group = "runtime"
    description = "Download pinned uv binaries from astral-sh/uv and bundle as JAR resources."
    inputs.property("uvVersion", uvVersion)
    outputs.dir(generatedRuntimeDir.map { it.dir(runtimeResourcePrefix) })

    doLast {
        val outDir = generatedRuntimeDir.get().dir(runtimeResourcePrefix).asFile
        outDir.mkdirs()
        for (p in uvPlatforms) {
            val target = outDir.resolve(p.outName)
            if (target.exists() && target.length() > 0) {
                logger.info("uv binary already present: ${target.name}")
                continue
            }
            val url = "https://github.com/astral-sh/uv/releases/download/$uvVersion/${p.archive}"
            logger.lifecycle("Fetching $url")
            val tmpArchive = temporaryDir.resolve(p.archive)
            URI(url).toURL().openStream().use { input ->
                tmpArchive.outputStream().use { out -> input.copyTo(out) }
            }
            extractUvBinary(tmpArchive, p.isZip, target)
            if (!p.isZip) {
                target.setExecutable(true, false)
            }
            logger.lifecycle("Wrote ${target.name} (${target.length() / 1024} KB)")
        }
    }
}

val copyRuntimeTemplate by tasks.registering(Copy::class) {
    group = "runtime"
    description = "Copy runtime/pyproject.toml into the JAR resources."
    from(rootProject.file("runtime")) {
        include("pyproject.toml")
    }
    into(generatedRuntimeDir.map { it.dir(runtimeResourcePrefix) })
}

val copySidecarSources by tasks.registering {
    group = "runtime"
    description = "Copy the Python sidecar source into the JAR resources and write a MANIFEST."
    val sidecarSrc = rootProject.file("python")
    val sidecarOut = generatedRuntimeDir.map { it.dir("$runtimeResourcePrefix/sidecar") }
    inputs.dir(sidecarSrc)
    outputs.dir(sidecarOut)

    doLast {
        val dst = sidecarOut.get().asFile
        if (dst.exists()) dst.deleteRecursively()
        dst.mkdirs()
        val collected = mutableListOf<String>()
        sidecarSrc.walkTopDown()
            .filter { it.isFile }
            .filter { !it.path.contains("__pycache__") }
            .filter { !it.path.contains(".egg-info") }
            .filter { !it.name.endsWith(".pyc") }
            .filter { !it.path.contains("/build/") && !it.path.contains("/dist/") }
            .forEach { f ->
                val rel = sidecarSrc.toPath().relativize(f.toPath()).toString()
                    .replace('\\', '/')
                val out = dst.resolve(rel)
                out.parentFile.mkdirs()
                f.copyTo(out, overwrite = true)
                collected.add(rel)
            }
        collected.sort()
        dst.resolve("MANIFEST").writeText(collected.joinToString("\n") + "\n")
        logger.lifecycle("Sidecar: ${collected.size} files copied to $dst")
    }
}

sourceSets.named("main") {
    resources.srcDir(generatedRuntimeDir)
}

tasks.named("processResources") {
    dependsOn(downloadUvBinaries, copyRuntimeTemplate, copySidecarSources)
}

data class UvPlatform(val archive: String, val outName: String, val isZip: Boolean)

fun extractUvBinary(archive: java.io.File, isZip: Boolean, target: java.io.File) {
    target.parentFile.mkdirs()
    if (isZip) {
        ZipFile(archive).use { zip ->
            val entry = zip.entries().toList().firstOrNull { it.name.endsWith("uv.exe") }
                ?: error("uv.exe not found inside ${archive.name}")
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
    } else {
        // The archive is .tar.gz with one binary named `uv` inside a single dir.
        val tarStream = GZIPInputStream(archive.inputStream().buffered())
        extractUvFromTar(tarStream, target)
    }
}

// Minimal tar reader: walks ustar entries until it finds a regular file named "uv".
fun extractUvFromTar(input: java.io.InputStream, target: java.io.File) {
    val header = ByteArray(512)
    while (true) {
        val read = input.readNBytesSafely(header)
        if (read < 512) break
        if (header.all { it == 0.toByte() }) break

        val name = String(header, 0, 100, Charsets.US_ASCII).trimEnd(Char.MIN_VALUE).trimEnd()
        val sizeOctal = String(header, 124, 12, Charsets.US_ASCII)
            .trim().trimEnd(Char.MIN_VALUE).trim()
        val size = if (sizeOctal.isEmpty()) 0L else sizeOctal.toLong(8)
        val typeFlag = header[156].toInt().toChar()

        val isFile = typeFlag == '0' || typeFlag == 0.toChar()
        val baseName = name.substringAfterLast('/')

        if (isFile && baseName == "uv") {
            val out = target.outputStream().buffered()
            var remaining = size
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) error("Unexpected EOF reading uv binary from tar")
                out.write(buf, 0, n)
                remaining -= n
            }
            out.flush()
            out.close()
            // Skip the rest of the tar — we've got what we need.
            return
        }

        // Skip data + padding to next 512-byte boundary.
        var toSkip = size + ((512 - size % 512) % 512)
        while (toSkip > 0) {
            val n = input.skip(toSkip)
            if (n <= 0) break
            toSkip -= n
        }
    }
    error("uv binary not found in tar archive")
}

fun java.io.InputStream.readNBytesSafely(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = this.read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}
