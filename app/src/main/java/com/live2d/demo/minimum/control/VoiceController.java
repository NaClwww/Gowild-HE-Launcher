package com.live2d.demo.minimum.control;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语音采集：/api/voice/{name}/*（现阶段 name 只有 "mic"）。
 *
 * AudioRecord（VOICE_RECOGNITION + 可选系统 AEC，PCM16LE 单声道）采集线程 → 有界队列
 * → GET /stream 以 chunked 响应持续吐给后端 ASR。
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

    private boolean aecRequested = true;
    private AcousticEchoCanceler micAec;
    private String aecError;
    private int micSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

    private boolean aecAvailable() {
        try {
            return AcousticEchoCanceler.isAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void releaseAec() {
        if (micAec != null) {
            try { micAec.release(); } catch (RuntimeException ignored) { }
            micAec = null;
        }
    }

    private boolean aecEnabled() {
        try {
            return micAec != null && micAec.getEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

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
        return micStart(explicit, rate, aecRequested);
    }

    /** AEC 开关变更会重建录音会话；客户端保留，但清除旧会话音频。 */
    public synchronized boolean micStart(boolean explicit, int rate, boolean aec) {
        int nextRate = Math.max(8000, Math.min(48000, rate));
        if (explicit) micExplicit = true;
        micLastUseNanos = System.nanoTime();
        if (micRecord != null && micRate == nextRate && aecRequested == aec) return true;
        micStop(false);
        micRate = nextRate;
        aecRequested = aec;
        aecError = null;
        for (MicClient c : micClientsList) c.q.clear();
        int minBuf = AudioRecord.getMinBufferSize(micRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return false;

        boolean useAec = aecRequested && aecAvailable();
        if (aecRequested && !useAec) aecError = "system AEC unavailable";
        if (useAec && startRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, minBuf, true)) {
            return true;
        }
        // 不让不支持的厂商效果阻断普通 ASR。
        return startRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, minBuf, false);
    }

    public synchronized boolean isAecRequested() {
        return aecRequested;
    }

    public synchronized void micStopWithAec(boolean requested) {
        micStop(true);
        aecRequested = requested;
        aecError = null;
    }

    private boolean startRecorder(int source, int minBuf, boolean withAec) {
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(source, micRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord initialization failed");
            }
            if (withAec) {
                micAec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                if (micAec == null || micAec.setEnabled(true) != AudioEffect.SUCCESS || !aecEnabled()) {
                    throw new IllegalStateException("system AEC enable failed");
                }
            }
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord start failed");
            }
            micSource = source;
            micRecord = rec;
            micThread = new Thread(new MicRunnable(rec), "voice-mic");
            micThread.start();
            Log.i(TAG, "mic on rate=" + micRate + " source=" + source + " aec=" + aecEnabled());
            return true;
        } catch (RuntimeException e) {
            if (withAec) aecError = e.toString();
            releaseAec();
            if (rec != null) rec.release();
            Log.w(TAG, "mic start failed source=" + source, e);
            return false;
        }
    }

    public synchronized void micStop(boolean explicit) {
        if (explicit) micExplicit = false;
        AudioRecord rec = micRecord;
        if (rec == null) return;
        micRecord = null;
        releaseAec();
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
            int block = rec.getSampleRate() / 25 * 2; // 40ms 块（样本数×2字节）
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
                    synchronized (VoiceController.this) {
                        if (micRecord == rec && !micExplicit && micClientsList.isEmpty()
                            && System.nanoTime() - micLastUseNanos > MIC_AUTO_OFF_NANOS) {
                            micStop(false);
                            break;
                        }
                    }
                }
                int n = rec.read(buf, 0, block);
                if (micRecord != rec) break;
                if (n <= 0) continue;
                synchronized (VoiceController.this) {
                    if (micRecord != rec) break; // 重建会话后不再投递旧音频
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
                }
                // 节拍完全交给阻塞读：sleep 会造成产出 < 实时，播放端周期性饿死（断续声）
            }
        }
    }

    public synchronized JSONObject micStatusJson() throws Exception {
        return new JSONObject()
            .put("available", hasMic)
            .put("on", isMicOn())
            .put("explicit", micExplicit)
            .put("clients", getMicClients())
            .put("rate", micRate)
            .put("format", "pcm16le mono")
            .put("audio_source", isMicOn() ? (micSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                ? "VOICE_COMMUNICATION" : "VOICE_RECOGNITION") : JSONObject.NULL)
            .put("aec_requested", aecRequested)
            .put("aec_available", aecAvailable())
            .put("aec_enabled", aecEnabled())
            .put("aec_error", aecError == null ? JSONObject.NULL : aecError)
            .put("seq", getMicSeq())
            .put("drops", getMicDrops());
    }
}
