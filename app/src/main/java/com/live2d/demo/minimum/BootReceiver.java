package com.live2d.demo.minimum;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启：立即拉起桌面 Activity。不能延迟——广播结束后进程无组件驻留，
 * 会被 AMS 按空进程回收（"Killing ... empty"），定时器永远走不到。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i(TAG, "BOOT_COMPLETED, starting now");
        Intent i = new Intent(context, MainActivityMinimum.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
            Log.i(TAG, "launcher activity started");
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
        }
    }
}
