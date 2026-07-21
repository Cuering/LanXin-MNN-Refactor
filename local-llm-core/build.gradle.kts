@file:Suppress("UnstableApiUsage")

import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// MNN 3.6.0 Android prebuilt: arm64-v8a + armeabi-v7a (cpu/opencl/vulkan)
// so 不进 git；CI / preBuild 下载。覆盖: MNN_NATIVE_ZIP / MNN_NATIVE_URL
// ---------------------------------------------------------------------------
val mnnVersion = "3.6.0"
val mnnZipFileName = "mnn_${mnnVersion}_android_armv7_armv8_cpu_opencl_vulkan.zip"
val mnnZipLocal = layout.projectDirectory.file("libs/$mnnZipFileName").asFile
val mnnJniRoot = layout.projectDirectory.dir("src/main/jniLibs").asFile
val mnnAbis = listOf("arm64-v8a", "armeabi-v7a")
val mnnDefaultUrl =
    "https://github.com/alibaba/MNN/releases/download/$mnnVersion/$mnnZipFileName"
val mnnMirrorUrl =
    "https://ghfast.top/https://github.com/alibaba/MNN/releases/download/$mnnVersion/$mnnZipFileName"
val mnnRequiredSo = listOf(
    "libMNN.so",
    "libMNN_Express.so",
    "libllm.so",
    "libc++_shared.so"
)

fun mnnNativeReady(): Boolean {
    return mnnAbis.all { abi ->
        val dir = File(mnnJniRoot, abi)
        mnnRequiredSo.all { name ->
            val f = File(dir, name)
            f.isFile && f.length() > 10_000L
        }
    }
}

val downloadMnnNative by tasks.registering {
    group = "lanxin"
    description = "Download MNN 3.6.0 Android prebuilt so into jniLibs (not committed)"
    outputs.dir(mnnJniRoot)
    onlyIf { !mnnNativeReady() }
    doLast {
        mnnZipLocal.parentFile.mkdirs()
        mnnAbis.forEach { File(mnnJniRoot, it).mkdirs() }

        val overrideZip = System.getenv("MNN_NATIVE_ZIP")
        if (!overrideZip.isNullOrBlank()) {
            val src = file(overrideZip)
            require(src.isFile && src.length() > 1_000_000L) {
                "MNN_NATIVE_ZIP invalid: $overrideZip"
            }
            src.copyTo(mnnZipLocal, overwrite = true)
        }

        if (!(mnnZipLocal.isFile && mnnZipLocal.length() > 1_000_000L)) {
            val envUrl = System.getenv("MNN_NATIVE_URL")
            val urls = buildList {
                if (!envUrl.isNullOrBlank()) add(envUrl)
                add(mnnDefaultUrl)
                add(mnnMirrorUrl)
            }
            var lastError: Exception? = null
            var ok = false
            for (url in urls) {
                try {
                    logger.lifecycle("Downloading MNN native zip from $url")
                    URI(url).toURL().openStream().use { input ->
                        Files.copy(input, mnnZipLocal.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    require(mnnZipLocal.isFile && mnnZipLocal.length() > 1_000_000L) {
                        "Downloaded MNN zip too small: ${mnnZipLocal.length()}"
                    }
                    ok = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    logger.warn("Failed $url: ${e.message}")
                    mnnZipLocal.delete()
                }
            }
            if (!ok) {
                throw GradleException(
                    "Unable to download MNN native zip. Set MNN_NATIVE_ZIP or MNN_NATIVE_URL. Last: ${lastError?.message}"
                )
            }
        }

        val tmpDir = layout.buildDirectory.dir("mnn-extract").get().asFile
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        copy {
            from(zipTree(mnnZipLocal))
            into(tmpDir)
        }

        for (abi in mnnAbis) {
            val abiDir = tmpDir.walkTopDown()
                .firstOrNull { it.isDirectory && it.name == abi }
                ?: throw GradleException("$abi not found in $mnnZipFileName")
            val dest = File(mnnJniRoot, abi)
            dest.mkdirs()
            abiDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".so") }
                ?.forEach { so -> so.copyTo(File(dest, so.name), overwrite = true) }
            logger.lifecycle("MNN $abi ready: ${dest.list()?.size ?: 0} files")
        }

        require(mnnNativeReady()) {
            "MNN extract incomplete under $mnnJniRoot"
        }
    }
}

android {
    namespace = "com.lanxin.localllm.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so"
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild").configure { dependsOn(downloadMnnNative) }
afterEvaluate {
    listOf(
        "configureCMakeDebug",
        "configureCMakeRelWithDebInfo",
        "configureCMakeRelease",
        "externalNativeBuildDebug",
        "externalNativeBuildRelease",
        "buildCMakeDebug",
        "buildCMakeRelWithDebInfo",
        "buildCMakeRelease"
    ).forEach { name ->
        tasks.findByName(name)?.dependsOn(downloadMnnNative)
    }
    tasks.matching {
        it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake")
    }.configureEach { dependsOn(downloadMnnNative) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
