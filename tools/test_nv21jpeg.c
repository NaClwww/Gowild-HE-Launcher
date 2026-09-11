/*
 * nv21_jpeg_core 的主机侧验证程序（不参与 Android 构建）。
 *
 * 用合成 NV21 帧验证 raw YCbCr 通路的三个易错点：
 *   1. 上下镜像方向（Y 行梯度，flipV=1 后输出顶部应是最亮行）
 *   2. Cb/Cr 顺序（NV21 是 V 在前；U=255/V=128 应解出偏蓝，反之偏红）
 *   3. 左右镜像（Y 列梯度，flipH=1 后输出左侧应是最亮列）
 *
 * 构建（用 libjpeg-turbo 源码目录，主机平台）：
 *   cc -O2 -I<ljt> -I<ljt>/build-host tools/test_nv21jpeg.c \
 *      Sample/src/main/jni/nv21_jpeg_core.c <ljt>/build-host/libjpeg.a -o /tmp/t
 */
#include "nv21_jpeg_core.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define W 640
#define H 480
#define FRAME ((size_t) W * H * 3 / 2)

static unsigned char *new_frame(void) {
    unsigned char *f = (unsigned char *) calloc(1, FRAME);
    if (f == NULL) {
        fprintf(stderr, "alloc failed\n");
        exit(1);
    }
    return f;
}

static void set_chroma(unsigned char *f, int u, int v) {
    unsigned char *uv = f + (size_t) W * H;
    for (size_t i = 0; i + 1 < (size_t) W * H / 2; i += 2) {
        uv[i] = (unsigned char) v;      /* NV21：V 在前 */
        uv[i + 1] = (unsigned char) u;
    }
}

static void set_y_rows(unsigned char *f) {          /* row0=16 → rowH-1=235 */
    for (int r = 0; r < H; r++) {
        memset(f + (size_t) r * W, 16 + r * 219 / (H - 1), W);
    }
}

static void set_y_cols(unsigned char *f) {          /* col0=16 → colW-1=235 */
    for (int r = 0; r < H; r++) {
        for (int c = 0; c < W; c++) {
            f[(size_t) r * W + c] = 16 + c * 219 / (W - 1);
        }
    }
}

static void dump(const char *path, const unsigned char *frame, int quality,
                 int flipV, int flipH) {
    Nv21Encoder *e = nv21enc_create(W, H);
    if (e == NULL) {
        fprintf(stderr, "create failed\n");
        exit(1);
    }
    nv21enc_fill(e, frame, flipV, flipH);
    clock_t t0 = clock();
    unsigned long n = nv21enc_encode(e, quality);
    double ms = (double) (clock() - t0) * 1000.0 / CLOCKS_PER_SEC;
    if (n == 0) {
        fprintf(stderr, "%s: encode FAILED\n", path);
        exit(1);
    }
    FILE *fp = fopen(path, "wb");
    if (fp == NULL || fwrite(nv21enc_data(e), 1, n, fp) != n) {
        fprintf(stderr, "%s: write failed\n", path);
        exit(1);
    }
    fclose(fp);
    printf("%-28s %6lu bytes  %6.1f ms  (flipV=%d flipH=%d)\n", path, n, ms, flipV, flipH);
    nv21enc_destroy(e);
}

int main(int argc, char **argv) {
    const char *dir = argc > 1 ? argv[1] : "/tmp/nv21test";
    char path[512];

    unsigned char *f = new_frame();

    /* 1) 上下镜像：行梯度 + 中性色度，flipV=1 */
    set_y_rows(f);
    set_chroma(f, 128, 128);
    snprintf(path, sizeof(path), "%s/t_vflip.jpg", dir);
    dump(path, f, 60, 1, 0);

    /* 对照组：不翻 */
    snprintf(path, sizeof(path), "%s/t_novflip.jpg", dir);
    dump(path, f, 60, 0, 0);

    /* 2) Cb/Cr 顺序：均匀 Y，只给 V 高 → 应偏红 */
    memset(f, 128, (size_t) W * H);
    set_chroma(f, 128, 240);
    snprintf(path, sizeof(path), "%s/t_vhigh.jpg", dir);
    dump(path, f, 90, 0, 0);

    /* 只给 U 高 → 应偏蓝 */
    set_chroma(f, 240, 128);
    snprintf(path, sizeof(path), "%s/t_uhigh.jpg", dir);
    dump(path, f, 90, 0, 0);

    /* 3) 左右镜像：列梯度，flipH=1 */
    set_y_cols(f);
    set_chroma(f, 128, 128);
    snprintf(path, sizeof(path), "%s/t_hflip.jpg", dir);
    dump(path, f, 60, 0, 1);

    free(f);
    return 0;
}
