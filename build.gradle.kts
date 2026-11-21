plugins {
    application
}

group = "com.dancingcat"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Spotify'dan gelen JSON cevaplarını okumak için
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
    // Sonraki adımlarda: beat detection (TarsosDSP) buraya eklenecek.
}

// JDK 21 ve üstüyle çalışır (sende JDK 27 kurulu).
tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
}

application {
    mainClass = "com.dancingcat.Main"
    applicationDefaultJvmArgs = listOf("-Dapple.awt.application.name=Dancing Cat")
}

// macOS'ta Spotify sesini yakalayan küçük Swift yardımcısını derle (build/native/audiotap).
// Derlenemezse uygulama yine çalışır, sadece sabit tempoya düşer.
val buildAudioTap by tasks.registering(Exec::class) {
    description = "Compiles the ScreenCaptureKit audio helper"
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    inputs.files("native/AudioTap.swift", "native/build.sh")
    outputs.file("build/native/audiotap")
    commandLine("sh", "native/build.sh", "build/native/audiotap")
}
tasks.named("classes") { dependsOn(buildAudioTap) }
