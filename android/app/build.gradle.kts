import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

fun asQuoted(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

val iflytekAppId = (localProps.getProperty("IFLYTEK_APP_ID") ?: "").trim()
val iflytekApiKey = (localProps.getProperty("IFLYTEK_API_KEY") ?: "").trim()
val iflytekApiSecret = (localProps.getProperty("IFLYTEK_API_SECRET") ?: "").trim()
val iflytekWakeAbilityId = (localProps.getProperty("IFLYTEK_WAKE_ABILITY_ID") ?: "e867a88f2").trim()
val iflytekWakeEnabled = (localProps.getProperty("IFLYTEK_WAKE_ENABLE") ?: "false").trim().equals("true", ignoreCase = true)
val iflytekWakeThreshold = (localProps.getProperty("IFLYTEK_WAKE_THRESHOLD") ?: "0 0:800").trim()

android {
    namespace = "com.blind.v1"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blind.v1"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["BAIDU_MAP_AK"] = (project.findProperty("BAIDU_MAP_AK") as String?) ?: ""
        buildConfigField("String", "IFLYTEK_APP_ID", asQuoted(iflytekAppId))
        buildConfigField("String", "IFLYTEK_API_KEY", asQuoted(iflytekApiKey))
        buildConfigField("String", "IFLYTEK_API_SECRET", asQuoted(iflytekApiSecret))
        buildConfigField("String", "IFLYTEK_WAKE_ABILITY_ID", asQuoted(iflytekWakeAbilityId))
        buildConfigField("boolean", "IFLYTEK_WAKE_ENABLE", if (iflytekWakeEnabled) "true" else "false")
        buildConfigField("String", "IFLYTEK_WAKE_THRESHOLD", asQuoted(iflytekWakeThreshold))
    }
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("com.baidu.lbsyun:BaiduMapSDK_Map:7.6.4")
    implementation(files("libs/SparkChain.aar"))
    implementation(files("libs/Codec.aar"))
    implementation(files("libs/Msc.jar"))
    implementation("com.google.code.gson:gson:2.8.8")
}
