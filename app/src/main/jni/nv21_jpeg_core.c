#include "nv21_jpeg_core.h"

/* jpeglib.h 需要调用方先提供 size_t / FILE */
#include <stddef.h>
#include <stdio.h>
#include <jpeglib.h>
#include <setjmp.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Nv21Jpeg", __VA_ARGS__)
#else
#define LOGE(...) do { fprintf(stderr, "[Nv21Jpeg] "); fprintf(stderr, __VA_ARGS__); \
                       fprintf(stderr, "\n"); } while (0)
#endif

#define IMG_ALIGN 16       /* 4:2:0 的 iMCU 行高 = max_v_samp_factor * DCTSIZE */
#define CHROMA_ALIGN 8
#define MAX_Y_ROWS 32
#define MAX_C_ROWS 16

struct Nv21Encoder {
    struct jpeg_compress_struct cinfo;
    struct jpeg_error_mgr jerr;
    jmp_buf jump;

    int width, height;
    int yStride, cWidth, cHeight;
    int yRowsAlloc, cRowsAlloc;   /* 含底部填充行 */

    unsigned char *yPlane;
    unsigned char *cbPlane;
    unsigned char *crPlane;

    unsigned char *outBuf;        /* libjpeg 分配，本模块负责 free */
    unsigned long outSize;

    JSAMPROW yRowPtrs[MAX_Y_ROWS];
    JSAMPROW cbRowPtrs[MAX_C_ROWS];
    JSAMPROW crRowPtrs[MAX_C_ROWS];
    JSAMPARRAY planes[3];
};

static void error_exit_cb(j_common_ptr cinfo) {
    struct Nv21Encoder *e = (struct Nv21Encoder *) cinfo->client_data;
    char msg[JMSG_LENGTH_MAX];
    (*cinfo->err->format_message)(cinfo, msg);
    LOGE("libjpeg: %s", msg);
    /* libjpeg 默认 error_exit 直接 exit(1)：必须换成 longjmp，否则一帧坏数据杀进程 */
    longjmp(e->jump, 1);
}

static int round_up(int v, int a) {
    return (v + a - 1) / a * a;
}

Nv21Encoder *nv21enc_create(int width, int height) {
    if (width <= 0 || height <= 0 || (width & 1) || (height & 1)) {
        LOGE("bad size %dx%d", width, height);
        return NULL;
    }
    struct Nv21Encoder *e = (struct Nv21Encoder *) calloc(1, sizeof(struct Nv21Encoder));
    if (e == NULL) {
        return NULL;
    }

    e->width = width;
    e->height = height;
    e->yStride = width;
    e->cWidth = width / 2;
    e->cHeight = height / 2;
    e->yRowsAlloc = round_up(height, IMG_ALIGN);
    e->cRowsAlloc = round_up(e->cHeight, CHROMA_ALIGN);

    e->yPlane = (unsigned char *) malloc((size_t) e->yRowsAlloc * e->yStride);
    e->cbPlane = (unsigned char *) malloc((size_t) e->cRowsAlloc * e->cWidth);
    e->crPlane = (unsigned char *) malloc((size_t) e->cRowsAlloc * e->cWidth);
    if (e->yPlane == NULL || e->cbPlane == NULL || e->crPlane == NULL) {
        LOGE("plane alloc failed");
        nv21enc_destroy(e);
        return NULL;
    }

    e->cinfo.err = jpeg_std_error(&e->jerr);
    e->jerr.error_exit = error_exit_cb;
    e->cinfo.client_data = e;

    e->planes[0] = e->yRowPtrs;
    e->planes[1] = e->cbRowPtrs;
    e->planes[2] = e->crRowPtrs;

    return e;
}

void nv21enc_destroy(Nv21Encoder *e) {
    if (e == NULL) {
        return;
    }
    free(e->yPlane);
    free(e->cbPlane);
    free(e->crPlane);
    free(e->outBuf);
    free(e);
}

/* NV21 → Y/Cb/Cr 三个平面并完成镜像；底部不足一个 iMCU 的行用最后一行填充。 */
void nv21enc_fill(Nv21Encoder *e, const unsigned char *nv21, int flipV, int flipH) {
    if (e == NULL || nv21 == NULL) {
        return;
    }
    const int w = e->width;
    const int h = e->height;
    const unsigned char *uv = nv21 + (size_t) w * h;

    for (int y = 0; y < h; y++) {
        const unsigned char *src = nv21 + (size_t) (flipV ? (h - 1 - y) : y) * w;
        unsigned char *dst = e->yPlane + (size_t) y * w;
        if (flipH) {
            for (int x = 0; x < w; x++) {
                dst[x] = src[w - 1 - x];
            }
        } else {
            memcpy(dst, src, (size_t) w);
        }
    }
    for (int y = h; y < e->yRowsAlloc; y++) {
        memcpy(e->yPlane + (size_t) y * w, e->yPlane + (size_t) (h - 1) * w, (size_t) w);
    }

    const int cw = e->cWidth;
    const int ch = e->cHeight;
    for (int cy = 0; cy < ch; cy++) {
        const unsigned char *src = uv + (size_t) (flipV ? (ch - 1 - cy) : cy) * w;
        unsigned char *cb = e->cbPlane + (size_t) cy * cw;
        unsigned char *cr = e->crPlane + (size_t) cy * cw;
        if (flipH) {
            for (int p = 0; p < cw; p++) {
                int sp = (cw - 1 - p) * 2;
                cr[p] = src[sp];        /* NV21：V 在前，对应 JPEG 的 Cr */
                cb[p] = src[sp + 1];    /* 之后是 U，对应 Cb */
            }
        } else {
            for (int p = 0; p < cw; p++) {
                cr[p] = src[p * 2];
                cb[p] = src[p * 2 + 1];
            }
        }
    }
    for (int cy = ch; cy < e->cRowsAlloc; cy++) {
        memcpy(e->cbPlane + (size_t) cy * cw, e->cbPlane + (size_t) (ch - 1) * cw, (size_t) cw);
        memcpy(e->crPlane + (size_t) cy * cw, e->crPlane + (size_t) (ch - 1) * cw, (size_t) cw);
    }
}

unsigned long nv21enc_encode(Nv21Encoder *e, int quality) {
    if (e == NULL) {
        return 0;
    }
    struct jpeg_compress_struct *c = &e->cinfo;
    if (setjmp(e->jump)) {
        jpeg_destroy_compress(c);
        return 0;
    }

    free(e->outBuf);
    e->outBuf = NULL;
    e->outSize = 0;

    jpeg_create_compress(c);
    jpeg_mem_dest(c, &e->outBuf, &e->outSize);

    c->image_width = e->width;
    c->image_height = e->height;
    c->input_components = 3;
    c->in_color_space = JCS_YCbCr;
    jpeg_set_defaults(c);
    c->raw_data_in = TRUE;                    /* 关键：raw 通路，不做颜色转换 */
    jpeg_set_quality(c, quality, TRUE);
    c->comp_info[0].h_samp_factor = 2;        /* 4:2:0，与 NV21 排布一致 */
    c->comp_info[0].v_samp_factor = 2;
    c->comp_info[1].h_samp_factor = 1;
    c->comp_info[1].v_samp_factor = 1;
    c->comp_info[2].h_samp_factor = 1;
    c->comp_info[2].v_samp_factor = 1;
    jpeg_start_compress(c, TRUE);

    const int imcuRows = c->max_v_samp_factor * DCTSIZE;
    const int cRows = c->comp_info[1].v_samp_factor * DCTSIZE;

    while (c->next_scanline < c->image_height) {
        const int yBase = c->next_scanline;
        for (int i = 0; i < imcuRows; i++) {
            e->yRowPtrs[i] = e->yPlane + (size_t) (yBase + i) * e->yStride;
        }
        const int cBase = yBase / 2;
        for (int i = 0; i < cRows; i++) {
            e->cbRowPtrs[i] = e->cbPlane + (size_t) (cBase + i) * e->cWidth;
            e->crRowPtrs[i] = e->crPlane + (size_t) (cBase + i) * e->cWidth;
        }
        if (jpeg_write_raw_data(c, e->planes, imcuRows) != imcuRows) {
            LOGE("jpeg_write_raw_data short write");
            jpeg_destroy_compress(c);
            return 0;
        }
    }

    jpeg_finish_compress(c);
    jpeg_destroy_compress(c);

    if (e->outSize == 0 || e->outSize > (unsigned long) INT32_MAX) {
        return 0;
    }
    return e->outSize;
}

const unsigned char *nv21enc_data(const Nv21Encoder *e) {
    return e == NULL ? NULL : e->outBuf;
}
