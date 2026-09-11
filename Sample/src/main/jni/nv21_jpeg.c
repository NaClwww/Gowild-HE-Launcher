/*
 * nv21_jpeg_core 的 JNI 绑定。编码逻辑与踩坑说明见 nv21_jpeg_core.c。
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "nv21_jpeg_core.h"

JNIEXPORT jlong JNICALL
Java_com_live2d_demo_minimum_control_Nv21JpegEncoder_create(JNIEnv *env, jclass clazz,
                                                            jint width, jint height) {
    return (jlong) (intptr_t) nv21enc_create(width, height);
}

JNIEXPORT void JNICALL
Java_com_live2d_demo_minimum_control_Nv21JpegEncoder_destroy(JNIEnv *env, jclass clazz,
                                                             jlong handle) {
    nv21enc_destroy((Nv21Encoder *) (intptr_t) handle);
}

JNIEXPORT jbyteArray JNICALL
Java_com_live2d_demo_minimum_control_Nv21JpegEncoder_encode(JNIEnv *env, jclass clazz,
                                                            jlong handle, jbyteArray nv21Arr,
                                                            jint quality, jboolean flipV,
                                                            jboolean flipH) {
    Nv21Encoder *enc = (Nv21Encoder *) (intptr_t) handle;
    if (enc == NULL || nv21Arr == NULL) {
        return NULL;
    }

    jbyte *nv21 = (*env)->GetPrimitiveArrayCritical(env, nv21Arr, NULL);
    if (nv21 == NULL) {
        return NULL;
    }
    /* 临界区内只做纯计算：这里不得调用任何会分配/触发 GC 的 JNI 函数 */
    nv21enc_fill(enc, (const unsigned char *) nv21, flipV ? 1 : 0, flipH ? 1 : 0);
    (*env)->ReleasePrimitiveArrayCritical(env, nv21Arr, nv21, JNI_ABORT);

    unsigned long len = nv21enc_encode(enc, quality);
    const unsigned char *data = nv21enc_data(enc);
    if (len == 0 || data == NULL) {
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) len);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize) len, (const jbyte *) data);
    }
    return out;
}
