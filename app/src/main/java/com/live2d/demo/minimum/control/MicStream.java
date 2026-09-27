package com.live2d.demo.minimum.control;

import java.io.IOException;
import java.io.InputStream;

/**
 * 麦克风单客户端流：把 VoiceController 的 PCM 块包装成顺序字节流
 * （chunked 传输，Content-Type application/octet-stream，无封装帧）。
 * 由 NanoHTTPD 的发送循环驱动 read()；麦克风关闭/连续 5s 无数据 → EOF，
 * 客户端重连即恢复（隐式开启）。close() 时归还客户端计数。
 */
public class MicStream extends InputStream {
    private static final long POLL_TIMEOUT_MS = 1000;
    private static final int MAX_EMPTY_POLLS = 5;

    private final VoiceController voice;
    private final VoiceController.MicClient client;
    private byte[] pending = null;
    private int pos = 0;
    private boolean closed = false;
    private boolean unregistered = false;

    public MicStream(VoiceController voice) {
        this.voice = voice;
        this.client = voice.micAttach();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) return -1;
        if (pending == null || pos >= pending.length) {
            pending = nextBlock();
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
            voice.micDetach(client);
        }
        super.close();
    }

    /** 阻塞取下一块；连续多次空轮询（麦克风被关/无数据）→ null（EOF）。 */
    private byte[] nextBlock() {
        int empty = 0;
        while (!closed) {
            byte[] b = voice.pollMic(client, POLL_TIMEOUT_MS);
            if (b != null) return b;
            empty++;
            // 麦克风已关时先尝试隐式重开（客户端还挂着说明有需求）
            if (empty >= 2 && !voice.isMicOn()) {
                if (!voice.micStart(false, voice.getMicRate())) return null;
            }
            if (empty >= MAX_EMPTY_POLLS) return null;
        }
        return null;
    }
}
