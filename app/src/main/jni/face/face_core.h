#pragma once
#include <cstdint>
#include <vector>

// One instance, one worker. Scratch buffers survive between frames.
class FaceCore {
public:
    FaceCore();
    // NV21 already reduced to the detection size and oriented like camera/frame.
    // out: normalized center x/y, confidence, accepted face count.
    bool detect(const uint8_t* nv21, int width, int height, float* out);
private:
    std::vector<uint8_t> bgr;
    std::vector<int> results;
};
