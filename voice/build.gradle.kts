@file:Suppress("UnstableApiUsage")

import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// sherpa-onnx Android AAR（ASR + TTS 共用），构建期下载，不进 git
// 覆盖: SHERPA_ONNX_AAR=本地路径 或 SHERPA_ONNX_AAR_URL=下载 URL
// ---------------------------------------------------------------------------
val sherpaOnnxVersion = "1.13.4"
val sherpaAarFileName = "sherpa-onnx-static-link-onnxruntime-$sherpaOnnxVersion.aar"
val sherpaAarLocal = layout.projectDirectory.file("libs/$sherpaAarFileName").asFile
val sherpaDefaultUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/$sherpaAarFileName"
val sherpaMirrorUrl =
    "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/$sherpaAarFileName"

val downloadSherpaOnnxAar by tasks.registering {
    group = "lanxin"
    description = "Download sherpa-onnx Android AAR into voice/libs (not committed)"
    outputs.file(sherpaAarLocal)
    onlyIf {
        val override = System.getenv("SHERPA_ONNX_AAR")
        if (!override.isNullOrBlank()) {
            val src = file(override)
            if (src.isFile && src.length() > 1_000_000L) {
                if (src.absolutePath != sherpaAarLocal.absolutePath) {
                    sherpaAarLocal.parentFile.mkdirs()
                    src.copyTo(sherpaAarLocal, overwrite = true)
                }
                return@onlyIf false
            }
        }
        !(sherpaAarLocal.isFile && sherpaAarLocal.length() > 1_000_000L)
    }
    doLast {
        sherpaAarLocal.parentFile.mkdirs()
        val envUrl = System.getenv("SHERPA_ONNX_AAR_URL")
        val urls = buildList {
            if (!envUrl.isNullOrBlank()) add(envUrl)
            add(sherpaDefaultUrl)
            add(sherpaMirrorUrl)
        }
        var lastError: Exception? = null
        for (url in urls) {
            try {
                logger.lifecycle("Downloading sherpa-onnx AAR from $url")
                URI(url).toURL().openStream().use { input ->
                    Files.copy(input, sherpaAarLocal.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                require(sherpaAarLocal.isFile && sherpaAarLocal.length() > 1_000_000L) {
                    "Downloaded AAR too small: ${sherpaAarLocal.length()}"
                }
                logger.lifecycle(
                    "sherpa-onnx AAR ready: $sherpaAarLocal (${sherpaAarLocal.length()} bytes)"
                )
                return@doLast
            } catch (e: Exception) {
                lastError = e
                logger.warn("Failed $url: ${e.message}")
                sherpaAarLocal.delete()
            }
        }
        throw GradleException(
            "Unable to download sherpa-onnx AAR. Set SHERPA_ONNX_AAR or SHERPA_ONNX_AAR_URL. Last: ${lastError?.message}"
        )
    }
}

val sherpaAarForCompile: File = when {
    !System.getenv("SHERPA_ONNX_AAR").isNullOrBlank() &&
        file(System.getenv("SHERPA_ONNX_AAR")!!).isFile ->
        file(System.getenv("SHERPA_ONNX_AAR")!!)
    sherpaAarLocal.isFile && sherpaAarLocal.length() > 1_000_000L -> sherpaAarLocal
    else -> sherpaAarLocal
}

android {
    namespace = "com.lanxin.voice"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // AAR 含多 abi；消费方 app 已过滤
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/x86_64/libonnxruntime.so"
            )
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

tasks.named("preBuild").configure { dependsOn(downloadSherpaOnnxAar) }
afterEvaluate {
    listOf(
        "compileDebugKotlin",
        "compileReleaseKotlin",
        "compileDebugUnitTestKotlin",
        "compileReleaseUnitTestKotlin"
    ).forEach { name ->
        tasks.findByName(name)?.dependsOn(downloadSherpaOnnxAar)
    }
}

dependencies {
    if (sherpaAarForCompile.isFile && sherpaAarForCompile.length() > 1_000_000L) {
        api(files(sherpaAarForCompile))
    } else {
        api(files(sherpaAarLocal))
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
