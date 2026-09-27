#include "face_core.h"
#include "vendor/facedetectcnn.h"
#include <algorithm>

FaceCore::FaceCore() : results(FACEDETECTION_RESULT_BUFFER_SIZE / sizeof(int)) {}

static uint8_t byteClamp(int x) { return static_cast<uint8_t>(std::max(0, std::min(255, x))); }

bool FaceCore::detect(const uint8_t* nv21, int w, int h, float* out) {
    std::fill(out, out + 4, 0.f);
    if (!nv21 || w < 32 || h < 32 || w > 320 || h > 320 || (w & 1) || (h & 1)) return false;
    bgr.resize(w * h * 3);
    // Only convert the small sampled frame; never convert a full camera image.
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            int uv = w * h + (y / 2) * w + (x & ~1);
            int v = int(nv21[uv]) - 128, u = int(nv21[uv + 1]) - 128;
            int l = 298 * std::max(0, int(nv21[y * w + x]) - 16);
            uint8_t* p = &bgr[(y * w + x) * 3];
            p[0] = byteClamp((l + 516 * u + 128) >> 8);
            p[1] = byteClamp((l - 100 * u - 208 * v + 128) >> 8);
            p[2] = byteClamp((l + 409 * v + 128) >> 8);
        }
    }
    int* faces = facedetect_cnn(reinterpret_cast<uint8_t*>(results.data()), bgr.data(), w, h, w * 3);
    if (!faces) return false;
    for (int i = 0; i < std::min(*faces, FACEDETECTION_RESULT_MAX_FACES); ++i) {
        const short* p = reinterpret_cast<const short*>(faces + 1) + FACEDETECTION_RESULT_STRIDE_SHORTS * i;
        if (p[0] < 50 || p[3] <= 0 || p[4] <= 0) continue;
        out[3] += 1.f;
        float confidence = p[0] / 100.f;
        if (confidence <= out[2]) continue;
        out[0] = std::max(-1.f, std::min(1.f, (p[1] + p[3] * .5f) * 2.f / w - 1.f));
        out[1] = std::max(-1.f, std::min(1.f, (p[2] + p[4] * .5f) * 2.f / h - 1.f));
        out[2] = confidence;
    }
    return true;
}
