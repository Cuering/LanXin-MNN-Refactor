/*
 * Thin JNI: MNN::Transformer::Llm (libllm.so)
 * Package: com.lanxin.localllm.core.MnnBridge
 *
 * UTF-8 安全：绝不用 NewStringUTF（emoji 会 JNI Abort）。
 * 所有返回串走 newStringUtfSafe → UTF-16 → NewString。
 */
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>
#include "llm/llm.hpp"

#define LOG_TAG "MnnLanxinJni"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using MNN::Transformer::Llm;

namespace {
std::mutex g_mutex;
Llm* g_llm = nullptr;
std::string g_last_error;
std::atomic<bool> g_cancel{false};

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
    g_cancel.store(false);
}

/** Resolve model dir / config.json / llm.mnn → path expected by createLLM. */
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

/**
 * 返回从 pos 开始的 UTF-8 码点需要的字节数；非法首字节返回 -1。
 */
int utf8ExpectedLen(unsigned char lead) {
    if (lead <= 0x7F) return 1;
    if ((lead & 0xE0) == 0xC0) return 2;
    if ((lead & 0xF0) == 0xE0) return 3;
    if ((lead & 0xF8) == 0xF0) return 4;
    return -1;
}

bool isUtf8Cont(unsigned char b) {
    return (b & 0xC0) == 0x80;
}

/**
 * 校验 [data, data+len) 是否为合法 UTF-8（含 4 字节 emoji）。
 * 裸 0 视为非法。
 */
bool isValidUtf8Sequence(const char* data, size_t len) {
    if (len == 0) return true;
    size_t i = 0;
    while (i < len) {
        auto lead = static_cast<unsigned char>(data[i]);
        if (lead == 0x00) return false;
        int need = utf8ExpectedLen(lead);
        if (need < 0 || i + static_cast<size_t>(need) > len) return false;
        for (int k = 1; k < need; ++k) {
            if (!isUtf8Cont(static_cast<unsigned char>(data[i + static_cast<size_t>(k)])))
                return false;
        }
        if (need == 2 && lead < 0xC2) return false;
        if (need == 4 && lead > 0xF4) return false;
        i += static_cast<size_t>(need);
    }
    return true;
}

std::string sanitizeToValidUtf8(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0, n = in.size();
    while (i < n) {
        auto lead = static_cast<unsigned char>(in[i]);
        if (lead == 0x00) { out.push_back(' '); ++i; continue; }
        int need = utf8ExpectedLen(lead);
        if (need < 0 || i + static_cast<size_t>(need) > n) {
            out.append("\xEF\xBF\xBD"); ++i; continue;
        }
        bool ok = true;
        for (int k = 1; k < need; ++k) {
            if (!isUtf8Cont(static_cast<unsigned char>(in[i + static_cast<size_t>(k)]))) { ok = false; break; }
        }
        if (ok && need == 2 && lead < 0xC2) ok = false;
        if (ok && need == 4 && lead > 0xF4) ok = false;
        if (!ok) { out.append("\xEF\xBF\xBD"); ++i; continue; }
        out.append(in, i, static_cast<size_t>(need));
        i += static_cast<size_t>(need);
    }
    return out;
}

/**
 * UTF-8 → UTF-16，再 NewString。
 * 关键：绝不用 NewStringUTF（emoji 会 JNI Abort / SIGABRT）。
 */
jstring newStringUtfSafe(JNIEnv* env, const std::string& s) {
    if (env == nullptr) return nullptr;
    const std::string& utf8 = isValidUtf8Sequence(s.data(), s.size()) ? s : sanitizeToValidUtf8(s);
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size() + 1);
    size_t i = 0, n = utf8.size();
    while (i < n) {
        auto lead = static_cast<unsigned char>(utf8[i]);
        if (lead == 0x00) { utf16.push_back(static_cast<jchar>(' ')); ++i; continue; }
        int need = utf8ExpectedLen(lead);
        if (need < 0 || i + static_cast<size_t>(need) > n) { utf16.push_back(0xFFFD); ++i; continue; }
        bool contOk = true;
        for (int k = 1; k < need; ++k) {
            if (!isUtf8Cont(static_cast<unsigned char>(utf8[i + static_cast<size_t>(k)]))) { contOk = false; break; }
        }
        if (!contOk) { utf16.push_back(0xFFFD); ++i; continue; }
        uint32_t cp = 0;
        if (need == 1) cp = lead;
        else if (need == 2) cp = (static_cast<uint32_t>(lead & 0x1F) << 6) | (static_cast<unsigned char>(utf8[i+1]) & 0x3F);
        else if (need == 3) cp = (static_cast<uint32_t>(lead & 0x0F) << 12) | (static_cast<uint32_t>(static_cast<unsigned char>(utf8[i+1]) & 0x3F) << 6) | (static_cast<unsigned char>(utf8[i+2]) & 0x3F);
        else cp = (static_cast<uint32_t>(lead & 0x07) << 18) | (static_cast<uint32_t>(static_cast<unsigned char>(utf8[i+1]) & 0x3F) << 12) | (static_cast<uint32_t>(static_cast<unsigned char>(utf8[i+2]) & 0x3F) << 6) | (static_cast<unsigned char>(utf8[i+3]) & 0x3F);
        if (cp <= 0xFFFF) {
            if (cp >= 0xD800 && cp <= 0xDFFF) utf16.push_back(0xFFFD);
            else utf16.push_back(static_cast<jchar>(cp));
        } else if (cp <= 0x10FFFF) {
            uint32_t v = cp - 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (v >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (v & 0x3FF)));
        } else {
            utf16.push_back(0xFFFD);
        }
        i += static_cast<size_t>(need);
    }
    if (utf16.empty()) return env->NewString(nullptr, 0);
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
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
        return newStringUtfSafe(env, out);
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
    return newStringUtfSafe(env, g_last_error);
}

JNIEXPORT jboolean JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeIsLoaded(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_llm != nullptr ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
