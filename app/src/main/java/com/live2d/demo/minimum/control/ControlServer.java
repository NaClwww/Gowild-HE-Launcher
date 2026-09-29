package com.live2d.demo.minimum.control;

import android.util.Log;

import com.live2d.demo.minimum.LAppMinimumDelegate;
import com.live2d.demo.minimum.LAppMinimumLive2DManager;
import com.live2d.demo.minimum.LAppMinimumModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * HTTP 控制面（:8900）。
 * v1 路由：GET/POST /api/models、GET/DELETE /api/models/{name}、POST /api/models/{name}/select、GET /api/status。
 */
public class ControlServer extends NanoHTTPD {
    private static final String TAG = "ControlServer";

    public ControlServer(int port) {
        super(port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            return route(session);
        } catch (ModelRepository.ModelException e) {
            return json(e.httpStatus, err(e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "serve failed", e);
            return json(500, err("internal error: " + e));
        }
    }

    private Response route(IHTTPSession s) throws Exception {
        String uri = s.getUri();
        if (uri.length() > 1 && uri.endsWith("/")) uri = uri.substring(0, uri.length() - 1);
        Method method = s.getMethod();

        if (uri.equals("/api/status")) {
            return status();
        }

        // Configuration remains available with a cold renderer or a 3D model selected.
        if (uri.equals("/api/control/tracking")) {
            if (method == Method.GET) return ok(FaceTracker.get().status());
            if (method == Method.POST) {
                try {
                    FaceTracker.get().configure(new JSONObject(readBody(s)));
                } catch (org.json.JSONException | IllegalArgumentException e) {
                    return json(400, err(e.getMessage()));
                }
                return ok(FaceTracker.get().status());
            }
            return methodNotAllowed("GET, POST");
        }

        if (uri.startsWith("/api/control/")) {
            return control(uri, s, method);
        }

        if (uri.startsWith("/api/voice/")) {
            return voice(uri, s, method);
        }

        if (uri.startsWith("/api/camera")) {
            return camera(uri, s, method);
        }

        if (uri.startsWith("/api/light")) {
            return light(uri, s, method);
        }

        if (uri.equals("/api/brightness")) {
            return brightness(s, method);
        }

        ModelRepository repo = ModelRepository.get(LAppMinimumDelegate.getInstance().getActivity());

        if (uri.equals("/api/models")) {
            if (method == Method.GET) {
                JSONArray arr = new JSONArray();
                String current = LAppMinimumLive2DManager.peekCurrentModel();
                String selected = repo.getSelected();
                for (ModelRepository.Descriptor d : repo.scan()) {
                    arr.put(toJson(d, d.name.equals(current), d.name.equals(selected)));
                }
                return ok(new JSONObject().put("models", arr));
            }
            if (method == Method.POST) {
                return upload(s, repo);
            }
            return methodNotAllowed("GET, POST");
        }

        if (uri.startsWith("/api/models/")) {
            String rest = uri.substring("/api/models/".length());
            if (rest.endsWith("/select") && method == Method.POST) {
                String name = rest.substring(0, rest.length() - "/select".length());
                return select(name, repo);
            }
            if (rest.endsWith("/animations") && method == Method.POST) {
                String name = rest.substring(0, rest.length() - "/animations".length());
                return addAnimation(name, s, repo);
            }
            String name = rest;
            if (method == Method.GET) {
                ModelRepository.Descriptor d = repo.find(name);
                if (d == null) return json(404, err("model not found: " + name));
                String current = LAppMinimumLive2DManager.peekCurrentModel();
                return ok(toJson(d, d.name.equals(current), d.name.equals(repo.getSelected())));
            }
            if (method == Method.DELETE) {
                String current = LAppMinimumLive2DManager.peekCurrentModel();
                if (name.equals(current)) {
                    return json(409, err("model is loaded, select another first"));
                }
                ModelRepository.Descriptor d = repo.find(name);
                repo.delete(name, d != null && d.source == ModelRepository.Source.BUILTIN);
                return ok(new JSONObject().put("deleted", name));
            }
            return methodNotAllowed("GET, DELETE");
        }

        return json(404, err("no route: " + uri));
    }

    /**
     * /api/voice/{name}/*：语音采集与播放。采集 name 只有 mic；播放走 play/*。
     * 不依赖 GL。
     * GET /api/voice/{name} → 状态；POST → {"on":bool,"rate":8000..48000}；
     * POST /{name}/start、/{name}/stop；GET /{name}/stream → chunked PCM16LE mono（隐式开启）。
     * 播放（对齐记录 #3，口型暂缓）：POST /play/begin {"rate","channels"} →
     * POST /play/chunk（raw PCM16LE body，多次）→ POST /play/end；POST /play/stop 立即掐断。
     */
    private Response voice(String uri, IHTTPSession s, Method method) throws Exception {
        String rest = uri.substring("/api/voice/".length());
        String name = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        String sub = rest.contains("/") ? rest.substring(rest.indexOf('/') + 1) : "";

        if (name.equals("play")) {
            return play(sub, s, method);
        }

        VoiceController voice = VoiceController.get();

        if (!VoiceController.isKnownSource(name)) {
            return json(404, err("unknown capture source: " + name + " (available: mic)"));
        }

        if (sub.equals("") || sub.equals("status")) {
            if (method == Method.GET) return ok(voice.micStatusJson());
            if (method == Method.POST) return micControl(s, voice);
            return methodNotAllowed("GET, POST");
        }

        if (sub.equals("start")) {
            if (method != Method.POST) return methodNotAllowed("POST");
            JSONObject in;
            try {
                in = new JSONObject(readBody(s));
            } catch (Exception e) {
                in = new JSONObject(); // 允许空 body
            }
            if (!voice.micStart(true, in.optInt("rate", voice.getMicRate()),
                in.optBoolean("aec", voice.isAecRequested()))) {
                return json(503, err("microphone start failed"));
            }
            return ok(voice.micStatusJson());
        }

        if (sub.equals("stop")) {
            if (method != Method.POST) return methodNotAllowed("POST");
            voice.micStop(true);
            return ok(voice.micStatusJson());
        }

        if (sub.equals("stream")) {
            if (method != Method.GET) return methodNotAllowed("GET");
            if (!voice.hasMic()) return json(503, err("no microphone on device"));
            if (!voice.micStart(false, voice.getMicRate())) {
                return json(503, err("microphone start failed"));
            }
            Response r = newChunkedResponse(Response.Status.OK,
                "audio/x pcm", new MicStream(voice)); // RFC 不存在该 mime，惯例写法（MicStream 构造时挂载客户端）
            r.addHeader("Cache-Control", "no-store");
            return r;
        }

        return json(404, err("no voice route: " + sub + " (available: status, start, stop, stream)"));
    }

    /**
     * /api/voice/play/*：TTS 下行播放（NanoHTTPD 不收 chunked 请求体，流式拆成
     * 三段式分块上行）。begin 开会话（掐旧）、chunk 喂 PCM16LE、end 收口排空、
     * stop 立即掐断。chunk 的阻塞入队即播放背压，反压调用方整条 TTS 链。
     */
    private Response play(String sub, IHTTPSession s, Method method) throws Exception {
        VoicePlayer player = VoicePlayer.get();

        if (sub.equals("") || sub.equals("status")) {
            if (method == Method.GET) return ok(player.statusJson());
            return methodNotAllowed("GET");
        }

        if (sub.equals("begin")) {
            if (method != Method.POST) return methodNotAllowed("POST");
            JSONObject in;
            try {
                in = new JSONObject(readBody(s));
            } catch (Exception e) {
                in = new JSONObject(); // 允许空 body（用默认 16k 单声道）
            }
            if (!player.begin(in.optInt("rate", 16000), in.optInt("channels", 1))) {
                return json(503, err(player.statusJson().opt("last_error") != null
                    ? player.statusJson().optString("last_error") : "playback start failed"));
            }
            return ok(player.statusJson());
        }

        if (sub.equals("chunk")) {
            if (method != Method.POST) return methodNotAllowed("POST");
            byte[] pcm = readRawBody(s, 2 << 20);
            if (pcm == null) return json(400, err("Content-Length required (raw PCM body)"));
            try {
                if (!player.write(pcm)) {
                    return json(409, err("no open playback session (begin first)"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return json(503, err("interrupted"));
            }
            return ok(player.statusJson());
        }

        if (sub.equals("end")) {
            if (method != Method.POST) return methodNotAllowed("POST");
            player.end();
            return ok(player.statusJson());
        }

        if (sub.equals("stop")) {
            if (method != Method.POST) return methodNotAllowed("POST");
            player.stop();
            return ok(player.statusJson());
        }

        return json(404, err("no play route: " + sub + " (available: status, begin, chunk, end, stop)"));
    }

    /** 读取定长原始二进制 body（Content-Length 必须；超上限或缺失返回 null）。 */
    private static byte[] readRawBody(IHTTPSession s, long maxBytes) throws IOException {
        long len;
        try {
            len = Long.parseLong(s.getHeaders().get("content-length"));
        } catch (Exception e) {
            return null;
        }
        if (len <= 0 || len > maxBytes) return null;

        byte[] buf = new byte[(int) len];
        InputStream in = s.getInputStream();
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, (int) (len - off));
            if (n <= 0) break;
            off += n;
        }
        return off == len ? buf : java.util.Arrays.copyOf(buf, off);
    }

    private Response micControl(IHTTPSession s, VoiceController voice) throws Exception {
        JSONObject in;
        try {
            in = new JSONObject(readBody(s));
        } catch (Exception e) {
            return json(400, err("invalid JSON body"));
        }
        boolean on = in.optBoolean("on", in.optBoolean("enabled", voice.isMicOn()));
        if (on) {
            if (!voice.micStart(true, in.optInt("rate", voice.getMicRate()),
                in.optBoolean("aec", voice.isAecRequested()))) {
                return json(503, err("microphone start failed"));
            }
        } else {
            if (in.has("aec")) voice.micStopWithAec(in.optBoolean("aec"));
            else voice.micStop(true);
        }
        return ok(voice.micStatusJson());
    }

    /**
     * /api/camera*：摄像头开关与 MJPEG 视频流（不依赖 GL，与 Live2D 并行服务）。
     * GET /api/camera → 状态；POST /api/camera {"on":bool,"quality":30..95} → 开关；
     * GET /api/camera/stream → multipart/x-mixed-replace MJPEG（隐式开机）；
     * GET /api/camera/frame → 单帧 JPEG（隐式开机）。
     */
    private Response camera(String uri, IHTTPSession s, Method method) throws Exception {
        CameraController cam = CameraController.get();

        if (uri.equals("/api/camera")) {
            if (method == Method.GET) return cameraStatus(cam);
            if (method == Method.POST) {
                JSONObject in;
                try {
                    in = new JSONObject(readBody(s));
                } catch (Exception e) {
                    return json(400, err("invalid JSON body"));
                }
                if (in.has("quality")) cam.setJpegQuality(in.optInt("quality", cam.getJpegQuality()));
                boolean on = in.optBoolean("on", in.optBoolean("enabled", cam.isOn()));
                if (on) {
                    if (!cam.turnOn(true)) return json(503, err(cam.getLastError() != null ? cam.getLastError() : "camera open failed"));
                } else {
                    FaceTracker.get().disable(); // Explicit camera-off must not be undone by tracking.
                    cam.turnOff(true);
                }
                return cameraStatus(cam);
            }
            return methodNotAllowed("GET, POST");
        }

        if (uri.equals("/api/camera/stream")) {
            if (method != Method.GET) return methodNotAllowed("GET");
            if (!cam.ensureProbed()) return json(503, err("no camera on device"));
            if (!cam.turnOn(false)) return json(503, err(cam.getLastError() != null ? cam.getLastError() : "camera open failed"));
            if (!waitForFirstFrame(cam, 2500)) {
                return json(503, err("camera warming up timeout" + (cam.getLastError() != null ? ": " + cam.getLastError() : "")));
            }
            cam.registerClient();
            Response r = newChunkedResponse(Response.Status.OK,
                "multipart/x-mixed-replace; boundary=frame", new MjpegStream(cam));
            r.addHeader("Cache-Control", "no-store");
            return r;
        }

        if (uri.equals("/api/camera/frame")) {
            if (method != Method.GET) return methodNotAllowed("GET");
            if (!cam.ensureProbed()) return json(503, err("no camera on device"));
            if (!cam.turnOn(false)) return json(503, err(cam.getLastError() != null ? cam.getLastError() : "camera open failed"));
            if (!waitForFirstFrame(cam, 2000)) {
                return json(503, err("camera warming up"));
            }
            byte[] jpeg = cam.latestJpeg();
            Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                new java.io.ByteArrayInputStream(jpeg), jpeg.length);
            r.addHeader("Cache-Control", "no-store");
            return r;
        }

        return json(404, err("no camera route: " + uri));
    }

    private static boolean waitForFirstFrame(CameraController cam, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (cam.latestJpeg() == null && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return cam.latestJpeg() != null;
    }

    private static Response cameraStatus(CameraController cam) throws Exception {
        JSONObject o = new JSONObject()
            .put("available", cam.ensureProbed())
            .put("on", cam.isOn())
            .put("explicit", cam.isExplicitOn())
            .put("clients", cam.getClientCount())
            .put("tracking", cam.isTrackingRequested())
            .put("width", cam.getWidth())
            .put("height", cam.getHeight())
            .put("fps", cam.getTargetFps())
            .put("quality", cam.getJpegQuality())
            .put("frame_seq", cam.getFrameSeq());
        if (cam.getLastError() != null) o.put("last_error", cam.getLastError());
        return ok(o);
    }

    /**
     * /api/light：机身 18 颗舞台灯（系统守护 zhcctrl 驱动，与 GL 渲染无关）。
     * GET → 状态；POST → {"mode":"off|stage|red|green|blue|color|custom", "blink":bool,
     *                      "brightness":0..255, "stage_a","stage_b":0..255,
     *                      "rgb":[r,g,b] 或 "color":"#rrggbb"（color 模式，亮度当总调光）,
     *                      "leds":[{"id":1..18,"level":0..255}, ...]（custom）}
     */
    private Response light(String uri, IHTTPSession s, Method method) throws Exception {
        if (!uri.equals("/api/light")) {
            return json(404, err("no light route: " + uri + " (available: /api/light)"));
        }
        LightController light = LightController.get();

        if (method == Method.GET) {
            light.ensureProbed();
            return ok(light.statusJson());
        }
        if (method == Method.POST) {
            JSONObject in;
            try {
                in = new JSONObject(readBody(s));
            } catch (Exception e) {
                return json(400, err("invalid JSON body"));
            }
            LightController.Spec spec;
            try {
                spec = LightController.parseSpec(in, light.currentSpec());
            } catch (IllegalArgumentException e) {
                return json(400, err(e.getMessage()));
            }
            if (!light.apply(spec)) {
                return json(503, err(light.getLastError() != null ? light.getLastError() : "light unavailable"));
            }
            return ok(light.statusJson());
        }
        return methodNotAllowed("GET, POST");
    }

    /**
     * /api/brightness：屏幕亮度（投影）。GET → 状态；POST → {"value":1..255}（超限夹紧）。
     * 双层写入：窗口覆盖立即生效 + Settings.System 持久；app 重启由本地持久重放。
     */
    private Response brightness(IHTTPSession s, Method method) throws Exception {
        BrightnessController brightness = BrightnessController.get();
        if (method == Method.GET) {
            return ok(brightness.statusJson());
        }
        if (method == Method.POST) {
            JSONObject in;
            try {
                in = new JSONObject(readBody(s));
            } catch (Exception e) {
                return json(400, err("invalid JSON body"));
            }
            if (!in.has("value")) {
                return json(400, err("missing value (" + BrightnessController.MIN + ".."
                    + BrightnessController.MAX + ")"));
            }
            try {
                in.get("value");
            } catch (Exception e) {
                return json(400, err("value must be a number"));
            }
            if (!brightness.setValue((int) in.getDouble("value"))) {
                return json(503, err("no activity window available"));
            }
            return ok(brightness.statusJson());
        }
        return methodNotAllowed("GET, POST");
    }

    /** GET/POST /api/control/animation：l3d 动作列表与播放。POST {"name","loop":true,"speed":1} 或 {"stop":true}。 */
    private Response animationCtl(IHTTPSession s, Method method, LAppMinimumLive2DManager manager) throws Exception {
        boolean is3d = "l3d".equals(LAppMinimumLive2DManager.peekCurrentType());
        if (method == Method.GET) {
            JSONObject o = new JSONObject();
            o.put("available", is3d);
            String playing = LAppMinimumLive2DManager.peekPlayingAnim();
            o.put("current", playing == null ? JSONObject.NULL : playing);
            o.put("loop", LAppMinimumLive2DManager.peekPlayingLoop());
            o.put("speed", (double) LAppMinimumLive2DManager.peekPlayingSpeed());
            if (is3d) {
                ModelRepository repo = ModelRepository.get(
                    LAppMinimumDelegate.getInstance().getActivity());
                String selected = repo.getSelected();
                ModelRepository.Descriptor d = selected != null ? repo.find(selected) : null;
                JSONArray arr = new JSONArray();
                if (d != null && "l3d".equals(d.type)) {
                    for (int i = 0; i < d.animationNames.size(); i++) {
                        arr.put(new JSONObject()
                            .put("name", d.animationNames.get(i))
                            .put("duration_s", d.animationDurations.get(i)));
                    }
                }
                o.put("animations", arr);
            }
            return ok(o);
        }
        if (method == Method.POST) {
            if (!is3d) {
                return json(409, err("current model is not l3d"));
            }
            JSONObject in;
            try {
                in = new JSONObject(readBody(s));
            } catch (Exception e) {
                return json(400, err("invalid JSON body"));
            }
            if (in.optBoolean("stop", false)) {
                LAppMinimumDelegate.getInstance().post(new Runnable() {
                    @Override
                    public void run() {
                        LAppMinimumLive2DManager.getInstance().stopAnimation();
                    }
                });
                return json(202, new JSONObject().put("ok", true).put("stopped", true));
            }
            final String name = in.optString("name", "");
            final float speed = (float) in.optDouble("speed", 1.0);
            final boolean loop = in.optBoolean("loop", true);
            LAppMinimumDelegate.getInstance().post(new Runnable() {
                @Override
                public void run() {
                    LAppMinimumLive2DManager.getInstance().playAnimation(name, loop, speed);
                }
            });
            return json(202, new JSONObject().put("ok", true).put("playing", name)
                .put("loop", loop).put("speed", (double) speed));
        }
        return methodNotAllowed("GET, POST");
    }

    private Response select(String name, ModelRepository repo) throws Exception {
        if (!ModelRepository.isValidName(name)) return json(400, err("invalid name: " + name));
        ModelRepository.Descriptor d = repo.find(name);
        if (d == null) return json(404, err("model not found: " + name));
        if (!LAppMinimumDelegate.isGlReady()) {
            return json(503, err("renderer not ready, retry later"));
        }
        repo.setSelected(name);
        final String model = name;
        LAppMinimumDelegate.getInstance().post(new Runnable() {
            @Override
            public void run() {
                LAppMinimumLive2DManager.getInstance().loadModel(model);
            }
        });
        return json(202, new JSONObject().put("ok", true).put("loading", name));
    }

    /**
     * POST /api/models/{name}/animations?name=<动作名>：向 l3d 包追加/替换单个动作
     * （raw glb body）。服务端校验骨架节点名匹配；若该模型正上屏则自动热重载。
     */
    private Response addAnimation(final String modelName, IHTTPSession s, ModelRepository repo) throws Exception {
        List<String> names = s.getParameters().get("name");
        String animName = names != null && !names.isEmpty() ? names.get(0) : null;
        if (animName == null || animName.length() == 0) {
            return json(400, err("missing ?name= for animation"));
        }
        long len;
        try {
            len = Long.parseLong(s.getHeaders().get("content-length"));
        } catch (Exception e) {
            return json(400, err("Content-Length required (raw glb body)"));
        }
        ModelRepository.AnimUploadResult r = repo.addAnimation(modelName, animName,
            s.getInputStream(), len);
        JSONObject o = new JSONObject()
            .put("ok", true)
            .put(r.replaced ? "replaced" : "added", animName)
            .put("duration_s", (double) r.durationS)
            .put("animations", toJson(r.descriptor, false, false).getJSONArray("animations"));
        boolean reloading = modelName.equals(LAppMinimumLive2DManager.peekCurrentModel());
        if (reloading) {
            repo.setSelected(modelName);
            LAppMinimumDelegate.getInstance().post(new Runnable() {
                @Override
                public void run() {
                    LAppMinimumLive2DManager.getInstance().loadModel(modelName);
                }
            });
            o.put("reloading", true);
        }
        Response resp = json(r.replaced ? 200 : 201, o);
        resp.addHeader("Connection", "close");
        return resp;
    }

    private Response upload(IHTTPSession s, ModelRepository repo) throws Exception {
        Map<String, String> headers = s.getHeaders();
        long len;
        try {
            len = Long.parseLong(headers.get("content-length"));
        } catch (Exception e) {
            return json(400, err("Content-Length required (raw zip body)"));
        }
        List<String> names = s.getParameters().get("name");
        String nameParam = names != null && !names.isEmpty() ? names.get(0) : null;

        InputStream in = s.getInputStream();
        ModelRepository.Descriptor d = repo.upload(in, len, nameParam);
        Response r = json(201, toJson(d, false, false));
        r.addHeader("Connection", "close");
        return r;
    }

    /**
     * /api/control/*：当前模型的运行时控制。pose / motion(身体层) / expression(面部叠加层)。
     */
    private Response control(String uri, IHTTPSession s, Method method) throws Exception {
        if (!LAppMinimumDelegate.isGlReady()) {
            return json(503, err("renderer not ready, retry later"));
        }
        LAppMinimumLive2DManager manager = LAppMinimumLive2DManager.getInstance();
        String sub = uri.substring("/api/control/".length());

        if (sub.equals("pose")) {
            return pose(s, method);
        }

        // l3d 动作播放（1 模型 + n 动作骨骼包；Live2D 模型请用 motion/expression）
        if (sub.equals("animation")) {
            return animationCtl(s, method, manager);
        }

        // 以下为 Live2D 专属控制；l3d 上屏时明确拒绝，避免误操作无响应
        if (!"live2d".equals(LAppMinimumLive2DManager.peekCurrentType())) {
            return json(409, err("current model is l3d; live2d control \"" + sub
                + "\" unavailable (pose/animation are shared)"));
        }

        // 视线/头随动：不需要模型对象（dragManager 跨线程喂）
        if (sub.equals("lookat")) {
            return lookat(s, method, manager);
        }

        // 以下需要已加载的模型
        final LAppMinimumModel model = manager.currentModel();
        if (model == null) {
            return json(503, err("no model loaded"));
        }

        if (sub.equals("param")) {
            return param(s, method, model);
        }

        if (sub.equals("lipsync")) {
            return lipsync(s, method, model);
        }

        if (sub.equals("idle")) {
            return idleCtl(s, method, model);
        }

        if (sub.equals("motion") || sub.equals("expression")) {
            final boolean face = sub.equals("expression");
            if (method == Method.GET) {
                JSONArray arr = new JSONArray();
                for (String g : model.getMotionGroupNames()) arr.put(g);
                return ok(new JSONObject().put("groups", arr));
            }
            if (method == Method.POST) {
                JSONObject in;
                try {
                    in = new JSONObject(readBody(s));
                } catch (Exception e) {
                    return json(400, err("invalid JSON body"));
                }
                if (face && in.optBoolean("clear", false)) {
                    LAppMinimumDelegate.getInstance().post(new Runnable() {
                        @Override
                        public void run() {
                            model.clearFaceMotion();
                        }
                    });
                    return json(202, new JSONObject().put("ok", true).put("cleared", true));
                }
                final String group = in.optString("group", "");
                if (!model.hasMotionGroup(group)) {
                    return json(404, err("motion group not found: " + group));
                }
                if (face) {
                    LAppMinimumDelegate.getInstance().post(new Runnable() {
                        @Override
                        public void run() {
                            model.playFaceMotion(group);
                        }
                    });
                    return json(202, new JSONObject().put("ok", true).put("face", group));
                }
                final int priority = Math.max(1, Math.min(3, in.optInt("priority", 3)));
                LAppMinimumDelegate.getInstance().post(new Runnable() {
                    @Override
                    public void run() {
                        model.playMotion(group, priority);
                    }
                });
                return json(202, new JSONObject().put("ok", true).put("motion", group).put("priority", priority));
            }
            return methodNotAllowed("GET, POST");
        }

        return json(404, err("no control route: " + sub));
    }

    /** POST/GET /api/control/param：参数 TTL 直控。{"id","value","ttl_ms"} 或批量 {"params":[...]} 或 {"clear":true}。 */
    private Response param(IHTTPSession s, Method method, LAppMinimumModel model) throws Exception {
        if (method == Method.GET) {
            return ok(new JSONObject().put("active", model.overrideCount()));
        }
        if (method == Method.POST) {
            JSONObject in;
            try {
                in = new JSONObject(readBody(s));
            } catch (Exception e) {
                return json(400, err("invalid JSON body"));
            }
            if (in.optBoolean("clear", false)) {
                model.clearParamOverrides();
                return json(200, new JSONObject().put("ok", true).put("cleared", true));
            }
            int applied = 0;
            JSONArray items = in.optJSONArray("params");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject p = items.optJSONObject(i);
                    if (p == null) continue;
                    String id = p.optString("id", "");
                    if (id.equals("")) continue;
                    model.setParamOverride(id, (float) p.getDouble("value"), p.optLong("ttl_ms", 500L));
                    applied++;
                }
            } else {
                String id = in.optString("id", "");
                if (id.equals("")) return json(400, err("missing param id"));
                model.setParamOverride(id, (float) in.getDouble("value"), in.optLong("ttl_ms", 500L));
                applied = 1;
            }
            return json(200, new JSONObject().put("ok", true).put("applied", applied));
        }
        return methodNotAllowed("GET, POST");
    }

    /** POST/GET /api/control/lipsync：口型 level 0..1（停推 150ms 自动闭合）。 */
    private Response lipsync(IHTTPSession s, Method method, LAppMinimumModel model) throws Exception {
        if (method == Method.GET) {
            return ok(new JSONObject()
                .put("level", (double) model.getMouthLevel())
                .put("auto_close_ms", 150.0)
                .put("weights", new JSONObject().put("open_y", 1.0).put("scale_y", 0.8)));
        }
        if (method == Method.POST) {
            JSONObject in;
            try {
                in = new JSONObject(readBody(s));
            } catch (Exception e) {
                return json(400, err("invalid JSON body"));
            }
            if (!in.has("level")) return json(400, err("missing level (0..1)"));
            float level = (float) in.getDouble("level");
            model.setMouthLevel(level);
            return json(200, new JSONObject().put("ok", true).put("level", (double) model.getMouthLevel()));
        }
        return methodNotAllowed("GET, POST");
    }

    /** POST/GET /api/control/lookat：视线与头随动，x/y 归一化 -1..1（y+ 实体屏向上）。 */
    private Response lookat(IHTTPSession s, Method method, LAppMinimumLive2DManager manager) throws Exception {
        if (method == Method.GET) {
            return ok(new JSONObject()
                .put("x", (double) manager.getLookX())
                .put("y", (double) manager.getLookY()));
        }
        if (method == Method.POST) {
            JSONObject in;
            try {
                in = new JSONObject(readBody(s));
            } catch (Exception e) {
                return json(400, err("invalid JSON body"));
            }
            if (in.optBoolean("reset", false)) {
                FaceTracker.get().manualOverride();
                manager.lookAtReset();
            } else {
                if (!in.has("x") && !in.has("y")) return json(400, err("need x/y or reset:true"));
                FaceTracker.get().manualOverride();
                manager.lookAt((float) in.optDouble("x", manager.getLookX()),
                    (float) in.optDouble("y", manager.getLookY()));
            }
            return json(200, new JSONObject()
                .put("ok", true)
                .put("x", (double) manager.getLookX())
                .put("y", (double) manager.getLookY()));
        }
        return methodNotAllowed("GET, POST");
    }

    /** GET/POST /api/control/idle：待机策略。{"mode":"on"/"off"} 与/或 {"interval_s":15}。 */
    private Response idleCtl(IHTTPSession s, Method method, LAppMinimumModel model) throws Exception {
        if (method == Method.GET) {
            return ok(new JSONObject()
                .put("mode", model.isIdleEnabled() ? "on" : "off")
                .put("interval_s", (double) model.getIdleIntervalSeconds()));
        }
        if (method == Method.POST) {
            JSONObject in;
            try {
                in = new JSONObject(readBody(s));
            } catch (Exception e) {
                return json(400, err("invalid JSON body"));
            }
            if (in.has("mode")) {
                String mode = in.optString("mode", "on");
                if (!mode.equals("on") && !mode.equals("off")) {
                    return json(400, err("mode must be on/off"));
                }
                model.setIdleEnabled(mode.equals("on"));
            }
            if (in.has("enabled")) {
                model.setIdleEnabled(in.optBoolean("enabled", true));
            }
            if (in.has("interval_s")) {
                model.setIdleIntervalSeconds((float) in.getDouble("interval_s"));
            }
            return json(200, new JSONObject()
                .put("ok", true)
                .put("mode", model.isIdleEnabled() ? "on" : "off")
                .put("interval_s", (double) model.getIdleIntervalSeconds()));
        }
        return methodNotAllowed("GET, POST");
    }

    /** GET/PUT(PUT) /api/control/pose：模型位置与缩放（x/y 为实体屏方向逻辑位移，y+ 向上；zoom 绕屏幕中心）。 */
    private Response pose(IHTTPSession s, Method method) throws Exception {
        if (!LAppMinimumDelegate.isGlReady()) {
            return json(503, err("renderer not ready, retry later"));
        }
        LAppMinimumLive2DManager manager = LAppMinimumLive2DManager.getInstance();

        if (method == Method.GET) {
            return ok(poseJson(manager));
        }
        if (method == Method.PUT || method == Method.POST) {
            JSONObject in;
            try {
                in = new JSONObject(readBody(s));
            } catch (Exception e) {
                return json(400, err("invalid JSON body"));
            }
            float x = in.has("x") ? (float) in.getDouble("x") : manager.getPoseX();
            float y = in.has("y") ? (float) in.getDouble("y") : manager.getPoseY();
            float zoom = in.has("zoom") ? (float) in.getDouble("zoom") : manager.getPoseZoom();
            manager.setPose(x, y, zoom);
            return ok(poseJson(manager));
        }
        return methodNotAllowed("GET, PUT");
    }

    private static JSONObject poseJson(LAppMinimumLive2DManager m) throws Exception {
        return new JSONObject()
            .put("x", (double) m.getPoseX())
            .put("y", (double) m.getPoseY())
            .put("zoom", (double) m.getPoseZoom());
    }

    /** 读取请求体为字符串（原始流，绕过 parseBody 的编码转换）。 */
    private static String readBody(IHTTPSession s) throws IOException {
        long len;
        try {
            len = Long.parseLong(s.getHeaders().get("content-length"));
        } catch (Exception e) {
            len = 0;
        }
        if (len <= 0) return "";
        if (len > 65536) throw new IOException("body too large");

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        InputStream in = s.getInputStream();
        byte[] buf = new byte[4096];
        long remaining = len;
        int n;
        while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
            bos.write(buf, 0, n);
            remaining -= n;
        }
        return bos.toString("UTF-8");
    }

    private Response status() throws Exception {        JSONObject o = new JSONObject();
        o.put("app", "live2d_luncher");
        o.put("version", "0.1");
        o.put("type", LAppMinimumLive2DManager.peekCurrentType());
        String model = LAppMinimumLive2DManager.peekCurrentModel();
        if (model != null) o.put("model", model);
        String err = LAppMinimumLive2DManager.peekLastError();
        if (err != null) o.put("last_error", err);
        o.put("fps", (double) LAppMinimumDelegate.peekFps());
        o.put("uptime_s", (double) LAppMinimumDelegate.peekUptimeSeconds());
        long rssKb = LAppMinimumDelegate.peekRssKb();
        if (rssKb > 0) o.put("rss_mb", (double) rssKb / 1024.0);
        CameraController cam = CameraController.get();
        o.put("camera", new JSONObject()
            .put("available", cam.ensureProbed())
            .put("on", cam.isOn()));
        LightController light = LightController.get();
        o.put("light", new JSONObject()
            .put("available", light.ensureProbed())
            .put("mode", light.getMode()));
        BrightnessController brightness = BrightnessController.get();
        o.put("brightness", new JSONObject()
            .put("value", brightness.getValue())
            .put("min", BrightnessController.MIN)
            .put("max", BrightnessController.MAX));
        return ok(o);
    }

    private static JSONObject toJson(ModelRepository.Descriptor d, boolean loaded, boolean selected) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", d.name);
        o.put("type", d.type);
        o.put("source", d.source == ModelRepository.Source.BUILTIN ? "builtin" : "external");
        if ("l3d".equals(d.type)) {
            o.put("format_version", d.formatVersion);
            JSONArray anims = new JSONArray();
            for (int i = 0; i < d.animationNames.size(); i++) {
                anims.put(new JSONObject()
                    .put("name", d.animationNames.get(i))
                    .put("duration_s", d.animationDurations.get(i)));
            }
            o.put("animations", anims);
        } else {
            o.put("moc3_version", d.moc3Version);
            o.put("moc3_bytes", d.moc3Bytes);
            o.put("motion_groups", d.motionGroups);
            o.put("physics", d.hasPhysics);
            o.put("textures", d.textureCount);
        }
        o.put("loaded", loaded);
        o.put("selected", selected);
        return o;
    }

    private static Response ok(JSONObject o) {
        return json(200, o);
    }

    private static Response json(int code, JSONObject o) {
        Response.Status st = Response.Status.lookup(code);
        if (st == null) st = Response.Status.INTERNAL_ERROR;
        return newFixedLengthResponse(st, "application/json", o.toString());
    }

    private static JSONObject err(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", message);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Response methodNotAllowed(String allow) {
        Response r = json(405, new JSONObject()); // 内容无意义，仅状态码
        r.addHeader("Allow", allow);
        return r;
    }
}
