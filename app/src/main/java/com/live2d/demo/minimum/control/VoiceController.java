package com.live2d.demo.minimum.control;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语音采集：/api/voice/{name}/*（现阶段 name 只有 "mic"）。
 *
 * AudioRecord（VOICE_RECOGNITION 源，PCM16LE 单声道）采集线程 → 有界队列
 * → GET /stream 以 chunked 响应持续吐给 Mac 侧 ASR。
 * 生命周期：显式 on 常开；流打开为隐式开启，10s 无人读自动关；
 * 队列满丢最旧块并计 drops（ASR 对连续性敏感度低，宁可丢旧不阻塞采集）。
 */
public class VoiceController {
    private static final String TAG = "VoiceController";

    private static final long MIC_AUTO_OFF_NANOS = 10_000_000_000L;

    private static final VoiceController INSTANCE = new VoiceController();

    public static VoiceController get() {
        return INSTANCE;
    }

    private VoiceController() {
    }

    /** 支持的采集源名。 */
    public static boolean isKnownSource(String name) {
        return "mic".equals(name);
    }

    private final AtomicInteger micClientSeq = new AtomicInteger(0);
    private final java.util.concurrent.CopyOnWriteArrayList<MicClient> micClientsList =
        new java.util.concurrent.CopyOnWriteArrayList<MicClient>();
    private final AtomicLong micSeq = new AtomicLong(0);

    /** 每客户端独立队列：块广播给所有客户端，慢客户端丢自己的最旧块，不偷别人的。 */
    public static class MicClient {
        final int id;
        final ArrayBlockingQueue<byte[]> q = new ArrayBlockingQueue<byte[]>(24); // ~1s
        volatile long drops = 0;
        volatile long lastReadNanos = System.nanoTime();
        volatile long deliveredBytes = 0;

        MicClient(int id) {
            this.id = id;
        }
    }

    private volatile long producedBytes = 0;

    private static final long STALE_CLIENT_NANOS = 15_000_000_000L;

    private volatile boolean hasMic = true; // la0920 实测有麦克风（pm list features）
    private Thread micThread;
    private volatile AudioRecord micRecord;
    private volatile boolean micExplicit = false;
    private volatile int micRate = 16000;
    private volatile long micLastUseNanos = 0;

    public boolean hasMic() {
        return hasMic;
    }

    public boolean isMicOn() {
        return micRecord != null;
    }

    public boolean isMicExplicit() {
        return micExplicit;
    }

    public int getMicRate() {
        return micRate;
    }

    public int getMicClients() {
        return micClientsList.size();
    }

    public long getMicSeq() {
        return micSeq.get();
    }

    public long getMicDrops() {
        long sum = 0;
        for (MicClient c : micClientsList) sum += c.drops;
        return sum;
    }

    /** 开采集（显式或隐式）。速率变化或未开时重开 AudioRecord。 */
    public synchronized boolean micStart(boolean explicit, int rate) {
        micRate = Math.max(8000, Math.min(48000, rate));
        if (explicit) micExplicit = true;
        micLastUseNanos = System.nanoTime();
        if (micRecord != null) {
            if (micRecord.getSampleRate() != micRate) micStop(false); // 变速率需重开
            else return true;
        }
        int minBuf = AudioRecord.getMinBufferSize(micRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord minBufferSize failed");
            return false;
        }
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, micRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord create failed", e);
            return false;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            Log.e(TAG, "AudioRecord init failed");
            return false;
        }
        micRecord = rec;
        rec.startRecording();
        micThread = new Thread(new MicRunnable(rec), "voice-mic");
        micThread.start();
        Log.i(TAG, "mic on rate=" + micRate + " clients=" + micClientsList.size());
        return true;
    }

    public synchronized void micStop(boolean explicit) {
        if (explicit) micExplicit = false;
        AudioRecord rec = micRecord;
        if (rec == null) return;
        micRecord = null;
        try {
            rec.stop();
        } catch (Exception ignored) {
        }
        rec.release();
        Log.i(TAG, "mic off");
        // 各客户端队列不再投喂 → MicStream 5s 超时后 EOF（感知关闭）
    }

    /** 挂载一个客户端，返回其专属句柄（MicStream 持有并轮询）。 */
    public MicClient micAttach() {
        MicClient c = new MicClient(micClientSeq.incrementAndGet());
        micClientsList.add(c);
        micLastUseNanos = System.nanoTime();
        return c;
    }

    public void micDetach(MicClient c) {
        micClientsList.remove(c);
        micLastUseNanos = System.nanoTime();
    }

    /** 从客户端专属队列取块；null = 已被回收/麦克风关闭。 */
    public byte[] pollMic(MicClient c, long timeoutMs) {
        c.lastReadNanos = System.nanoTime();
        micLastUseNanos = c.lastReadNanos;
        try {
            return c.q.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private class MicRunnable implements Runnable {
        private final AudioRecord rec;

        MicRunnable(AudioRecord rec) {
            this.rec = rec;
        }

        @Override
        public void run() {
            int block = micRate / 25 * 2; // 40ms 块（样本数×2字节）
            byte[] buf = new byte[block];
            while (micRecord == rec) {
                long now = System.nanoTime();
                // 摘除僵死客户端（HTTP 线程已死但没走到 detach 的）
                for (MicClient c : micClientsList) {
                    if (now - c.lastReadNanos > STALE_CLIENT_NANOS) {
                        micClientsList.remove(c);
                        Log.i(TAG, "mic client #" + c.id + " reaped (stale)");
                    }
                }
                if (!micExplicit && micClientsList.isEmpty()
                    && now - micLastUseNanos > MIC_AUTO_OFF_NANOS) {
                    micStop(false);
                    break;
                }
                int n = rec.read(buf, 0, block);
                if (n <= 0) continue;
                byte[] out = new byte[n];
                System.arraycopy(buf, 0, out, 0, n);
                micSeq.incrementAndGet();
                producedBytes += n;
                // 广播：每个客户端独立投递，慢的丢自己的最旧块（单块被多客户端共享 = 偷流）
                for (MicClient c : micClientsList) {
                    c.deliveredBytes += n;
                    if (!c.q.offer(out)) {
                        c.q.poll();
                        c.drops++;
                        c.q.offer(out);
                    }
                }
                if (micSeq.get() % 125 == 0) { // 临时计量：每 5s 打一次产出/投递/积压
                    StringBuilder sb = new StringBuilder("stats produced=" + producedBytes);
                    for (MicClient c : micClientsList) {
                        sb.append(" | c").append(c.id).append(" delivered=").append(c.deliveredBytes)
                            .append(" backlog=").append(c.q.size()).append(" drops=").append(c.drops);
                    }
                    Log.i(TAG, sb.toString());
                }
                // 节拍完全交给阻塞读：sleep 会造成产出 < 实时，播放端周期性饿死（断续声）
            }
        }
    }

    public JSONObject micStatusJson() throws Exception {
        return new JSONObject()
            .put("available", hasMic)
            .put("on", isMicOn())
            .put("explicit", micExplicit)
            .put("clients", getMicClients())
            .put("rate", micRate)
            .put("format", "pcm16le mono")
            .put("seq", getMicSeq())
            .put("drops", getMicDrops());
    }
}
