#include "face_core.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <vector>

static void check(bool value, const char* label) {
    if (!value) { std::fprintf(stderr, "FAIL: %s\n", label); std::exit(1); }
}

int main(int argc, char** argv) {
    FaceCore detector;
    float out[4];
    if (argc == 4) {
        int w = std::atoi(argv[2]), h = std::atoi(argv[3]);
        check(w >= 32 && w <= 320 && h >= 32 && h <= 320, "fixture dimensions");
        std::vector<uint8_t> frame(w * h * 3 / 2);
        std::ifstream input(argv[1], std::ios::binary);
        check(bool(input.read(reinterpret_cast<char*>(frame.data()), frame.size())), "fixture read");
        auto start = std::chrono::steady_clock::now();
        for (int i = 0; i < 10; ++i) check(detector.detect(frame.data(), w, h, out), "fixture inference");
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count() / 10;
        std::printf("faces=%.0f confidence=%.2f center=(%.3f,%.3f) host_mean_ms=%.2f\n", out[3], out[2], out[0], out[1], ms);
        check(out[3] >= 1 && out[2] >= .5f, "positive face fixture");
        return 0;
    }
    check(!detector.detect(nullptr, 192, 144, out), "null rejected");
    std::vector<uint8_t> frame(320 * 320 * 3 / 2, 128);
    check(!detector.detect(frame.data(), 191, 144, out), "odd rejected");
    check(!detector.detect(frame.data(), 640, 480, out), "oversize rejected");
    for (int size : {32, 128, 160, 192, 224, 256, 288, 320, 192}) {
        int h = size == 32 ? 32 : size * 3 / 4;
        std::fill(frame.begin(), frame.begin() + size * h, 16);
        std::fill(frame.begin() + size * h, frame.end(), 128);
        for (int i = 0; i < 3; ++i) {
            check(detector.detect(frame.data(), size, h, out), "blank inference");
            check(out[3] == 0 && out[2] == 0, "blank no faces / stale output cleared");
        }
    }
    std::puts("Native NV21 conversion, detection and buffer reuse tests passed");
}
