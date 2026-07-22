plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lanxin.refactor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lanxin.refactor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-mnn"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // MNN + sherpa-onnx 可能各带 c++_shared / onnxruntime
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so"
            )
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// sherpa AAR 由 :voice 模块 downloadSherpaOnnxAar 提供（api files），app 不重复下载
// 确保 assemble 前 voice 已 preBuild
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(":voice:downloadSherpaOnnxAar")
}

dependencies {
    implementation(project(":companion"))
    implementation(project(":local-llm-domain"))
    implementation(project(":core-memory"))
    implementation(project(":local-llm-core"))
    // voice 以 api 传递 sherpa-onnx AAR（含 so）
    implementation(project(":voice"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
