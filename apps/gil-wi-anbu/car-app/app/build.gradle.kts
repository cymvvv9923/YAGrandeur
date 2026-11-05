plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.gilwi.anbu"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.gilwi.anbu"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 에뮬레이터에서 PC의 서버(localhost:8090)는 10.0.2.2로 보인다
        buildConfigField("String", "SERVER_URL", "\"http://10.0.2.2:8090\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    useLibrary("android.car")
}
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // 일기의 발견 지도 (OpenStreetMap 타일, Apache 2.0)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
