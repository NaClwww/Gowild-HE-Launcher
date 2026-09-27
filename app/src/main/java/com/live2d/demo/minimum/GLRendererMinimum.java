/*
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at http://live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.live2d.demo.minimum;

import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLRendererMinimum implements GLSurfaceView.Renderer {
    private boolean firstSurface = true;

    // Called at initialization (when the drawing context is lost and recreated).
    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        LAppMinimumDelegate.getInstance().onSurfaceCreated();
        // 上下文重建（如扫码往返）后贴图/渲染器失效：整模重载。首次创建由 manager 构造器加载，跳过。
        if (!firstSurface) {
            LAppMinimumLive2DManager.getInstance().reloadCurrentModel();
        }
        firstSurface = false;
    }

    // Mainly called when switching between landscape and portrait.
    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        LAppMinimumDelegate.getInstance().onSurfaceChanged(width, height);
    }

    // Called repeatedly for drawing.
    @Override
    public void onDrawFrame(GL10 unused) {
        LAppMinimumDelegate.getInstance().run();
    }
}
