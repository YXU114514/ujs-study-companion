package cn.campusai.ujscompanion;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private offline cache plus separately encrypted session credentials. */
public final class AppStore {
    private static final Object LOCK = new Object();
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final AtomicFile cache;
    private final AtomicFile credentials;
    private final String keyAlias;

    public AppStore(Context context) {
        Context app = context.getApplicationContext();
        cache = new AtomicFile(new File(app.getFilesDir(), "companion-cache-v1.json"));
        credentials = new AtomicFile(new File(app.getFilesDir(), "companion-session-v1.bin"));
        keyAlias = app.getPackageName() + ".session.aes.v1";
    }

    public static final class Change {
        public final boolean initial;
        public final boolean changed;
        public final int added;
        public final int removed;

        public Change(boolean initial, boolean changed, int added, int removed) {
            this.initial = initial;
            this.changed = changed;
            this.added = added;
            this.removed = removed;
        }
    }

    public JSONObject data(String kind) {
        requireKind(kind);
        synchronized (LOCK) {
            return section(readCache(), "data").optJSONObject(kind);
        }
    }

    public synchronized Change saveData(String kind, JSONObject raw, String year, String term) {
        requireKind(kind);
        requireSemester(year, term);
        JSONObject fresh = normalize(kind, raw, year, term);
        synchronized (LOCK) {
            JSONObject root = readCache();
            JSONObject data = section(root, "data");
            JSONObject previous = data.optJSONObject(kind);
            Change result = compare(previous, fresh, kind);
            put(fresh, "updatedAt", System.currentTimeMillis());
            put(data, kind, fresh);
            put(root, "data", data);
            JSONObject errors = section(root, "errors");
            errors.remove(kind);
            put(root, "errors", errors);
            put(root, "needsLogin", false);
            writeCache(root);
            return result;
        }
    }

    public String firstMonday(String year, String term) {
        if (!validSemester(year, term)) return "";
        synchronized (LOCK) {
            JSONObject anchor = section(readCache(), "anchors").optJSONObject(semesterKey(year, term));
            if (anchor != null) return anchor.optString("firstMonday", "");
            // Historical calibration from the user's original app, never a universal calendar.
            return "2026".equals(year) && "3".equals(term) ? "2026-08-31" : "";
        }
    }

    public int totalWeeks(String year, String term) {
        if (!validSemester(year, term)) return 60;
        synchronized (LOCK) {
            JSONObject anchor = section(readCache(), "anchors").optJSONObject(semesterKey(year, term));
            int value = anchor == null ? 60 : anchor.optInt("totalWeeks", 60);
            return value >= 1 && value <= 60 ? value : 60;
        }
    }

    public void saveAnchor(String year, String term, String monday, int totalWeeks) {
        requireSemester(year, term);
        if (SemesterClock.parseMonday(monday) == null || totalWeeks < 1 || totalWeeks > 60) {
            throw new IllegalArgumentException("请选择有效的首周周一，学期总周数须为 1–60");
        }
        synchronized (LOCK) {
            JSONObject root = readCache();
            JSONObject anchors = section(root, "anchors");
            JSONObject value = new JSONObject();
            put(value, "firstMonday", monday);
            put(value, "totalWeeks", totalWeeks);
            put(anchors, semesterKey(year, term), value);
            put(root, "anchors", anchors);
            writeCache(root);
        }
    }

    public boolean needsLogin() {
        synchronized (LOCK) { return readCache().optBoolean("needsLogin", true); }
    }

    public void setNeedsLogin(boolean value) {
        synchronized (LOCK) {
            JSONObject root = readCache();
            put(root, "needsLogin", value);
            writeCache(root);
        }
    }

    public String error(String kind) {
        requireKind(kind);
        synchronized (LOCK) { return section(readCache(), "errors").optString(kind, ""); }
    }

    public void setError(String kind, String message) {
        requireKind(kind);
        synchronized (LOCK) {
            JSONObject root = readCache();
            JSONObject errors = section(root, "errors");
            if (message == null || message.isEmpty()) errors.remove(kind);
            else put(errors, kind, message.length() > 500 ? message.substring(0, 500) : message);
            put(root, "errors", errors);
            writeCache(root);
        }
    }

    public long lastSuccess(String kind) {
        JSONObject value = data(kind);
        return value == null ? 0 : value.optLong("updatedAt", 0);
    }

    public JSONObject template(String kind) {
        requireKind(kind);
        synchronized (LOCK) { return section(readCredentials(), "templates").optJSONObject(kind); }
    }

    public void saveTemplate(String kind, JSONObject value) {
        requireKind(kind);
        synchronized (LOCK) {
            JSONObject root = readCredentials();
            JSONObject templates = section(root, "templates");
            if (value == null) templates.remove(kind);
            else put(templates, kind, copy(value));
            put(root, "templates", templates);
            writeCredentials(root);
        }
    }

    public void snapshotCookies(String url, String cookie) {
        String key = cookieKey(url);
        synchronized (LOCK) {
            JSONObject root = readCredentials();
            JSONObject cookies = section(root, "cookies");
            if (cookie == null || cookie.trim().isEmpty()) cookies.remove(key);
            else {
                if (cookie.length() > 65536 || cookie.indexOf('\r') >= 0 || cookie.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("登录状态格式无效");
                }
                put(cookies, key, cookie);
            }
            put(root, "cookies", cookies);
            writeCredentials(root);
        }
    }

    /** Exact path snapshot, with a same-origin root snapshot as the only fallback. */
    public String cookieFor(String url) {
        String key = cookieKey(url);
        synchronized (LOCK) {
            JSONObject cookies = section(readCredentials(), "cookies");
            String exact = cookies.optString(key, "");
            if (!exact.isEmpty()) return exact;
            URI uri = URI.create(key);
            String root = uri.getScheme() + "://" + uri.getRawAuthority() + "/";
            return cookies.optString(root, "");
        }
    }

    /** Clears this store and its encryption key; UI also clears WebView cookies. */
    public void reset() {
        synchronized (LOCK) {
            cache.delete();
            credentials.delete();
            try {
                KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
                keys.load(null);
                if (keys.containsAlias(keyAlias)) keys.deleteEntry(keyAlias);
            } catch (Exception failure) {
                throw new IllegalStateException("本地密钥清理失败，请重试", failure);
            }
        }
    }

    static JSONObject normalize(String kind, JSONObject raw, String year, String term) {
        if (raw == null) throw invalid("响应为空");
        requireKind(kind);
        requireSemester(year, term);
        JSONObject result = new JSONObject();
        put(result, "year", year);
        put(result, "term", term);
        if (SchoolConfig.TIMETABLE.equals(kind)) {
            JSONArray courses = rows(raw, "kbList");
            JSONArray practical = rows(raw, "sjkList");
            JSONArray normalized = new JSONArray();
            JSONArray normalizedPractical = new JSONArray();
            for (int i = 0; i < courses.length(); i++) {
                JSONObject row = rowAt(courses, i);
                ensureRowSemester(row, year, term);
                JSONObject value = new JSONObject();
                put(value, "course", requiredText(row, "kcmc"));
                put(value, "teacher", text(row, "xm"));
                put(value, "location", text(row, "cdmc"));
                int weekday = number(text(row, "xqj"), "星期");
                if (weekday < 1 || weekday > 7) throw invalid("星期超出范围");
                put(value, "weekday", weekday);
                String periods = text(row, "jcs");
                if (periods.isEmpty()) periods = text(row, "jc");
                validatePeriods(periods);
                put(value, "periods", periods);
                String weeks = requiredText(row, "zcd");
                if (WeekPattern.parse(weeks).isEmpty()) throw invalid("无法识别课程周次");
                put(value, "weeks", weeks);
                normalized.put(value);
            }
            for (int i = 0; i < practical.length(); i++) {
                JSONObject row = rowAt(practical, i);
                ensureRowSemester(row, year, term);
                JSONObject value = new JSONObject();
                put(value, "course", requiredText(row, "kcmc"));
                put(value, "teacher", text(row, "jsxm"));
                put(value, "weeks", text(row, "qsjsz"));
                put(value, "credits", text(row, "xf"));
                normalizedPractical.put(value);
            }
            put(result, "courses", normalized);
            put(result, "practical", normalizedPractical);
        } else {
            JSONArray exams = rows(raw, "items");
            Integer total = null;
            for (String key : new String[]{"totalCount", "totalResult"}) {
                if (raw.has(key) && !raw.isNull(key)) {
                    int n = number(text(raw, key), "考试总数");
                    if (n < 0 || (total != null && total != n)) throw invalid("考试分页总数矛盾");
                    total = n;
                }
            }
            int pages = number(requiredText(raw, "totalPage"), "考试分页");
            boolean aggregated = Boolean.TRUE.equals(raw.opt("_allPagesFetched"));
            if (total == null || total != exams.length() || pages < 0
                    || (pages > 1 && !aggregated) || (total > 0 && pages == 0)
                    || (total == 0 && pages > 1)) {
                throw invalid("考试分页尚未完整读取，保留上次结果");
            }
            JSONArray normalized = new JSONArray();
            for (int i = 0; i < exams.length(); i++) {
                JSONObject row = rowAt(exams, i);
                ensureRowSemester(row, year, term);
                JSONObject value = new JSONObject();
                put(value, "course", requiredText(row, "kcmc"));
                put(value, "exam", text(row, "ksmc"));
                put(value, "time", text(row, "kssj"));
                put(value, "location", text(row, "cdmc"));
                put(value, "campus", text(row, "cdxqmc"));
                normalized.put(value);
            }
            put(result, "exams", normalized);
        }
        return result;
    }

    static Change compare(JSONObject oldData, JSONObject newData, String kind) {
        if (oldData == null) return new Change(true, false, 0, 0);
        Map<String, Integer> oldRows = multiset(oldData, kind);
        Map<String, Integer> newRows = multiset(newData, kind);
        int added = 0, removed = 0;
        for (Map.Entry<String, Integer> entry : newRows.entrySet()) {
            added += Math.max(0, entry.getValue() - oldRows.getOrDefault(entry.getKey(), 0));
        }
        for (Map.Entry<String, Integer> entry : oldRows.entrySet()) {
            removed += Math.max(0, entry.getValue() - newRows.getOrDefault(entry.getKey(), 0));
        }
        return new Change(false, added > 0 || removed > 0, added, removed);
    }

    private static Map<String, Integer> multiset(JSONObject data, String kind) {
        Map<String, Integer> result = new TreeMap<>();
        String[] arrays = SchoolConfig.TIMETABLE.equals(kind)
                ? new String[]{"courses", "practical"} : new String[]{"exams"};
        for (String name : arrays) {
            JSONArray rows = data.optJSONArray(name);
            if (rows == null) throw invalid("本地缓存结构无效");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rowAt(rows, i);
                List<String> keys = new ArrayList<>();
                Iterator<String> iterator = row.keys();
                while (iterator.hasNext()) keys.add(iterator.next());
                Collections.sort(keys);
                JSONArray canonical = new JSONArray();
                canonical.put(name).put(data.optString("year")).put(data.optString("term"));
                for (String key : keys) canonical.put(key).put(row.opt(key));
                String signature = canonical.toString();
                result.put(signature, result.getOrDefault(signature, 0) + 1);
            }
        }
        return result;
    }

    private static void ensureRowSemester(JSONObject row, String year, String term) {
        String rowYear = text(row, "xnm");
        String rowTerm = text(row, "xqm");
        if ((!rowYear.isEmpty() && !year.equals(rowYear)) || (!rowTerm.isEmpty() && !term.equals(rowTerm))) {
            throw invalid("查询学期与返回记录不一致");
        }
    }

    private static void validatePeriods(String periods) {
        String value = periods.replace("第", "").replace("节", "").trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+)\\s*(?:[-–—~～]\\s*(\\d+))?$").matcher(value);
        if (!matcher.matches()) throw invalid("无法识别课程节次");
        int start = number(matcher.group(1), "节次");
        int end = matcher.group(2) == null ? start : number(matcher.group(2), "节次");
        if (start < 1 || end < start || end > 30) throw invalid("课程节次超出范围");
    }

    private static JSONArray rows(JSONObject raw, String name) {
        JSONArray value = raw.optJSONArray(name);
        if (value == null || value.length() > 5000) throw invalid("缺少有效的 " + name + " 列表");
        return value;
    }

    private static JSONObject rowAt(JSONArray rows, int index) {
        JSONObject row = rows.optJSONObject(index);
        if (row == null) throw invalid("记录不是对象");
        return row;
    }

    private static String requiredText(JSONObject row, String name) {
        String result = text(row, name);
        if (result.isEmpty()) throw invalid("缺少 " + name + " 字段");
        return result;
    }

    private static String text(JSONObject row, String name) {
        Object value = row.opt(name);
        if (value == null || value == JSONObject.NULL) return "";
        if (!(value instanceof String) && !(value instanceof Number)) throw invalid(name + " 字段类型无效");
        String result = String.valueOf(value).trim();
        if (result.length() > 8192) throw invalid(name + " 字段过长");
        return result;
    }

    private static int number(String value, String label) {
        if (value == null || !value.matches("[0-9]+")) throw invalid(label + " 不是非负整数");
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { throw invalid(label + " 超出范围"); }
    }

    private static void requireKind(String kind) {
        if (!SchoolConfig.TIMETABLE.equals(kind) && !SchoolConfig.EXAMS.equals(kind)) {
            throw new IllegalArgumentException("未知数据类型");
        }
    }

    private static void requireSemester(String year, String term) {
        if (!validSemester(year, term)) {
            throw invalid("学期信息无效");
        }
    }

    private static boolean validSemester(String year, String term) {
        return year != null && year.matches("[0-9]{4}") && term != null && term.matches("[0-9]{1,2}");
    }

    private static String semesterKey(String year, String term) { return year + ":" + term; }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("学校数据格式异常：" + detail);
    }

    private JSONObject readCache() { return parse(readBytes(cache)); }

    private void writeCache(JSONObject root) { writeBytes(cache, root.toString().getBytes(StandardCharsets.UTF_8)); }

    private JSONObject readCredentials() {
        byte[] bytes = readBytes(credentials);
        if (bytes.length == 0) return new JSONObject();
        try {
            JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            byte[] iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP);
            if (iv.length != 12) throw new IllegalStateException("Invalid IV");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(false), new GCMParameterSpec(128, iv));
            return parse(cipher.doFinal(encrypted));
        } catch (Exception failure) {
            throw new IllegalStateException("本地登录状态无法解密，请清除本地登录后重新登录", failure);
        }
    }

    private void writeCredentials(JSONObject value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(true));
            byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject envelope = new JSONObject();
            put(envelope, "version", 1);
            put(envelope, "iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            put(envelope, "ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP));
            writeBytes(credentials, envelope.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException("本地登录状态加密保存失败", failure);
        }
    }

    private SecretKey secretKey(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(keyAlias)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(keyAlias, null)).getSecretKey();
        }
        if (!create) throw new IllegalStateException("Key missing");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String cookieKey(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !(host.equalsIgnoreCase("ujs.edu.cn") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".ujs.edu.cn"))
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException();
            }
            String path = uri.getRawPath();
            return "https://" + host.toLowerCase(java.util.Locale.ROOT) + (path == null || path.isEmpty() ? "/" : path);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("只能保存学校 HTTPS 页面的登录状态");
        }
    }

    private static JSONObject copy(JSONObject source) { return parse(source.toString().getBytes(StandardCharsets.UTF_8)); }

    private static JSONObject section(JSONObject root, String name) {
        JSONObject section = root.optJSONObject(name);
        return section == null ? new JSONObject() : section;
    }

    private static JSONObject parse(byte[] bytes) {
        if (bytes.length == 0) return new JSONObject();
        try { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
        catch (JSONException failure) { throw new IllegalStateException("本地数据损坏，请重新同步或清除本地数据", failure); }
    }

    private static void put(JSONObject value, String key, Object field) {
        try { value.put(key, field); }
        catch (JSONException failure) { throw new IllegalArgumentException("无法保存数据字段", failure); }
    }

    private static byte[] readBytes(AtomicFile file) {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return new byte[0];
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int count;
            while ((count = input.read(block)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new IllegalStateException("本地文件过大");
                output.write(block, 0, count);
            }
            return output.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("无法读取本地数据", failure);
        }
    }

    private static void writeBytes(AtomicFile file, byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("数据超过本地缓存容量");
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (Exception failure) {
            if (stream != null) file.failWrite(stream);
            throw new IllegalStateException("本地数据保存失败，旧数据已保留", failure);
        }
    }
}
