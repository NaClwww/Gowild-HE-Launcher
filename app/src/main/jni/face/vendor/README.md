Vendored from https://github.com/ShiqiYu/libfacedetection
commit acf7b254121927e7dced30e233a2e03119f28ea2 (BSD-3-Clause).

The four facedetectcnn source files, including the embedded YuNet weights, are
unmodified. facedetection_export.h is the upstream-documented static build shim.
License is also included in APK assets/licenses/libfacedetection.txt.

This is the author's native CNN implementation, not the exact OpenCV
face_detection_yunet_2023mar.onnx used by the retired Python backend.
We use one CPU worker, ARM NEON, no OpenMP, no OpenCV/ncnn runtime.
