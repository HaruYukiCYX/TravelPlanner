plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.harukayuki.travelplanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.harukayuki.travelplanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 必须开启这个，否则找不到 ActivityMainBinding
    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // --- 高德地图解决方案：使用 v9 系列稳定版，避开 v10 的冲突坑 ---

    // 1. 3D 地图 (9.5.0 是非常稳定的版本)
    implementation("com.amap.api:3dmap:9.5.0")

    // 2. 搜索功能 (使用 9.4.5，并强制排除可能重复的基础库)
    implementation("com.amap.api:search:9.4.5") {
        exclude(group = "com.amap.api", module = "gbl")
    }

    // --- Room 数据库 ---
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

}