/*
 * Thin JNI: MNN::Transformer::Llm (libllm.so)
 * Package: com.lanxin.localllm.core.MnnBridge
 */
#include <android/log.h>
#include <jni.h>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include "llm/llm.hpp"

#define LOG_TAG "MnnLanxinJni"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using MNN::Transformer::Llm;

namespace {
std::mutex g_mutex;
Llm* g_llm = nullptr;
std::string g_last_error;

void setError(const std::string& msg) {
    g_last_error = msg;
    ALOGE("%s", msg.c_str());
}
void clearError() { g_last_error.clear(); }
void unloadLocked() {
    if (g_llm != nullptr) {
        Llm::destroy(g_llm);
        g_llm = nullptr;
    }
}

std::string resolveConfigPath(const std::string& path) {
    if (path.empty()) return path;
    if (path.size() > 5 && path.substr(path.size() - 5) == ".json") return path;
    if (path.size() > 4 && path.substr(path.size() - 4) == ".mnn") return path;
    std::string base = path;
    while (!base.empty() && (base.back() == '/' || base.back() == '\\')) base.pop_back();
    const std::string candidates[] = {
        base + "/config.json",
        base + "/llm_config.json",
        base + "/llm.mnn.json",
        base + "/llm.mnn",
        base
    };
    for (const auto& c : candidates) {
        if (c == base) {
            FILE* m = fopen((base + "/llm.mnn").c_str(), "rb");
            if (m != nullptr) { fclose(m); return base; }
            continue;
        }
        FILE* f = fopen(c.c_str(), "rb");
        if (f != nullptr) { fclose(f); return c; }
    }
    return base + "/config.json";
}
} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeLoadModel(JNIEnv* env, jobject, jstring pathJ) {
    if (pathJ == nullptr) { setError("path_null"); return JNI_FALSE; }
    const char* pathC = env->GetStringUTFChars(pathJ, nullptr);
    if (pathC == nullptr) { setError("path_utf_null"); return JNI_FALSE; }
    std::string path(pathC);
    env->ReleaseStringUTFChars(pathJ, pathC);

    std::lock_guard<std::mutex> lock(g_mutex);
    unloadLocked();
    clearError();
    const std::string configPath = resolveConfigPath(path);
    ALOGI("nativeLoadModel path=%s config=%s", path.c_str(), configPath.c_str());

    Llm* llm = nullptr;
    try {
        llm = Llm::createLLM(configPath);
        if (llm == nullptr) { setError("createLLM_null:" + configPath); return JNI_FALSE; }
        if (!llm->load()) {
            Llm::destroy(llm);
            setError("llm_load_failed:" + configPath);
            return JNI_FALSE;
        }
        g_llm = llm;
        ALOGI("nativeLoadModel ok");
        return JNI_TRUE;
    } catch (const std::exception& e) {
        if (llm) Llm::destroy(llm);
        setError(std::string("load_exception:") + e.what());
        return JNI_FALSE;
    } catch (...) {
        if (llm) Llm::destroy(llm);
        setError("load_exception:unknown");
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeGenerate(JNIEnv* env, jobject, jstring promptJ, jint maxTokens) {
    if (promptJ == nullptr) return nullptr;
    const char* promptC = env->GetStringUTFChars(promptJ, nullptr);
    if (promptC == nullptr) return nullptr;
    std::string prompt(promptC);
    env->ReleaseStringUTFChars(promptJ, promptC);

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_llm == nullptr) { setError("not_loaded"); return nullptr; }
    int maxNew = static_cast<int>(maxTokens);
    if (maxNew < 1) maxNew = 1;
    if (maxNew > 4096) maxNew = 4096;

    try {
        std::ostringstream os;
        g_llm->response(prompt, &os, nullptr, maxNew);
        std::string out = os.str();
        if (out.empty() && g_llm->getContext() != nullptr) {
            out = g_llm->getContext()->generate_str;
        }
        clearError();
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception& e) {
        setError(std::string("generate_exception:") + e.what());
        return nullptr;
    } catch (...) {
        setError("generate_exception:unknown");
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeUnload(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unloadLocked();
    clearError();
}

JNIEXPORT jstring JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_last_error.empty()) return nullptr;
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeIsLoaded(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_llm != nullptr ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
