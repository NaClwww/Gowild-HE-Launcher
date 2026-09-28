package com.live2d.demo.minimum.l3d;

/**
 * l3d 专用的列主序 4x4 矩阵运算（glTF 与 GLES 同为列主序，数组即 GL 语义）。
 * 全部静态方法写 out，不分配。与 Live2D 无关，不引入 Cubism 依赖。
 */
final class L3dMat {
    private L3dMat() {
    }

    public static void identity(float[] out) {
        for (int i = 0; i < 16; i++) out[i] = 0f;
        out[0] = out[5] = out[10] = out[15] = 1f;
    }

    /** out = a * b（列主序，out 独立数组）。 */
    public static void mul(float[] out, float[] a, float[] b) {
        mulOffset(out, 0, a, 0, b, 0);
    }

    /** out[outOff..] = a[aOff..] * b[bOff..]，支持同一大数组内的偏移访问（关节调色板用）。 */
    public static void mulOffset(float[] out, int outOff, float[] a, int aOff, float[] b, int bOff) {
        float r0 = a[aOff] * b[bOff] + a[aOff + 4] * b[bOff + 1] + a[aOff + 8] * b[bOff + 2] + a[aOff + 12] * b[bOff + 3];
        float r1 = a[aOff + 1] * b[bOff] + a[aOff + 5] * b[bOff + 1] + a[aOff + 9] * b[bOff + 2] + a[aOff + 13] * b[bOff + 3];
        float r2 = a[aOff + 2] * b[bOff] + a[aOff + 6] * b[bOff + 1] + a[aOff + 10] * b[bOff + 2] + a[aOff + 14] * b[bOff + 3];
        float r3 = a[aOff + 3] * b[bOff] + a[aOff + 7] * b[bOff + 1] + a[aOff + 11] * b[bOff + 2] + a[aOff + 15] * b[bOff + 3];
        float c0 = a[aOff] * b[bOff + 4] + a[aOff + 4] * b[bOff + 5] + a[aOff + 8] * b[bOff + 6] + a[aOff + 12] * b[bOff + 7];
        float c1 = a[aOff + 1] * b[bOff + 4] + a[aOff + 5] * b[bOff + 5] + a[aOff + 9] * b[bOff + 6] + a[aOff + 13] * b[bOff + 7];
        float c2 = a[aOff + 2] * b[bOff + 4] + a[aOff + 6] * b[bOff + 5] + a[aOff + 10] * b[bOff + 6] + a[aOff + 14] * b[bOff + 7];
        float c3 = a[aOff + 3] * b[bOff + 4] + a[aOff + 7] * b[bOff + 5] + a[aOff + 11] * b[bOff + 6] + a[aOff + 15] * b[bOff + 7];
        float c0b = a[aOff] * b[bOff + 8] + a[aOff + 4] * b[bOff + 9] + a[aOff + 8] * b[bOff + 10] + a[aOff + 12] * b[bOff + 11];
        float c1b = a[aOff + 1] * b[bOff + 8] + a[aOff + 5] * b[bOff + 9] + a[aOff + 9] * b[bOff + 10] + a[aOff + 13] * b[bOff + 11];
        float c2b = a[aOff + 2] * b[bOff + 8] + a[aOff + 6] * b[bOff + 9] + a[aOff + 10] * b[bOff + 10] + a[aOff + 14] * b[bOff + 11];
        float c3b = a[aOff + 3] * b[bOff + 8] + a[aOff + 7] * b[bOff + 9] + a[aOff + 11] * b[bOff + 10] + a[aOff + 15] * b[bOff + 11];
        float c0c = a[aOff] * b[bOff + 12] + a[aOff + 4] * b[bOff + 13] + a[aOff + 8] * b[bOff + 14] + a[aOff + 12] * b[bOff + 15];
        float c1c = a[aOff + 1] * b[bOff + 12] + a[aOff + 5] * b[bOff + 13] + a[aOff + 9] * b[bOff + 14] + a[aOff + 13] * b[bOff + 15];
        float c2c = a[aOff + 2] * b[bOff + 12] + a[aOff + 6] * b[bOff + 13] + a[aOff + 10] * b[bOff + 14] + a[aOff + 14] * b[bOff + 15];
        float c3c = a[aOff + 3] * b[bOff + 12] + a[aOff + 7] * b[bOff + 13] + a[aOff + 11] * b[bOff + 14] + a[aOff + 15] * b[bOff + 15];
        out[outOff] = r0; out[outOff + 1] = r1; out[outOff + 2] = r2; out[outOff + 3] = r3;
        out[outOff + 4] = c0; out[outOff + 5] = c1; out[outOff + 6] = c2; out[outOff + 7] = c3;
        out[outOff + 8] = c0b; out[outOff + 9] = c1b; out[outOff + 10] = c2b; out[outOff + 11] = c3b;
        out[outOff + 12] = c0c; out[outOff + 13] = c1c; out[outOff + 14] = c2c; out[outOff + 15] = c3c;
    }

    /** TRS：平移 t、四元数 r（glTF 序 x,y,z,w）、缩放 s。任一可 null（取单位）。 */
    public static void trs(float[] out, float[] t, float[] r, float[] s) {
        float x = r != null ? r[0] : 0f, y = r != null ? r[1] : 0f;
        float z = r != null ? r[2] : 0f, w = r != null ? r[3] : 1f;
        float x2 = x + x, y2 = y + y, z2 = z + z;
        float xx = x * x2, xy = x * y2, xz = x * z2;
        float yy = y * y2, yz = y * z2, zz = z * z2;
        float wx = w * x2, wy = w * y2, wz = w * z2;
        float sx = s != null ? s[0] : 1f, sy = s != null ? s[1] : 1f, sz = s != null ? s[2] : 1f;
        out[0] = (1f - (yy + zz)) * sx; out[1] = (xy + wz) * sx; out[2] = (xz - wy) * sx; out[3] = 0f;
        out[4] = (xy - wz) * sy; out[5] = (1f - (xx + zz)) * sy; out[6] = (yz + wx) * sy; out[7] = 0f;
        out[8] = (xz + wy) * sz; out[9] = (yz - wx) * sz; out[10] = (1f - (xx + yy)) * sz; out[11] = 0f;
        out[12] = t != null ? t[0] : 0f;
        out[13] = t != null ? t[1] : 0f;
        out[14] = t != null ? t[2] : 0f;
        out[15] = 1f;
    }

    /** 透视投影（fovY 弧度）。 */
    public static void perspective(float[] out, float fovY, float aspect, float near, float far) {
        float f = 1f / (float) Math.tan(fovY / 2f);
        for (int i = 0; i < 16; i++) out[i] = 0f;
        out[0] = f / aspect;
        out[5] = f;
        out[10] = (far + near) / (near - far);
        out[11] = -1f;
        out[14] = 2f * far * near / (near - far);
    }

    /** 视空间后变换：绕画面中心缩放（zoom 大 = 模型大）+ 平移（x 已乘纵横比）。 */
    public static void viewPose(float[] out, float aspect, float x, float y, float zoom) {
        identity(out);
        out[0] = zoom;
        out[5] = zoom;
        out[12] = x * aspect;
        out[13] = y;
    }

    /**
     * 垂直镜像 = 左乘 S(1,-1)（投影光路上下颠倒补偿）：线性部分（[5]）与
     * 平移分量（[13]）一并翻转。只翻 [5] 会让 pose 的 y 平移逃过镜像，
     * 导致 l3d 与 Live2D 的 pose y 物理方向相反（Live2D 行向量序 v·T·Z·F·A
     * 中平移在翻转之前应用、同样被镜像）。
     */
    public static void flipV(float[] out) {
        out[5] = -out[5];
        out[13] = -out[13];
    }
}
