package com.live2d.demo.minimum.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Environment;
import android.util.Log;

import com.live2d.demo.minimum.LAppMinimumPal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 模型仓库：APK 内置（只读）与 /sdcard/live2d/models（可上传/删除）双来源。
 */
public class ModelRepository {
    private static final String TAG = "ModelRepository";
    private static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;

    public enum Source {BUILTIN, EXTERNAL}

    /** 业务错误，httpStatus 供控制面直接映射。 */
    public static class ModelException extends RuntimeException {
        public final int httpStatus;

        public ModelException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }

    public static class Descriptor {
        public String name;
        public Source source;
        /** "live2d"（Cubism model3.json）或 "l3d"（glTF 骨骼动画包，manifest.json 约定）。 */
        public String type = "live2d";
        public String homeDir;          // 以 / 结尾的资源前缀（assets 相对目录或绝对路径）
        public String model3FileName;
        public String moc3Version = "?";
        public long moc3Bytes = -1;
        public int motionGroups;
        public boolean hasPhysics;
        public int textureCount;
        // l3d 专属
        public int formatVersion = -1;
        public final List<String> animationNames = new ArrayList<String>();
        public final List<Double> animationDurations = new ArrayList<Double>();
    }

    private static ModelRepository s_instance;

    public static synchronized ModelRepository get(Context context) {
        if (s_instance == null) {
            s_instance = new ModelRepository(context.getApplicationContext());
        }
        return s_instance;
    }

    private final Context context;
    private final File externalRoot;

    private ModelRepository(Context context) {
        this.context = context;
        externalRoot = new File(Environment.getExternalStorageDirectory(), "live2d/models");
        //noinspection ResultOfMethodCallIgnored
        externalRoot.mkdirs();
    }

    public static boolean isValidName(String name) {
        if (name == null || name.length() == 0 || name.length() > 64) return false;
        if (name.equals(".") || name.equals("..")) return false;
        return name.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    /** 全仓扫描：内置在前、外部在后。 */
    public List<Descriptor> scan() {
        List<Descriptor> out = new ArrayList<Descriptor>();
        AssetManager am = context.getAssets();
        try {
            String[] entries = am.list("");
            if (entries != null) {
                Arrays.sort(entries);
                for (String e : entries) {
                    String[] sub = am.list(e);
                    if (sub == null) continue;
                    for (String s : sub) {
                        if (s.equals(e + ".model3.json")) {
                            Descriptor d = new Descriptor();
                            d.name = e;
                            d.source = Source.BUILTIN;
                            d.homeDir = e + "/";
                            d.model3FileName = s;
                            fillFromModel3(d);
                            out.add(d);
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "asset scan failed", e);
        }
        File[] dirs = externalRoot.listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File dir : dirs) {
                if (!dir.isDirectory() || dir.getName().startsWith(".")) continue;
                File m3 = new File(dir, dir.getName() + ".model3.json");
                if (!m3.isFile()) {
                    m3 = findModel3Json(dir, 0);
                }
                if (m3 != null && m3.isFile()) {
                    Descriptor d = new Descriptor();
                    d.name = dir.getName();
                    d.source = Source.EXTERNAL;
                    d.homeDir = dir.getAbsolutePath() + "/";
                    d.model3FileName = m3.getName();
                    fillFromModel3(d);
                    out.add(d);
                    continue;
                }
                File mf = findManifest(dir, 0);
                if (mf != null) {
                    Descriptor d = new Descriptor();
                    d.name = dir.getName();
                    d.type = "l3d";
                    d.source = Source.EXTERNAL;
                    d.homeDir = dir.getAbsolutePath() + "/";
                    d.model3FileName = mf.getName();   // 元数据文件名沿用该字段
                    fillFromManifest(d, mf);
                    out.add(d);
                }
            }
        }
        return out;
    }

    public Descriptor find(String name) {
        for (Descriptor d : scan()) {
            if (d.name.equals(name)) return d;
        }
        return null;
    }

    /** 从 model3.json 解析 moc3 版本、贴图数、动作组数等统计。 */
    private void fillFromModel3(Descriptor d) {
        try {
            byte[] bytes = LAppMinimumPal.loadFileAsBytes(d.homeDir + d.model3FileName);
            JSONObject root = new JSONObject(new String(bytes, "UTF-8"));
            JSONObject fr = root.optJSONObject("FileReferences");
            if (fr == null) return;
            String mocRel = fr.optString("Moc", "");
            if (!mocRel.equals("")) {
                String mocPath = d.homeDir + mocRel;
                byte[] head = readHeader(mocPath, 5);
                if (pathIsFile(mocPath)) d.moc3Bytes = new File(mocPath).length();
                if (head.length >= 5 && "MOC3".equals(new String(head, 0, 4, "UTF-8"))) {
                    int v = head[4] & 0xFF;
                    d.moc3Version = versionLabel(v) + " (0x0" + v + ")";
                }
            }
            JSONObject motions = fr.optJSONObject("Motions");
            d.motionGroups = motions != null ? motions.length() : 0;
            d.hasPhysics = !fr.optString("Physics", "").equals("");
            JSONArray textures = fr.optJSONArray("Textures");
            d.textureCount = textures != null ? textures.length() : 0;
        } catch (Exception e) {
            Log.w(TAG, "parse model3.json failed: " + d.name, e);
        }
    }

    private static String versionLabel(int v) {
        switch (v) {
            case 1: return "3.0";
            case 2: return "3.3";
            case 3: return "4.0";
            case 4: return "4.2";
            case 5: return "5.0";
            default: return "unknown";
        }
    }

    private static boolean pathIsFile(String path) {
        return path.startsWith("/") && new File(path).isFile();
    }

    /** 从 l3d 的 manifest.json 解析描述（字段缺失由 fillFromManifest 校验兜底）。 */
    private void fillFromManifest(Descriptor d, File manifestFile) {
        try {
            JSONObject root = new JSONObject(new String(readAll(new FileInputStream(manifestFile)), "UTF-8"));
            d.formatVersion = root.optInt("format_version", -1);
            JSONArray anims = root.optJSONArray("animations");
            if (anims != null) {
                for (int i = 0; i < anims.length(); i++) {
                    JSONObject a = anims.getJSONObject(i);
                    d.animationNames.add(a.optString("name", ""));
                    d.animationDurations.add(a.optDouble("duration_s", 0));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parse manifest.json failed: " + d.name, e);
        }
    }

    private static File findManifest(File dir, int depth) {
        if (depth > 3) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals("manifest.json")) return f;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File r = findManifest(f, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static byte[] readHeader(String path, int n) {
        InputStream is = null;
        try {
            is = LAppMinimumPal.openStream(path);
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = is.read(buf, off, n - off);
                if (r < 0) break;
                off += r;
            }
            return off == n ? buf : Arrays.copyOf(buf, off);
        } catch (IOException e) {
            return new byte[0];
        } finally {
            if (is != null) {
                try { is.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static File findModel3Json(File dir, int depth) {
        if (depth > 3) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".model3.json")) return f;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File r = findModel3Json(f, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * 接收 zip 上传：暂存 → 解压（防 Zip Slip）→ 按包类型定位与校验 → 落位。
     * live2d = *.model3.json + moc3 校验；l3d = manifest.json + model/anims glb 校验。
     * nameParam 为空时用 model3.json 主干或 manifest.name。
     */
    public Descriptor upload(InputStream in, long contentLength, String nameParam) throws IOException {
        if (contentLength <= 0) throw new ModelException(400, "Content-Length required");
        if (contentLength > MAX_UPLOAD_BYTES) throw new ModelException(400, "upload too large (max 200MB)");

        File staging = new File(externalRoot, ".upload-" + System.currentTimeMillis());
        File zipTmp = new File(externalRoot, ".upload.zip-" + System.currentTimeMillis());
        try {
            copyToFile(in, zipTmp, contentLength);
            unzip(zipTmp, staging);

            File model3 = findModel3Json(staging, 0);
            File manifest = null;
            if (model3 == null) {
                manifest = findManifest(staging, 0);
                if (manifest == null) {
                    throw new ModelException(400,
                        "zip contains neither a .model3.json nor a l3d manifest.json");
                }
            }

            File modelDir = (model3 != null ? model3 : manifest).getParentFile();
            if (modelDir == null) modelDir = staging;
            String finalName;
            if (model3 != null) {
                String stem = model3.getName();
                if (stem.endsWith(".model3.json")) {
                    stem = stem.substring(0, stem.length() - ".model3.json".length());
                }
                finalName = stem;
            } else {
                String stem = "";
                try {
                    JSONObject m = new JSONObject(
                        new String(readAll(new FileInputStream(manifest)), "UTF-8"));
                    stem = m.optString("name", "");
                } catch (Exception ignored) {
                }
                finalName = stem;
            }
            if (nameParam != null && nameParam.length() > 0) finalName = nameParam;
            if (!isValidName(finalName)) throw new ModelException(400, "invalid model name: " + finalName);

            File target = new File(externalRoot, finalName);
            if (target.exists()) throw new ModelException(409, "model already exists: " + finalName);

            Descriptor d = new Descriptor();
            d.name = finalName;
            d.source = Source.EXTERNAL;
            if (model3 != null) {
                validateMoc(modelDir, model3);
                d.type = "live2d";
                d.model3FileName = model3.getName();
            } else {
                validateL3d(modelDir, manifest, d);
                d.type = "l3d";
                d.model3FileName = manifest.getName();
            }

            //noinspection ResultOfMethodCallIgnored
            target.mkdirs();
            File[] items = modelDir.listFiles();
            if (items != null) {
                for (File item : items) {
                    File dst = new File(target, item.getName());
                    if (!item.renameTo(dst)) {
                        copyRecursively(item, dst);
                        deleteRecursively(item);
                    }
                }
            }
            deleteRecursively(staging);

            d.homeDir = target.getAbsolutePath() + "/";
            if (model3 != null) {
                fillFromModel3(d);
            } else {
                fillFromManifest(d, new File(target, manifest.getName()));
            }
            return d;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zipTmp.delete();
            deleteRecursively(staging); // 失败路径清理残留
        }
    }

    /** l3d 包校验：format/type、model.glb 魔数、动作文件存在且是 glb。 */
    private void validateL3d(File modelDir, File manifest, Descriptor d) {
        try {
            JSONObject root = new JSONObject(new String(readAll(new FileInputStream(manifest)), "UTF-8"));
            int fmt = root.optInt("format_version", -1);
            if (fmt != 1) throw new ModelException(400, "unsupported l3d format_version: " + fmt);
            if (!"l3d".equals(root.optString("type", ""))) {
                throw new ModelException(400, "manifest type must be \"l3d\"");
            }
            String modelFile = root.optString("model", "");
            if (modelFile.equals("")) throw new ModelException(400, "manifest has no model");
            checkGlb(new File(modelDir, modelFile));
            JSONArray anims = root.optJSONArray("animations");
            if (anims != null) {
                for (int i = 0; i < anims.length(); i++) {
                    String f = anims.getJSONObject(i).optString("file", "");
                    if (!f.equals("")) checkGlb(new File(modelDir, f));
                }
            }
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(400, "bad manifest.json: " + e);
        }
    }

    private static void checkGlb(File f) {
        if (!f.isFile()) throw new ModelException(400, "glb missing: " + f.getName());
        byte[] head = readHeader(f.getAbsolutePath(), 4);
        if (head.length < 4 || head[0] != 'g' || head[1] != 'l' || head[2] != 'T' || head[3] != 'F') {
            throw new ModelException(400, "not a valid glb: " + f.getName());
        }
    }

    private void validateMoc(File modelDir, File model3) {
        try {
            JSONObject root = new JSONObject(new String(readAll(new FileInputStream(model3)), "UTF-8"));
            JSONObject fr = root.optJSONObject("FileReferences");
            String mocRel = fr != null ? fr.optString("Moc", "") : "";
            if (mocRel.equals("")) throw new ModelException(400, "model3.json has no FileReferences.Moc");
            File moc = new File(modelDir, mocRel);
            if (!moc.isFile()) throw new ModelException(400, "moc file missing: " + mocRel);
            byte[] head = readHeader(moc.getAbsolutePath(), 5);
            if (head.length < 5 || !"MOC3".equals(new String(head, 0, 4, "UTF-8"))) {
                throw new ModelException(400, "not a valid moc3: " + mocRel);
            }
            int v = head[4] & 0xFF;
            if (v < 1 || v > 5) throw new ModelException(400, "unsupported moc3 version byte: " + v);
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(400, "bad model3.json: " + e);
        }
    }

    public void delete(String name, boolean builtin) {
        if (builtin) throw new ModelException(403, "builtin model cannot be deleted");
        File dir = new File(externalRoot, name);
        if (!dir.isDirectory()) throw new ModelException(404, "model not found: " + name);
        deleteRecursively(dir);
    }

    // ---- 选中模型持久化（重启后自动加载） ----
    private static final String PREFS = "live2d";
    private static final String KEY_SELECTED = "selected_model";

    public String getSelected() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED, null);
    }

    public void setSelected(String name) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, name).commit();
    }

    // ---- IO 工具 ----

    private static void copyToFile(InputStream in, File dst, long count) throws IOException {
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[65536];
            long remaining = count;
            int n;
            while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                out.write(buf, 0, n);
                remaining -= n;
            }
            if (remaining > 0) throw new ModelException(400, "body shorter than Content-Length");
        } finally {
            out.close();
        }
    }

    private static void unzip(File zip, File destDir) throws IOException {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip), 65536));
        try {
            String destCanon = destDir.getCanonicalPath() + File.separator;
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("__MACOSX") || name.contains("/__MACOSX") || name.endsWith(".DS_Store")) {
                    continue;
                }
                File out = new File(destDir, name);
                if (!out.getCanonicalPath().startsWith(destCanon)) {
                    throw new ModelException(400, "bad zip entry: " + name);
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                } finally {
                    fos.close();
                }
            }
        } finally {
            zis.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void copyRecursively(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) copyRecursively(c, new File(dst, c.getName()));
            }
        } else {
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
                out.close();
            }
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
