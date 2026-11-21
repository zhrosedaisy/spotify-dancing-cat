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
