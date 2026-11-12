package cn.campusai.ujscompanion;

import android.content.Context;
import android.webkit.CookieManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Collections;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Queries only the two observed, read-only school endpoints. No password handling. */
public final class SyncEngine {
    private SyncEngine() {}
    public static final class AuthException extends IOException {
        public AuthException(String message) { super(message); }
    }
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern PASSWORD = Pattern.compile("<input\\b[^>]*\\btype\\s*=\\s*['\"]?password\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER = Pattern.compile("^(content-type|accept|x-requested-with|authorization|[a-z0-9-]*(?:csrf|xsrf)[a-z0-9-]*)$", Pattern.CASE_INSENSITIVE);

    public static synchronized AppStore.Change acceptCaptured(Context context, JSONObject capture) throws IOException {
        AppStore store = new AppStore(context);
        String kind = capture.optString("kind");
        try {
            checkKind(kind);
            String url = capture.optString("url");
            checkEndpoint(kind, url);
            String body = capture.optString("body", "");
            JSONObject raw = capture.optJSONObject("data");
            if (raw == null || body.length() > 131072) throw new IOException("未收到完整的学校查询结果");
            JSONObject headers = cleanHeaders(capture.optJSONObject("headers"));
            String contentType = headers.optString("content-type", "application/x-www-form-urlencoded; charset=UTF-8");
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) throw new IOException("当前查询表单格式尚不支持后台同步");
            JSONObject template = new JSONObject();
            String userAgent = capture.optString("userAgent", "");
            if (!safeHeaderValue(userAgent)) throw new IOException("浏览器标识无效，请重新打开学校页面");
            template.put("kind", kind).put("url", url).put("body", body).put("headers", headers).put("page", SchoolConfig.page(kind)).put("userAgent", userAgent);
            Map<String,String> form = decodeForm(body);
            if (SchoolConfig.EXAMS.equals(kind)) validateExamPage(raw);
            else if (raw.optJSONArray("kbList") == null || raw.optJSONArray("sjkList") == null) throw new IOException("课表数据格式异常");
            store.saveTemplate(kind, template);
            snapshot(store, url);
            snapshot(store, SchoolConfig.page(kind));
            snapshot(store, SchoolConfig.ORIGIN);
            CookieManager.getInstance().flush();
            if (SchoolConfig.EXAMS.equals(kind) && (total(raw) != raw.getJSONArray("items").length() || pageCount(raw) > 1)) return sync(context, kind);
            AppStore.Change change = store.saveData(kind, raw, form.getOrDefault("xnm", ""), form.getOrDefault("xqm", ""));
            store.setNeedsLogin(false);
            return change;
        } catch (AuthException e) {
            store.setNeedsLogin(true);
            throw e;
        } catch (JSONException | IllegalArgumentException | IllegalStateException e) {
            throw new IOException("学校返回的数据格式不完整，已保留原有数据");
        }
    }

    public static synchronized AppStore.Change sync(Context context, String kind) throws AuthException, IOException {
        AppStore store = new AppStore(context);
        try {
            checkKind(kind);
            JSONObject template = store.template(kind);
            if (template == null || !template.has("url")) throw new AuthException("请先登录学校并读取" + SchoolConfig.label(kind));
            String url = template.optString("url");
            checkEndpoint(kind, url);
            JSONObject headers = cleanHeaders(template.optJSONObject("headers"));
            String userAgent = template.optString("userAgent", "");
            if (!safeHeaderValue(userAgent)) throw new AuthException("请重新打开学校登录，以更新浏览器会话信息");
            Map<String,String> form = decodeForm(template.optString("body", ""));
            // A normal page visit permits the school's existing SSO session to renew cookies.
            Reply page = request(store, SchoolConfig.page(kind), "GET", null, new JSONObject(), userAgent, SchoolConfig.page(kind));
            checkLogin(page);
            for (String key : new String[]{"xnm", "xqm"}) {
                String selected = selectedValue(page.text, key);
                if (selected != null && form.containsKey(key)) form.put(key, selected);
            }
            String pageKey = null;
            if (SchoolConfig.EXAMS.equals(kind)) {
                for (String candidate : new String[]{"queryModel.currentPage", "currentPage", "page", "pageNumber"}) {
                    if (form.containsKey(candidate)) { pageKey = candidate; form.put(candidate, "1"); break; }
                }
            }
            JSONObject raw = jsonReply(request(store, url, "POST", encodeForm(form), headers, userAgent, SchoolConfig.page(kind)));
            if (SchoolConfig.EXAMS.equals(kind)) {
                validateExamPage(raw);
                int expected = total(raw), pages = pageCount(raw);
                JSONArray items = raw.getJSONArray("items");
                checkPageNumber(raw, 1);
                Set<String> seenPages = new HashSet<>();
                seenPages.add(pageSignature(items));
                if (pages > 100 || expected > 10000) throw new IOException("考试结果超出安全分页上限，已保留原有数据");
                if (pages > 1 && pageKey == null) throw new IOException("考试存在多页，但学校未提供可识别的分页参数；请重新打开考试页");
                for (int number = 2; number <= pages; number++) {
                    form.put(pageKey, Integer.toString(number));
                    JSONObject next = jsonReply(request(store, url, "POST", encodeForm(form), headers, userAgent, SchoolConfig.page(kind)));
                    validateExamPage(next);
                    checkPageNumber(next, number);
                    if (total(next) != expected || pageCount(next) != pages) throw new IOException("考试列表在读取时发生变化，请稍后重试");
                    JSONArray part = next.getJSONArray("items");
                    if (part.length() == 0) throw new IOException("学校考试分页结果不完整，已保留原有数据");
                    if (!seenPages.add(pageSignature(part))) throw new IOException("学校重复返回同一页考试，已停止同步并保留原有数据");
                    for (int i = 0; i < part.length(); i++) items.put(part.get(i));
                }
                if (items.length() != expected) throw new IOException("学校考试数量与分页结果不一致，已保留原有数据");
                raw.put("_allPagesFetched", true);
                if (pageKey != null) form.put(pageKey, "1");
            }
            AppStore.Change change = store.saveData(kind, raw, form.getOrDefault("xnm", ""), form.getOrDefault("xqm", ""));
            template.put("body", encodeForm(form));
            store.saveTemplate(kind, template);
            snapshot(store, url);
            snapshot(store, SchoolConfig.ORIGIN);
            CookieManager.getInstance().flush();
            store.setNeedsLogin(false);
            return change;
        } catch (AuthException e) {
            store.setNeedsLogin(true);
            throw e;
        } catch (JSONException | IllegalArgumentException | IllegalStateException e) {
            throw new IOException("学校数据格式发生变化，已保留原有数据；请在学校页面重新读取");
        }
    }

    private static void checkKind(String kind) throws IOException {
        if (!SchoolConfig.TIMETABLE.equals(kind) && !SchoolConfig.EXAMS.equals(kind)) throw new IOException("未知同步项目");
    }
    private static void checkEndpoint(String kind, String address) throws IOException {
        try {
            URI uri = URI.create(address);
            URI base = URI.create(SchoolConfig.PROXY);
            if (!sameOrigin(uri) || !uri.getPath().equals(base.getPath() + SchoolConfig.endpoint(kind)) || uri.getUserInfo() != null) throw new IOException("查询地址与已验证的学校接口不符");
            if (SchoolConfig.EXAMS.equals(kind) && !"query".equals(decodeForm(uri.getRawQuery() == null ? "" : uri.getRawQuery()).get("doType"))) throw new IOException("考试地址缺少已验证的查询标记");
        } catch (IllegalArgumentException e) { throw new IOException("学校查询地址无效"); }
    }
    private static boolean sameOrigin(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "webvpn.ujs.edu.cn".equalsIgnoreCase(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }
    private static boolean loginUrl(String address) {
        String path = URI.create(address).getPath().toLowerCase(Locale.ROOT);
        return path.endsWith("/login") || path.contains("/cas/login") || path.contains("/authserver/login");
    }
    private static void checkLogin(Reply response) throws AuthException {
        if (loginUrl(response.url) || PASSWORD.matcher(response.text).find()) throw new AuthException("学校登录已失效，请打开应用重新验证");
    }
    private static JSONObject jsonReply(Reply response) throws IOException {
        checkLogin(response);
        try { return new JSONObject(response.text); }
        catch (JSONException e) { throw new IOException("学校未返回有效查询数据，已保留原有数据"); }
    }
    private static void validateExamPage(JSONObject data) throws IOException, JSONException {
        JSONArray rows = data.optJSONArray("items");
        if (rows == null) throw new IOException("考试响应缺少结果列表");
        int total = total(data), pages = pageCount(data);
        if (total < rows.length() || (total > 0 && pages < 1)) throw new IOException("考试分页元信息不一致");
        for (int i = 0; i < rows.length(); i++) if (!(rows.get(i) instanceof JSONObject)) throw new IOException("考试记录格式异常");
    }
    private static int number(Object value) throws IOException {
        String raw = String.valueOf(value);
        if (!raw.matches("[0-9]{1,9}")) throw new IOException("考试分页信息缺失或无效");
        return Integer.parseInt(raw);
    }
    private static int total(JSONObject data) throws IOException {
        int result = number(data.has("totalCount") ? data.opt("totalCount") : data.opt("totalResult"));
        if (data.has("totalCount") && data.has("totalResult") && result != number(data.opt("totalResult"))) throw new IOException("考试数量信息不一致");
        return result;
    }
    private static int pageCount(JSONObject data) throws IOException { return number(data.opt("totalPage")); }
    private static void checkPageNumber(JSONObject data, int expected) throws IOException {
        for (String field : new String[]{"currentPage", "page", "pageNumber"}) {
            if (data.has(field) && number(data.opt(field)) != expected) throw new IOException("学校返回的考试页码与请求不符");
        }
    }
    private static String canonical(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = object.keys(); it.hasNext();) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) out.append(canonical(array.get(i))).append(',');
            return out.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }
    private static String pageSignature(JSONArray rows) throws JSONException {
        List<String> normalized = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) normalized.add(canonical(rows.get(i)));
        Collections.sort(normalized);
        return String.join("\n", normalized);
    }
    private static boolean safeHeaderValue(String value) {
        return value != null && !value.isEmpty() && value.length() <= 8192 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }
    private static JSONObject cleanHeaders(JSONObject original) throws JSONException {
        JSONObject result = new JSONObject();
        if (original != null) for (Iterator<String> keys = original.keys(); keys.hasNext();) {
            String key = keys.next();
            String value = original.optString(key, "");
            if (HEADER.matcher(key).matches() && value.length() <= 8192 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) result.put(key.toLowerCase(Locale.ROOT), value);
        }
        return result;
    }
    private static Map<String,String> decodeForm(String body) throws IOException {
        Map<String,String> result = new LinkedHashMap<>();
        if (body.isEmpty()) return result;
        for (String entry : body.split("&")) {
            String[] parts = entry.split("=", 2);
            String key = URLDecoder.decode(parts[0], "UTF-8");
            if (result.containsKey(key)) throw new IOException("查询包含重复字段，尚不能安全自动同步");
            result.put(key, URLDecoder.decode(parts.length > 1 ? parts[1] : "", "UTF-8"));
        }
        return result;
    }
    private static String encodeForm(Map<String,String> values) throws IOException {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String,String> entry : values.entrySet()) result.add(URLEncoder.encode(entry.getKey(), "UTF-8") + "=" + URLEncoder.encode(entry.getValue(), "UTF-8"));
        return String.join("&", result);
    }
    private static String selectedValue(String html, String key) {
        Matcher selects = Pattern.compile("<select\\b([^>]*)>(.*?)</select>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (selects.find()) {
            String attrs = selects.group(1);
            if (!Pattern.compile("(?:id|name)\\s*=\\s*['\"](?:cx_)?" + key + "['\"]", Pattern.CASE_INSENSITIVE).matcher(attrs).find()) continue;
            Matcher options = Pattern.compile("<option\\b([^>]*)>", Pattern.CASE_INSENSITIVE).matcher(selects.group(2));
            while (options.find()) {
                Matcher value = Pattern.compile("\\bvalue\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE).matcher(options.group(1));
                if (!value.find()) continue;
                if (Pattern.compile("\\bselected(?:\\s|=|$)", Pattern.CASE_INSENSITIVE).matcher(options.group(1)).find()) return value.group(1);
            }
            // Without an explicit server selection, page JavaScript may choose a
            // different default. Preserve the last observed query in that case.
            return null;
        }
        return null;
    }
    private static void snapshot(AppStore store, String address) {
        String cookies = CookieManager.getInstance().getCookie(address);
        if (cookies != null && !cookies.isEmpty()) store.snapshotCookies(address, cookies);
    }
    private static final class Reply {
        final String url, text;
        Reply(String url, String text) { this.url = url; this.text = text; }
    }
    private static Reply request(AppStore store, String address, String method, String body, JSONObject headers, String userAgent, String referer) throws IOException {
        CookieManager manager = CookieManager.getInstance();
        for (int hop = 0; hop < 8; hop++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("同步已取消");
            URI uri;
            try { uri = URI.create(address); }
            catch (IllegalArgumentException e) { throw new IOException("学校跳转地址无效"); }
            if (!sameOrigin(uri)) throw new AuthException("学校要求在浏览器中重新完成登录");
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Referer", referer);
            connection.setRequestProperty("Origin", SchoolConfig.ORIGIN);
            for (Iterator<String> keys = headers.keys(); keys.hasNext();) {
                String key = keys.next();
                if (HEADER.matcher(key).matches()) connection.setRequestProperty(key, headers.optString(key));
            }
            String cookies = manager.getCookie(address);
            if (cookies == null || cookies.isEmpty()) cookies = store.cookieFor(address);
            if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
            try {
                if ("POST".equals(method)) {
                    byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
                    connection.setDoOutput(true);
                    if (!headers.has("content-type")) connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                }
                int status = connection.getResponseCode();
                for (Map.Entry<String,List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() != null && "set-cookie".equalsIgnoreCase(entry.getKey())) for (String cookie : entry.getValue()) manager.setCookie(address, cookie);
                }
                manager.flush();
                snapshot(store, address);
                snapshot(store, SchoolConfig.ORIGIN);
                if (status == 401 || status == 403) throw new AuthException("学校登录已失效或访问被拒绝，请重新验证");
                if (status >= 300 && status <= 308 && status != 304) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) throw new IOException("学校返回了无效跳转");
                    address = uri.resolve(next).toString();
                    if (status == 301 || status == 302 || status == 303) { method = "GET"; body = null; headers = new JSONObject(); }
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("学校查询暂时失败（HTTP " + status + "）");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        if (out.size() + count > MAX_BYTES) throw new IOException("学校查询结果超过读取上限");
                        out.write(buffer, 0, count);
                    }
                    return new Reply(address, new String(out.toByteArray(), StandardCharsets.UTF_8));
                }
            } finally { connection.disconnect(); }
        }
        throw new AuthException("学校登录跳转未完成，请打开应用重新验证");
    }
}
