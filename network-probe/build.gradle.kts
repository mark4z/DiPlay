plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.diplay.networkprobe"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.diplay.networkprobe"
        minSdk = 28
        targetSdk = 37
        versionCode = 4
        versionName = "0.4-dual-tun"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies { testImplementation(libs.junit) }

