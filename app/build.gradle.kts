plugins { id("com.android.application") }

android {
    namespace = "app.vynix"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.vynix"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.2.6-beta"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.10.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
}
