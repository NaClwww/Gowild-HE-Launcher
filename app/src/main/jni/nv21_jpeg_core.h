/*
 * NV21 → JPEG 编码核心（libjpeg-turbo raw YCbCr 通路），与 JNI 解耦以便主机单测。
 *
 * 为什么需要它：框架的 YuvImage.compressToJpeg 每次调用泄漏约 2.5KB native 堆
 * （实测 58MB/h，拉流整夜累积到 674MB 后被内核 LMK 在 adj 0 杀掉），app 侧无法修补；
 * 而绕开它之后唯一可用的替代（Java 侧 YUV→RGB + Bitmap.compress）太慢
 * （实测转换 72ms + 编码 52ms 每帧，camera-pump 从 24% 顶到 95%）。
 *
 * 关键在于 NV21 本身就是 Y/Cb/Cr 4:2:0，和 JPEG 内部的存法一致，所以走
 * libjpeg-turbo 的 raw 数据通路（raw_data_in + JCS_YCbCr）可以**完全不做颜色
 * 空间转换**：只把平面按行拷成 libjpeg 要的排布（顺便完成镜像），DCT/Huffman
 * 交给它的 NEON 内核。
 *
 * 取值域：相机 NV21 的 Y 一般是 limited range(16-235)，JPEG 的 YCbCr 按约定
 * 也是 limited range，直通即正确——反而比 YuvImage 那条
 * NV21→RGB→YCbCr 的两次有损转换少一次损失。
 */
#ifndef NV21_JPEG_CORE_H
#define NV21_JPEG_CORE_H

typedef struct Nv21Encoder Nv21Encoder;

/* 尺寸须为正偶数（NV21 4:2:0 要求）；失败返回 NULL。 */
Nv21Encoder *nv21enc_create(int width, int height);

void nv21enc_destroy(Nv21Encoder *enc);

/*
 * 把 NV21（长度须 >= width*height*3/2）拆成 Y/Cb/Cr 平面并按 flipV/flipH 镜像。
 * 拆成独立一步是为了让 JNI 层能把「读 Java 数组」限制在 GetPrimitiveArrayCritical
 * 的临界区内（临界区里不得再调其它 JNI 函数，否则会破坏 GC）。
 */
void nv21enc_fill(Nv21Encoder *enc, const unsigned char *nv21, int flipV, int flipH);

/*
 * 用已填好的平面编码成 JPEG。
 * 返回 JPEG 字节数；0 表示失败。数据在内部缓冲里，有效期到下次 encode 或 destroy。
 */
unsigned long nv21enc_encode(Nv21Encoder *enc, int quality);

/* 上一次 encode 的结果指针（长度见其返回值）。 */
const unsigned char *nv21enc_data(const Nv21Encoder *enc);

#endif /* NV21_JPEG_CORE_H */
