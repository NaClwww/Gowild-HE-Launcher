package com.live2d.demo.minimum.control;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * MJPEG 单客户端流：把 CameraController 的最新 JPEG 帧包装成
 * multipart/x-mixed-replace 记录流（boundary=frame）。
 * 由 NanoHTTPD 的 chunked 发送循环驱动 read()；连续 3 次 5s 无新帧 → EOF，
 * 客户端（或 curl 重试循环）负责重连。close() 时归还客户端计数。
 */
public class MjpegStream extends InputStream {
    private static final String BOUNDARY = "frame";
    private static final long FRAME_TIMEOUT_NANOS = 5_000_000_000L;
    private static final int MAX_IDLE_ROUNDS = 3;

    private final CameraController cam;
    private final long startGeneration;
    private long lastSeq = -1;
    private int idleRounds = 0;
    private byte[] pending = null;
    private int pos = 0;
    private boolean closed = false;
    private boolean unregistered = false;

    public MjpegStream(CameraController cam) {
        this.cam = cam;
        this.startGeneration = cam.getGeneration();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) return -1;
        if (pending == null || pos >= pending.length) {
            pending = nextRecord();
            pos = 0;
            if (pending == null) {
                close();
                return -1;
            }
        }
        int n = Math.min(len, pending.length - pos);
        System.arraycopy(pending, pos, b, off, n);
        pos += n;
        return n;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (!unregistered) {
            unregistered = true;
            cam.unregisterClient();
        }
        super.close();
    }

    /** 阻塞取下一帧记录；无新帧/摄像头被关/超时 → null（EOF）。 */
    private byte[] nextRecord() {
        long deadline = System.nanoTime() + FRAME_TIMEOUT_NANOS;
        while (!closed) {
            if (!cam.isOn()) {
                // 代数变了说明摄像头在中途被真实关闭（显式关/看门狗）→ 流终止，
                // 客户端重连时会走隐式开启路径重新拉起
                if (cam.getGeneration() != startGeneration) return null;
                if (!cam.turnOn(false)) return null;
            }
            long seq = cam.getFrameSeq();
            byte[] jpeg = cam.latestJpeg();
            if (jpeg != null && seq != lastSeq) {
                lastSeq = seq;
                idleRounds = 0;
                return record(jpeg);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (System.nanoTime() > deadline) {
                if (++idleRounds >= MAX_IDLE_ROUNDS) return null;
                deadline = System.nanoTime() + FRAME_TIMEOUT_NANOS;
            }
        }
        return null;
    }

    private static byte[] record(byte[] jpeg) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(jpeg.length + 96);
        try {
            bos.write(("--" + BOUNDARY + "\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: " + jpeg.length + "\r\n\r\n").getBytes("US-ASCII"));
            bos.write(jpeg);
            bos.write("\r\n".getBytes("US-ASCII"));
        } catch (IOException ignored) {
        }
        return bos.toByteArray();
    }
}
