#include "face_core.h"
#include <jni.h>
#include <new>

extern "C" JNIEXPORT jlong JNICALL
Java_com_live2d_demo_minimum_control_LocalFaceDetector_create(JNIEnv*, jclass) {
    try { return reinterpret_cast<jlong>(new FaceCore()); }
    catch (...) { return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_live2d_demo_minimum_control_LocalFaceDetector_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<FaceCore*>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_live2d_demo_minimum_control_LocalFaceDetector_detect(
        JNIEnv* env, jclass, jlong handle, jbyteArray frame, jint w, jint h, jfloatArray output) {
    if (!handle || !frame || !output || w < 32 || h < 32 || w > 320 || h > 320
        || (w & 1) || (h & 1) || env->GetArrayLength(frame) < w * h * 3 / 2
        || env->GetArrayLength(output) < 4) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(frame, nullptr);
    if (!bytes) return JNI_FALSE;
    float result[4] = {};
    bool ok = false;
    try { ok = reinterpret_cast<FaceCore*>(handle)->detect(reinterpret_cast<uint8_t*>(bytes), w, h, result); }
    catch (...) { ok = false; }
    env->ReleaseByteArrayElements(frame, bytes, JNI_ABORT);
    if (ok) env->SetFloatArrayRegion(output, 0, 4, result);
    return ok ? JNI_TRUE : JNI_FALSE;
}
