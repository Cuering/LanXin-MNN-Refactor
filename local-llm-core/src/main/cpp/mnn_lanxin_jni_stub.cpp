#include <android/log.h>
#include <jni.h>
#define LOG_TAG "MnnLanxinJniStub"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeLoadModel(JNIEnv*, jobject, jstring) {
    ALOGE("stub abi: load unsupported");
    return JNI_FALSE;
}
JNIEXPORT jstring JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeGenerate(JNIEnv*, jobject, jstring, jint) {
    return nullptr;
}
JNIEXPORT void JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeUnload(JNIEnv*, jobject) {}
JNIEXPORT jstring JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF("stub_abi");
}
JNIEXPORT jboolean JNICALL
Java_com_lanxin_localllm_core_MnnBridge_nativeIsLoaded(JNIEnv*, jobject) {
    return JNI_FALSE;
}

}
