plugins {
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-tiatoolbox"
    version = "0.2.0"
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
