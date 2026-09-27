package com.live2d.demo.minimum.control;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * MJPEG 单客户端流：把 CameraController 的最新 JPEG 帧包装成
 * multipart/x-mixed-replace 记录流（boundary=frame）。
 * 由 NanoHTTPD 的 chunked 发送循环驱动 read()；连续 3 次 5s 无新帧 → EOF，
 * 客户端（或 curl 重试循环）负责重连。close() 时归还客户端计数。
 */
public class MjpegStream extends InputStream {
    private static final String BOUNDARY = "frame";
    private static final java.nio.charset.Charset ASCII = StandardCharsets.US_ASCII;
    private static final long FRAME_TIMEOUT_NANOS = 5_000_000_000L;
    private static final int MAX_IDLE_ROUNDS = 3;

    private final CameraController cam;
    private final long startGeneration;
    private long lastSeq = -1;
    private int idleRounds = 0;
    private int pos = 0;
    private boolean closed = false;
    private boolean unregistered = false;

    /**
     * 复用的一条 multipart 记录缓冲。read() 总是在下一次 nextRecord() 之前把整条
     * 拷进调用方的数组，所以这里可以安全复用——原先每帧 new ByteArrayOutputStream
     * + toByteArray() 会往 ART large object space 里丢两个大对象垃圾。
     */
    private byte[] recordBuf = new byte[1 << 16];
    private int recordLen = 0;

    public MjpegStream(CameraController cam) {
        this.cam = cam;
        this.startGeneration = cam.getGeneration();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) return -1;
        if (recordLen == 0 || pos >= recordLen) {
            if (!nextRecord()) {
                close();
                return -1;
            }
        }
        int n = Math.min(len, recordLen - pos);
        System.arraycopy(recordBuf, pos, b, off, n);
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

    /** 阻塞取下一帧记录；无新帧/摄像头被关/超时 → false（EOF）。 */
    private boolean nextRecord() {
        long deadline = System.nanoTime() + FRAME_TIMEOUT_NANOS;
        while (!closed) {
            if (!cam.isOn()) {
                // 代数变了说明摄像头在中途被真实关闭（显式关/看门狗）→ 流终止，
                // 客户端重连时会走隐式开启路径重新拉起
                if (cam.getGeneration() != startGeneration) return false;
                if (!cam.turnOn(false)) return false;
            }
            long seq = cam.getFrameSeq();
            byte[] jpeg = cam.latestJpeg();
            if (jpeg != null && seq != lastSeq) {
                lastSeq = seq;
                idleRounds = 0;
                makeRecord(jpeg);
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (System.nanoTime() > deadline) {
                if (++idleRounds >= MAX_IDLE_ROUNDS) return false;
                deadline = System.nanoTime() + FRAME_TIMEOUT_NANOS;
            }
        }
        return false;
    }

    /** 把一帧 JPEG 包装成 boundary=frame 的记录写进复用缓冲，返回记录长度。 */
    private void makeRecord(byte[] jpeg) {
        String header = "--" + BOUNDARY + "\r\n"
            + "Content-Type: image/jpeg\r\n"
            + "Content-Length: " + jpeg.length + "\r\n\r\n";
        byte[] head = header.getBytes(ASCII);
        int need = head.length + jpeg.length + 2;
        if (recordBuf.length < need) {
            recordBuf = new byte[need];
        }
        System.arraycopy(head, 0, recordBuf, 0, head.length);
        System.arraycopy(jpeg, 0, recordBuf, head.length, jpeg.length);
        recordBuf[head.length + jpeg.length] = '\r';
        recordBuf[head.length + jpeg.length + 1] = '\n';
        recordLen = need;
        pos = 0;
    }
}
