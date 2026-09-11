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

        if (uri.startsWith("/api/control/")) {
            return control(uri, s, method);
        }

        if (uri.startsWith("/api/voice/")) {
            return voice(uri, s, method);
        }

        if (uri.startsWith("/api/camera")) {
            return camera(uri, s, method);
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
     * /api/voice/{name}/*：语音采集（现阶段 name 只有 mic）。不依赖 GL。
     * GET /api/voice/{name} → 状态；POST → {"on":bool,"rate":8000..48000}；
     * POST /{name}/start、/{name}/stop；GET /{name}/stream → chunked PCM16LE mono（隐式开启）。
     */
    private Response voice(String uri, IHTTPSession s, Method method) throws Exception {
        String rest = uri.substring("/api/voice/".length());
        String name = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        String sub = rest.contains("/") ? rest.substring(rest.indexOf('/') + 1) : "";
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
            if (!voice.micStart(true, in.optInt("rate", voice.getMicRate()))) {
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

    private Response micControl(IHTTPSession s, VoiceController voice) throws Exception {
        JSONObject in;
        try {
            in = new JSONObject(readBody(s));
        } catch (Exception e) {
            return json(400, err("invalid JSON body"));
        }
        boolean on = in.optBoolean("on", in.optBoolean("enabled", voice.isMicOn()));
        if (on) {
            if (!voice.micStart(true, in.optInt("rate", voice.getMicRate()))) {
                return json(503, err("microphone start failed"));
            }
        } else {
            voice.micStop(true);
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
            .put("width", cam.getWidth())
            .put("height", cam.getHeight())
            .put("fps", cam.getTargetFps())
            .put("quality", cam.getJpegQuality())
            .put("frame_seq", cam.getFrameSeq());
        if (cam.getLastError() != null) o.put("last_error", cam.getLastError());
        return ok(o);
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
                manager.lookAtReset();
            } else {
                if (!in.has("x") && !in.has("y")) return json(400, err("need x/y or reset:true"));
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
        String model = LAppMinimumLive2DManager.peekCurrentModel();
        if (model != null) o.put("model", model);
        String err = LAppMinimumLive2DManager.peekLastError();
        if (err != null) o.put("last_error", err);
        o.put("fps", (double) LAppMinimumDelegate.peekFps());
        o.put("uptime_s", (double) LAppMinimumDelegate.peekUptimeSeconds());
        CameraController cam = CameraController.get();
        o.put("camera", new JSONObject()
            .put("available", cam.ensureProbed())
            .put("on", cam.isOn()));
        return ok(o);
    }

    private static JSONObject toJson(ModelRepository.Descriptor d, boolean loaded, boolean selected) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", d.name);
        o.put("source", d.source == ModelRepository.Source.BUILTIN ? "builtin" : "external");
        o.put("moc3_version", d.moc3Version);
        o.put("moc3_bytes", d.moc3Bytes);
        o.put("motion_groups", d.motionGroups);
        o.put("physics", d.hasPhysics);
        o.put("textures", d.textureCount);
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
