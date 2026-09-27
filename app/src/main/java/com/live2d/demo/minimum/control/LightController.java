package com.live2d.demo.minimum.control;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * 舞台灯控制：/api/light。
 *
 * 音箱机身 18 颗 LED（SN3218 驱动）由系统守护 /system/bin/zhcctrl 经本地 socket 驱动。
 * 出厂 voice 的 LEDManager 是原本唯一的业务控制方（现已禁用），这里直接以同一协议接管：
 * 连上 socket 后依次写握手串、LEDInit，之后反复写 LEDA 分段亮度。
 *
 * 协议（实证）：socket 为文件系统路径 /dev/socket/zhcctrl（init 创建并经 ANDROID_SOCKET_ 把 fd
 * 交给 zhcctrl）。voice 用的是 LocalSocketAddress("zhcctrl", Namespace.RESERVED)——RESERVED 的
 * 语义是"保留 socket 目录"即 /dev/socket/<name>，不是抽象命名空间（实测两条路径等价）。
 * 命令为 ASCII 文本、无分隔符，守护按已知命令字逐个解析：
 *   LEDA,<灯号>,<亮度>,...   灯号 1-18，亮度 0-255；12=红 15=绿 18=蓝
 * 守护支持多客户端并发（每连接 spawn 一个 pthread），故与其它进程并存无冲突。
 */
public class LightController {
    private static final String TAG = "LightController";

    /** 守护 socket 路径（init 建于 /dev/socket，权限 0666）。 */
    private static final String SOCKET_PATH = "/dev/socket/zhcctrl";
    private static final String CMD_HANDSHAKE = "zhc_client_ctrl";
    private static final String CMD_INIT = "LEDInit";
    /**
     * 只关三色通道（12/15/18），不碰 15 颗暖白舞台灯——voice 的 closeLED 就是这个语义，
     * 用于闪烁的"灭"相位（闪灯不该动暖白环）。
     * 注意：真·全灭见 {@link #allOffCommand()}。
     */
    private static final String CMD_COLOR_OFF = "LEDA,12,0,15,0,18,0";

    /** 硬件版本：voice 的 VoiceApp 读此文件（==2 走另一套舞台灯亮度），缺失则用默认 3。 */
    private static final String HW_VERSION_SYSFS = "/sys/class/zhc_version/hardware_version";

    // 舞台灯亮度档位（对齐 voice LEDManager 的 CMD_STAGE_OPEN）
    private static final int STAGE_A_HW2 = 7;
    private static final int STAGE_B_HW2 = 40;
    private static final int STAGE_A_DEFAULT = 10;
    private static final int STAGE_B_DEFAULT = 45;

    /** 三色常亮亮度（voice 用 150）。 */
    private static final int SOLID_LEVEL = 150;
    private static final int MAX_LEVEL = 255;

    /** 灯号排布：1-9 为舞台第一段，10/11/13/14/16/17 为第二段（12/15/18 是三色通道，不参与）。 */
    private static final int[] STAGE_GROUP_2 = {10, 11, 13, 14, 16, 17};
    private static final int CH_RED = 12;
    private static final int CH_GREEN = 15;
    private static final int CH_BLUE = 18;
    private static final int LED_MIN = 1;
    private static final int LED_MAX = 18;

    /** 闪烁节奏：1s 交替亮/灭（与 voice 定时器一致）。 */
    private static final int BLINK_INTERVAL_MS = 1000;

    public static final String MODE_OFF = "off";
    public static final String MODE_STAGE = "stage";
    public static final String MODE_RED = "red";
    public static final String MODE_GREEN = "green";
    public static final String MODE_BLUE = "blue";
    public static final String MODE_COLOR = "color";
    public static final String MODE_CUSTOM = "custom";

    private static final LightController INSTANCE = new LightController();

    public static LightController get() {
        return INSTANCE;
    }

    private LightController() {
        hardwareVersion = readHardwareVersion();
        stageA = hardwareVersion == 2 ? STAGE_A_HW2 : STAGE_A_DEFAULT;
        stageB = hardwareVersion == 2 ? STAGE_B_HW2 : STAGE_B_DEFAULT;
        ticker = new HandlerThread("light-blink");
        ticker.start();
        tickerHandler = new Handler(ticker.getLooper());
    }

    private final HandlerThread ticker;
    private final Handler tickerHandler;

    /** 所有 socket 写操作都在此锁内串行（HTTP 线程与闪烁定时线程共用）。 */
    private final Object lock = new Object();
    private LocalSocket socket;
    private OutputStream out;

    private volatile boolean available = false;
    private volatile boolean connected = false;
    private volatile String mode = MODE_OFF;
    private volatile boolean blink = false;
    private volatile int brightness = SOLID_LEVEL;
    /** 三色通道的已知电平（守护逐通道更新，故本地累积跟踪；供 status 的 rgb 字段）。 */
    private volatile int appliedR = 0;
    private volatile int appliedG = 0;
    private volatile int appliedB = 0;
    /**
     * 15 颗暖白"舞台环"的已知开关状态；null = 不确定（进程刚起、或 custom 只动过部分灯）。
     * 只在能确定的模式下更新：stage 依档位、off 为 false、三色模式不动它。
     */
    private volatile Boolean ringOn = null;
    private volatile int stageA;
    private volatile int stageB;
    private volatile int hardwareVersion;
    private volatile long seq = 0;
    private volatile String lastError = null;

    /** 闪烁相位与定时链（受 lock 保护）。gen 用于让已在执行中的旧 tick 自行退出，避免重启闪烁时留下两条链。 */
    private String blinkCmd = null;
    private boolean blinkOn = false;
    private long blinkGen = 0;
    private Runnable blinkTick = null;

    public boolean isAvailable() {
        return available;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getMode() {
        return mode;
    }

    public boolean isBlinking() {
        return blink;
    }

    public int getHardwareVersion() {
        return hardwareVersion;
    }

    public String getLastError() {
        return lastError;
    }

    /** 支持的 mode 取值。 */
    public static boolean isKnownMode(String m) {
        return MODE_OFF.equals(m) || MODE_STAGE.equals(m) || MODE_RED.equals(m)
            || MODE_GREEN.equals(m) || MODE_BLUE.equals(m) || MODE_COLOR.equals(m)
            || MODE_CUSTOM.equals(m);
    }

    /** 可闪烁的模式（即所有"点亮某种颜色"的模式）。 */
    private static boolean isColorMode(String m) {
        return MODE_RED.equals(m) || MODE_GREEN.equals(m) || MODE_BLUE.equals(m) || MODE_COLOR.equals(m);
    }

    /**
     * 探测/建立连接（首次成功前每次调用都会重试）。
     * 连接是长持的：守护每接受一个连接就 spawn 一个 pthread，反复连会留下悬挂 fd，故只连一次。
     */
    public boolean ensureProbed() {
        synchronized (lock) {
            return ensureConnectedLocked();
        }
    }

    /**
     * 执行一次灯光设置。
     *
     * @return true = 已下发；false = socket 不可用（调用方回 503，细节看 {@link #getLastError()}）。
     */
    public boolean apply(Spec spec) {
        synchronized (lock) {
            stopBlinkLocked();

            String cmd = commandFor(spec);
            if (cmd == null) {
                lastError = "unsupported mode: " + spec.mode;
                return false;
            }
            boolean ok = sendLocked(cmd);
            if (!ok) return false;

            // 只有真的下发成功才提交状态，否则状态会与设备实际显示不符
            mode = spec.mode;
            brightness = spec.brightness;
            stageA = spec.stageA;
            stageB = spec.stageB;
            int[] c = channelLevels(spec);
            if (c != null) {
                appliedR = c[0];
                appliedG = c[1];
                appliedB = c[2];
            } else {
                // custom 只改请求里提到的通道，其余通道在守护侧保持原值，故本地也逐通道更新
                for (int i = 0; i < spec.ledIds.length; i++) {
                    recordChannel(spec.ledIds[i], spec.ledLevels[i]);
                }
            }
            // 暖白环状态：只在能确定的模式下更新（custom 是部分写，无法断言整环，置为未知）
            if (MODE_STAGE.equals(spec.mode)) {
                ringOn = Boolean.valueOf(spec.stageA > 0 || spec.stageB > 0);
            } else if (MODE_OFF.equals(spec.mode)) {
                ringOn = Boolean.FALSE;
            } else if (MODE_CUSTOM.equals(spec.mode)) {
                // 碰到暖白灯就无法断言整环状态（其它暖白灯在守护侧保持原值）；只动三色通道则环不变
                for (int i = 0; i < spec.ledIds.length; i++) {
                    if (isRingId(spec.ledIds[i])) {
                        ringOn = null;
                        break;
                    }
                }
            }
            blink = spec.blink;
            if (blink) {
                blinkCmd = cmd;
                blinkOn = true;
                startBlinkLocked();
                Log.i(TAG, "blink on: " + cmd);
            }
            return true;
        }
    }

    /** 停止闪烁（不影响当前常亮状态）。 */
    public void stopBlink() {
        synchronized (lock) {
            stopBlinkLocked();
        }
    }

    /** 调用者持 lock；blink/blinkCmd 已就位。 */
    private void startBlinkLocked() {
        final long gen = ++blinkGen;
        blinkTick = new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (blinkGen != gen || !blink || blinkCmd == null) return;
                    blinkOn = !blinkOn;
                    sendLocked(blinkOn ? blinkCmd : CMD_COLOR_OFF);
                    tickerHandler.postDelayed(this, BLINK_INTERVAL_MS);
                }
            }
        };
        tickerHandler.postDelayed(blinkTick, BLINK_INTERVAL_MS);
    }

    private void stopBlinkLocked() {
        blink = false;
        blinkCmd = null;
        blinkGen++;
        if (blinkTick != null) {
            tickerHandler.removeCallbacks(blinkTick);
            blinkTick = null;
        }
    }

    private String commandFor(Spec spec) {
        // off = 真·全灭（18 路全 0），包含 15 颗暖白舞台灯——voice 的 closeLED 只关三色通道，
        // 那种语义会让暖白环一直亮着，用户说"关灯"时期望的是整圈都灭
        if (MODE_OFF.equals(spec.mode)) return allOffCommand();
        if (MODE_STAGE.equals(spec.mode)) return stageCommand(spec.stageA, spec.stageB);
        if (MODE_CUSTOM.equals(spec.mode)) return customCommand(spec.ledIds, spec.ledLevels);
        int[] c = channelLevels(spec);
        if (c == null) return null;
        return colorCommand(c[0], c[1], c[2]);
    }

    /**
     * 本次下发的三色通道电平。返回 null = 该模式不整组设置三色（custom 只动指定灯）。
     * off/stage 都会把三色通道显式清零，故这里的值就是下发后的真实电平。
     */
    private static int[] channelLevels(Spec spec) {
        if (MODE_OFF.equals(spec.mode) || MODE_STAGE.equals(spec.mode)) return new int[]{0, 0, 0};
        if (MODE_RED.equals(spec.mode)) return new int[]{spec.brightness, 0, 0};
        if (MODE_GREEN.equals(spec.mode)) return new int[]{0, spec.brightness, 0};
        if (MODE_BLUE.equals(spec.mode)) return new int[]{0, 0, spec.brightness};
        if (MODE_COLOR.equals(spec.mode)) {
            return new int[]{
                scale(spec.rgbR, spec.brightness),
                scale(spec.rgbG, spec.brightness),
                scale(spec.rgbB, spec.brightness)};
        }
        return null; // custom
    }

    /** 按 brightness/255 缩放通道值（color 模式的总调光）。 */
    private static int scale(int value, int brightness) {
        return (value * brightness + 127) / 255;
    }

    private void recordChannel(int id, int level) {
        if (id == CH_RED) appliedR = level;
        else if (id == CH_GREEN) appliedG = level;
        else if (id == CH_BLUE) appliedB = level;
    }

    private static String colorCommand(int red, int green, int blue) {
        return "LEDA," + CH_RED + "," + red + "," + CH_GREEN + "," + green + "," + CH_BLUE + "," + blue;
    }

    private static String stageCommand(int a, int b) {
        StringBuilder sb = new StringBuilder("LEDA,");
        for (int id = 1; id <= 9; id++) sb.append(id).append(',').append(a).append(',');
        for (int id : STAGE_GROUP_2) sb.append(id).append(',').append(b).append(',');
        sb.append(CH_RED).append(",0,").append(CH_GREEN).append(",0,").append(CH_BLUE).append(",0");
        return sb.toString();
    }

    private static String customCommand(int[] ids, int[] levels) {
        StringBuilder sb = new StringBuilder("LEDA");
        for (int i = 0; i < ids.length; i++) {
            sb.append(',').append(ids[i]).append(',').append(levels[i]);
        }
        return sb.toString();
    }

    /** 18 路全灭（1..18 全部 0）。刻意不缓存成 static final：那会在 LED_MIN/LED_MAX 静态初始化之前执行。 */
    private static String allOffCommand() {
        StringBuilder sb = new StringBuilder("LEDA");
        for (int id = LED_MIN; id <= LED_MAX; id++) sb.append(',').append(id).append(",0");
        return sb.toString();
    }

    /** 灯号是否属于 15 颗暖白"舞台环"（1-9 与 10/11/13/14/16/17）。 */
    private static boolean isRingId(int id) {
        if (id >= 1 && id <= 9) return true;
        for (int r : STAGE_GROUP_2) {
            if (r == id) return true;
        }
        return false;
    }

    /** 调用者持 lock。连接断了会重连一次。 */
    private boolean sendLocked(String cmd) {
        if (ensureConnectedLocked() && writeLocked(cmd)) return true;
        // 连接可能已被守护关掉：丢弃后重连再试一次
        closeLocked();
        return ensureConnectedLocked() && writeLocked(cmd);
    }

    private boolean writeLocked(String cmd) {
        try {
            out.write(cmd.getBytes("US-ASCII"));
            out.flush();
            seq++;
            lastError = null;
            return true;
        } catch (Exception e) {
            lastError = "write failed: " + e;
            Log.w(TAG, "write failed: " + cmd, e);
            connected = false;
            return false;
        }
    }

    /** 调用者持 lock。 */
    private boolean ensureConnectedLocked() {
        if (socket != null) return true;
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            OutputStream os = s.getOutputStream();
            os.write(CMD_HANDSHAKE.getBytes("US-ASCII"));
            os.flush();
            os.write(CMD_INIT.getBytes("US-ASCII"));
            os.flush();
            socket = s;
            out = os;
            connected = true;
            available = true;
            lastError = null;
            Log.i(TAG, "connected " + SOCKET_PATH + " hw=" + hardwareVersion);
            return true;
        } catch (Exception e) {
            closeQuietly(s);
            connected = false;
            available = false;
            lastError = "zhcctrl unavailable: " + e;
            Log.w(TAG, "connect " + SOCKET_PATH + " failed", e);
            return false;
        }
    }

    private void closeLocked() {
        stopBlinkLocked();
        closeQuietly(socket);
        socket = null;
        out = null;
        connected = false;
    }

    private static void closeQuietly(LocalSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    private static int readHardwareVersion() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(HW_VERSION_SYSFS)));
            String line = r.readLine();
            if (line != null && line.trim().length() > 0) return Integer.parseInt(line.trim());
        } catch (Exception e) {
            Log.i(TAG, "no hardware version, assume default (not hw v2): " + e);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
        return 3; // 与 voice VoiceApp.hardwareVersion 默认值一致（!=2 → 10/45 档）
    }

    public JSONObject statusJson() {
        JSONObject channels = new JSONObject();
        JSONObject o = new JSONObject();
        try {
            channels.put("red", CH_RED).put("green", CH_GREEN).put("blue", CH_BLUE);
            o.put("available", available);
            o.put("connected", connected);
            o.put("mode", mode);
            o.put("blink", blink);
            o.put("brightness", brightness);
            o.put("rgb", new JSONArray().put(appliedR).put(appliedG).put(appliedB));
            if (ringOn != null) o.put("ring_on", ringOn.booleanValue());
            o.put("stage_levels", new JSONObject().put("a", stageA).put("b", stageB));
            o.put("hardware_version", hardwareVersion);
            o.put("led_count", LED_MAX);
            o.put("color_channels", channels);
            o.put("seq", seq);
            if (lastError != null) o.put("last_error", lastError);
        } catch (Exception e) {
            Log.e(TAG, "status json failed", e);
        }
        return o;
    }

    /** 一次灯光设置的完整参数（由 {@link #parseSpec} 校验后交给 {@link #apply}）。 */
    public static class Spec {
        public String mode = MODE_OFF;
        public boolean blink = false;
        /** red/green/blue 模式下即该通道亮度；color 模式下为总调光系数（缺省 255 = 不缩放）。 */
        public int brightness = SOLID_LEVEL;
        public int stageA = STAGE_A_DEFAULT;
        public int stageB = STAGE_B_DEFAULT;
        /** color 模式的期望颜色（0..255/通道）。 */
        public int rgbR = 0;
        public int rgbG = 0;
        public int rgbB = 0;
        public int[] ledIds = new int[0];
        public int[] ledLevels = new int[0];
    }

    /**
     * 校验 POST /api/light 的请求体。
     *
     * @param base 当前状态，用于补全省略的可选字段（stage_a/b 缺省沿用当前硬件档位）
     * @throws IllegalArgumentException 参数不合法（调用方回 400，异常文案即 error）
     */
    public static Spec parseSpec(JSONObject in, Spec base) {
        Spec s = base == null ? new Spec() : base;
        Spec spec = new Spec();
        spec.stageA = s.stageA;
        spec.stageB = s.stageB;

        String m = in.optString("mode", "");
        if (m.length() == 0) throw new IllegalArgumentException("missing mode");
        if (!isKnownMode(m)) {
            throw new IllegalArgumentException("unknown mode: " + m
                + " (available: off, stage, red, green, blue, color, custom)");
        }
        spec.mode = m;
        spec.blink = in.optBoolean("blink", false);
        if (spec.blink && !isColorMode(m)) {
            throw new IllegalArgumentException("blink only supported for red/green/blue/color");
        }

        int brightness = -1;
        if (in.has("brightness")) {
            brightness = in.optInt("brightness", SOLID_LEVEL);
            if (brightness < 0 || brightness > MAX_LEVEL) {
                throw new IllegalArgumentException("brightness out of range 0..255");
            }
        }
        // color 模式把 brightness 当总调光（缺省 255 = 按 rgb 原值下发）
        spec.brightness = brightness >= 0 ? brightness
            : (MODE_COLOR.equals(m) ? MAX_LEVEL : SOLID_LEVEL);

        if (MODE_COLOR.equals(m)) {
            int[] c = parseColor(in);
            if (c == null) {
                throw new IllegalArgumentException("color mode needs rgb:[r,g,b] (0..255) or color:\"#rrggbb\"");
            }
            spec.rgbR = c[0];
            spec.rgbG = c[1];
            spec.rgbB = c[2];
        }

        if (in.has("stage_a")) {
            int a = in.optInt("stage_a", spec.stageA);
            if (a < 0 || a > MAX_LEVEL) throw new IllegalArgumentException("stage_a out of range 0..255");
            spec.stageA = a;
        }
        if (in.has("stage_b")) {
            int b = in.optInt("stage_b", spec.stageB);
            if (b < 0 || b > MAX_LEVEL) throw new IllegalArgumentException("stage_b out of range 0..255");
            spec.stageB = b;
        }

        JSONArray leds = in.optJSONArray("leds");
        if (MODE_CUSTOM.equals(m)) {
            if (leds == null || leds.length() == 0) {
                throw new IllegalArgumentException("custom mode needs leds: [{\"id\":1,\"level\":10}, ...]");
            }
        }
        if (leds != null) {
            int n = leds.length();
            spec.ledIds = new int[n];
            spec.ledLevels = new int[n];
            for (int i = 0; i < n; i++) {
                JSONObject item = leds.optJSONObject(i);
                if (item == null) throw new IllegalArgumentException("leds[" + i + "] must be an object");
                int id = item.optInt("id", -1);
                if (id < LED_MIN || id > LED_MAX) {
                    throw new IllegalArgumentException("leds[" + i + "].id out of range 1..18");
                }
                int level = item.optInt("level", -1);
                if (level < 0 || level > MAX_LEVEL) {
                    throw new IllegalArgumentException("leds[" + i + "].level out of range 0..255");
                }
                spec.ledIds[i] = id;
                spec.ledLevels[i] = level;
            }
        }
        return spec;
    }

    /** rgb:[r,g,b] 与 color:"#rrggbb"（或 "#rgb"）两种写法。返回 null = 无法解析。 */
    private static int[] parseColor(JSONObject in) {
        if (in.has("rgb")) {
            JSONArray a = in.optJSONArray("rgb");
            if (a == null || a.length() != 3) return null;
            int[] c = new int[3];
            for (int i = 0; i < 3; i++) {
                int v = a.optInt(i, -1);
                if (v < 0 || v > MAX_LEVEL) return null;
                c[i] = v;
            }
            return c;
        }
        if (in.has("color")) {
            String h = in.optString("color", "").trim();
            if (h.startsWith("#")) h = h.substring(1);
            if (h.length() == 3) {
                StringBuilder sb = new StringBuilder(6);
                for (int i = 0; i < 3; i++) sb.append(h.charAt(i)).append(h.charAt(i));
                h = sb.toString();
            }
            if (h.length() != 6) return null;
            try {
                return new int[]{
                    Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16)};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 当前状态快照（parseSpec 的 base）。 */
    public Spec currentSpec() {
        Spec s = new Spec();
        s.mode = mode;
        s.blink = blink;
        s.brightness = brightness;
        s.stageA = stageA;
        s.stageB = stageB;
        return s;
    }
}
