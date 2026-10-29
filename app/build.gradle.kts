plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.vynix"
    compileSdk = 35
    buildFeatures { compose = true; buildConfig = true }
    defaultConfig {
        val appleMusicDeveloperToken = providers.gradleProperty("APPLE_MUSIC_DEVELOPER_TOKEN")
            .orElse(providers.environmentVariable("APPLE_MUSIC_DEVELOPER_TOKEN"))
            .getOrElse("")
        buildConfigField("String", "APPLE_MUSIC_DEVELOPER_TOKEN", "\"${appleMusicDeveloperToken}\"")
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.0-beta3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    // SoundCloud delivers playable audio as HLS playlists; without this module ExoPlayer cannot decode those streams.
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    // Resolve real YouTube audio streams for the built-in player.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
