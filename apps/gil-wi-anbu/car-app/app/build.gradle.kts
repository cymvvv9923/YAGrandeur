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
        // 앱만으로 동작하는 시연 버전 (브랜치 v002-standalone). 서버 없이 앱 안(LocalServer)에서 모든 기능이 돈다.
        buildConfigField("boolean", "STANDALONE", "true")
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
    // 말로 대답 받기 (앱이 먼저 묻는 흐름). 지금은 검증(ProbeReceiver)에만 쓴다
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    // 메인 안부 지도 (벡터 지도 엔진, BSD-2). 지도 자료는 OpenFreeMap(OpenStreetMap), 스타일은 assets/map에서 직접 정한다.
    implementation("org.maplibre.gl:android-sdk:11.5.2")
}
