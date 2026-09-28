package com.live2d.demo.minimum.control;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 语音播放：/api/voice/play/*（TTS 下行，对齐记录待决策 #3 的落地）。
 *
 * NanoHTTPD 不收 chunked 请求体，流式播放拆成三段式 HTTP 分块上行：
 *   POST /api/voice/play/begin  {"rate":16000,"channels":1}   开会话（掐掉旧会话）
 *   POST /api/voice/play/chunk  body=raw PCM16LE（多次，Content-Length）
 *   POST /api/voice/play/end                                   输入收口（放完自动停）
 *   POST /api/voice/play/stop                                  立即掐断（切尾巴/抢话）
 *
 * 写路线：chunk 入有界队列 → 单写者线程阻塞式 AudioTrack.write——播放本身
 * 自带背压（放不完 → 队列满 → chunk POST 阻塞），反压整条 TTS 链，内存有界。
 *
 * 口型（RMS 喂 /api/control/lipsync）暂缓：writtenBytes 已按会话累计，将来自
 * AudioTrack 播放头折算进度即可接上。与 GL 无关，参照 VoiceController 单例。
 */
public class VoicePlayer {
    private static final String TAG = "VoicePlayer";

    private static final VoicePlayer INSTANCE = new VoicePlayer();

    public static VoicePlayer get() {
        return INSTANCE;
    }

    private VoicePlayer() {
    }

    /** 队列块数上限：块大小由调用方决定（aria-host 约 16KB），128 块远超一次应答。 */
    private static final int QUEUE_CHUNKS = 128;

    /** 结束哨兵（write 收到即排空收尾）。 */
    private static final byte[] EOF = new byte[0];

    /** EOF 后等播放头追平的上限：超时也收（防止坏数据卡死会话）。 */
    private static final long DRAIN_TIMEOUT_NANOS = 5_000_000_000L;

    private final Object lock = new Object();
    private AudioTrack track;
    private Thread writer;
    private BlockingQueue<byte[]> queue;
    private volatile boolean sessionOpen = false; // 写者线程轮询可见
    private int rate = 16000;
    private int channels = 1;
    private long writtenBytes = 0; // 本会话已 write 进 AudioTrack 的字节数（口型预留）
    private long queuedBytes = 0;
    private long sessions = 0;
    private String lastError;

    /** 开播放会话。rate 8000..48000、channels 1..2，越界夹紧；旧会话立即掐掉。 */
    public boolean begin(int rateHz, int ch) {
        rateHz = Math.max(8000, Math.min(48000, rateHz));
        ch = Math.max(1, Math.min(2, ch));
        stop();
        synchronized (lock) {
            int channelMask = ch == 2
                ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int minBuf = AudioTrack.getMinBufferSize(rateHz, channelMask,
                AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                lastError = "getMinBufferSize failed: " + minBuf;
                Log.e(TAG, lastError);
                return false;
            }
            AudioTrack t;
            try {
                t = new AudioTrack(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                    new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rateHz)
                        .setChannelMask(channelMask)
                        .build(),
                    Math.max(minBuf * 2, 8192),
                    AudioTrack.MODE_STREAM,
                    0); // AUDIO_SESSION_ID_GENERATE
            } catch (Exception e) {
                lastError = "AudioTrack create failed: " + e;
                Log.e(TAG, lastError);
                return false;
            }
            try {
                t.play();
            } catch (Exception e) {
                t.release();
                lastError = "AudioTrack play failed: " + e;
                Log.e(TAG, lastError);
                return false;
            }
            track = t;
            queue = new ArrayBlockingQueue<byte[]>(QUEUE_CHUNKS);
            writtenBytes = 0;
            queuedBytes = 0;
            rate = rateHz;
            channels = ch;
            sessions++;
            sessionOpen = true;
            lastError = null;
            writer = new Thread(this::writerLoop, "VoicePlayerWriter");
            writer.start();
            return true;
        }
    }

    /** 喂一块 PCM。阻塞入队（队列满 = 播放背压）；无会话返回 false。 */
    public boolean write(byte[] pcm) throws InterruptedException {
        if (pcm == null || pcm.length == 0) return true;
        BlockingQueue<byte[]> q;
        synchronized (lock) {
            if (!sessionOpen || queue == null) {
                lastError = "chunk without open session (begin first)";
                return false;
            }
            q = queue;
        }
        q.put(pcm); // 阻塞背压
        synchronized (lock) {
            queuedBytes += pcm.length;
        }
        return true;
    }

    /** 输入收口：写入端已完，播放排空后自动收尾。 */
    public void end() {
        BlockingQueue<byte[]> q;
        synchronized (lock) {
            if (!sessionOpen || queue == null) return;
            q = queue;
        }
        try {
            q.put(EOF);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
        }
    }

    /** 立即掐断：清队列、停写者、释放 AudioTrack（抢话/切尾巴）。幂等。 */
    public void stop() {
        Thread w;
        synchronized (lock) {
            if (!sessionOpen && track == null) return;
            sessionOpen = false;
            if (queue != null) queue.clear();
            w = writer;
            writer = null;
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                    track.release();
                } catch (Exception e) {
                    Log.w(TAG, "stop release: " + e);
                }
                track = null;
            }
            queue = null;
        }
        if (w != null) w.interrupt();
    }

    private void writerLoop() {
        // 队列引用一次性捕获：stop() 会把字段置 null，循环里重读会 NPE
        final BlockingQueue<byte[]> q;
        synchronized (lock) {
            q = queue;
        }
        if (q == null) return;
        try {
            while (true) {
                byte[] chunk = q.take();
                if (chunk == EOF || !sessionOpen) break;
                AudioTrack t;
                synchronized (lock) {
                    t = track;
                }
                if (t == null) break;
                int off = 0;
                while (off < chunk.length) {
                    int n = t.write(chunk, off, chunk.length - off); // 阻塞写
                    if (n <= 0) {
                        Log.e(TAG, "track.write returned " + n + ", aborting session");
                        stop();
                        return;
                    }
                    off += n;
                }
                synchronized (lock) {
                    writtenBytes += chunk.length;
                }
            }
            drainUntilPlayedOut();
        } catch (InterruptedException ignored) {
            // stop() 打断：设备已释放，直接退出
        } finally {
            synchronized (lock) {
                sessionOpen = false;
                if (track != null) {
                    try {
                        track.stop();
                        track.release();
                    } catch (Exception ignored) {
                    }
                    track = null;
                }
                queue = null;
                writer = null;
            }
        }
    }

    /** EOF 后等播放头追平已写字节（PCM16：字节/2/声道=帧），上限 5s。 */
    private void drainUntilPlayedOut() {
        long targetFrames;
        AudioTrack t;
        synchronized (lock) {
            t = track;
            targetFrames = writtenBytes / (2L * channels);
        }
        if (t == null) return;
        long deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            if (t.getPlaybackHeadPosition() >= targetFrames) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public boolean isPlaying() {
        synchronized (lock) {
            return sessionOpen || (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING);
        }
    }

    public JSONObject statusJson() {
        int qLen = 0;
        synchronized (lock) {
            qLen = queue != null ? queue.size() : 0;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("open", sessionOpen);
            o.put("rate", rate);
            o.put("channels", channels);
            o.put("queue_chunks", qLen);
            o.put("written_ms", writtenBytes * 1000L / (2L * channels * rate));
            o.put("sessions", sessions);
            if (lastError != null) o.put("last_error", lastError);
        } catch (Exception ignored) {
        }
        return o;
    }
}
