plugins {
    application
    // JavaFX draws the transparent cat window (smooth, GPU-backed; Swing flickered on macOS)
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.dancingcat"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Parses JSON responses from Spotify and LRCLIB
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
}

javafx {
    version = "25.0.5"
    modules = listOf("javafx.controls")
}

// Runs on JDK 25 or newer.
tasks.withType<JavaCompile> {
    options.release = 25
    options.encoding = "UTF-8"
}

application {
    mainClass = "com.dancingcat.Main"
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "--enable-native-access=javafx.graphics")
}

// On macOS, build the small Swift helper that captures Spotify's audio (build/native/audiotap).
// If it can't be built the app still works, it just falls back to a fixed tempo.
val buildAudioTap by tasks.registering(Exec::class) {
    description = "Compiles the ScreenCaptureKit audio helper"
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    inputs.files("native/AudioTap.swift", "native/build.sh")
    outputs.file("build/native/audiotap")
    commandLine("sh", "native/build.sh", "build/native/audiotap")
}
tasks.named("classes") { dependsOn(buildAudioTap) }

// ---------------------------------------------------------------------------------------------
// Desktop app: ./gradlew installApp builds "Dancing Cat.app" (with its own Java runtime) via
// jpackage and installs it to ~/Applications. Settings live in
// ~/Library/Application Support/Dancing Cat (your config.properties / tokens.json are copied there).

val appName = "Dancing Cat"
val appInputDir = layout.buildDirectory.dir("jpackage-input")
val appOutputDir = layout.buildDirectory.dir("app")

val prepareAppInput by tasks.registering(Sync::class) {
    dependsOn(tasks.jar, buildAudioTap)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    from(layout.buildDirectory.file("native/audiotap"))
    into(appInputDir)
}

val packageApp by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds '$appName.app' with jpackage into build/app"
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    dependsOn(prepareAppInput)
    val out = appOutputDir.get().asFile
    doFirst { File(out, "$appName.app").deleteRecursively(); out.mkdirs() }
    executable = File(System.getProperty("java.home"), "bin/jpackage").path
    args(
        "--type", "app-image",
        "--name", appName,
        "--dest", out.path,
        "--input", appInputDir.get().asFile.path,
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", "com.dancingcat.Main",
        "--icon", "packaging/DancingCat.icns",
        "--app-version", "1.0.0",
        "--mac-package-identifier", "com.dancingcat.app",
        "--add-modules", listOf(
            "java.base", "java.desktop", "java.logging", "java.management", "java.naming",
            "java.net.http", "java.prefs", "java.sql", "jdk.httpserver", "jdk.unsupported",
            "jdk.crypto.ec", "jdk.charsets", "jdk.localedata"
        ).joinToString(","),
        "--java-options", "-Djava.awt.headless=true",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Ddancingcat.audiotap=\$APPDIR/audiotap"
    )
}

val installApp by tasks.registering {
    group = "distribution"
    description = "Installs '$appName.app' to ~/Applications and copies your settings next to it"
    dependsOn(packageApp)
    doLast {
        val home = System.getProperty("user.home")
        val target = File(home, "Applications/$appName.app")
        target.parentFile.mkdirs()
        target.deleteRecursively()
        // ditto keeps permissions, symlinks and code signatures intact
        val proc = ProcessBuilder("ditto", File(appOutputDir.get().asFile, "$appName.app").path, target.path)
            .inheritIO().start()
        check(proc.waitFor() == 0) { "copying the app failed" }

        val data = File(home, "Library/Application Support/$appName").apply { mkdirs() }
        for (name in listOf("config.properties", "tokens.json")) {
            val src = file(name)
            val dst = File(data, name)
            if (src.exists() && !dst.exists()) {
                src.copyTo(dst)
                dst.setReadable(false, false); dst.setReadable(true, true)
                dst.setWritable(false, false); dst.setWritable(true, true)
            }
        }
        println("Installed: ${target.path}")
        println("Settings:  ${data.path}")
    }
}
