package io.maizon.webossim;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Explicitly simulated webOS TV 22 Luna handlers. No privileged LG services,
 * crypto keys, DRM, native execution, or real TV hardware are accessed.
 * Each request is scoped to the running IPK's immutable app ID.
 */
public final class LunaServiceEngine {
    private static final int MAX_DB_BYTES = 1024 * 1024;
    private static final int MAX_DB_OBJECTS = 256;
    private final Context context;
    private final JSONObject profile;
    private final File appsDir;

    public interface Relaunch {
        void relaunch(String appId, JSONObject parameters);
    }
    private final Relaunch relaunch;

    public LunaServiceEngine(Context context, JSONObject profile, File appsDir, Relaunch relaunch) {
        this.context = context.getApplicationContext();
        this.profile = profile == null ? new JSONObject() : profile;
        this.appsDir = appsDir;
        this.relaunch = relaunch;
    }

    static JSONObject result(String origin) {
        JSONObject value = new JSONObject();
        try {
            value.put("returnValue", true);
            value.put("__simulator", origin);
        } catch (JSONException ignored) { }
        return value;
    }

    static JSONObject error(int code, String text) {
        JSONObject out = result("UNSUPPORTED");
        try {
            out.put("returnValue", false);
            out.put("errorCode", code);
            out.put("errorText", text);
        } catch (JSONException ignored) { }
        return out;
    }

    private static String basename(String uri) {
        return uri.startsWith("luna://") ? uri.substring(7).replaceAll("/+$", "") : "";
    }

    private SharedPreferences preferences(String appId) {
        return context.getSharedPreferences("luna-ipk-" + appId, Context.MODE_PRIVATE);
    }

    public synchronized JSONObject request(String appId, String uri, JSONObject params) {
        if (appId == null || !appId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            return error(-1, "No active IPK");
        if (uri == null || uri.length() > 512 || params == null)
            return error(-1, "Invalid Luna request");
        String normalized = basename(uri);
        int slash = normalized.indexOf('/');
        if (slash <= 0) return error(-1, "Invalid Luna URI");
        String service = normalized.substring(0, slash).toLowerCase(Locale.ROOT);
        String method = normalized.substring(slash + 1);
        try {
            if (service.equals("com.palm.connectionmanager")
                    || service.equals("com.webos.service.connectionmanager"))
                return method.equals("getStatus") ? connectionStatus(params) : error(-1, "Unsupported connection method");
            if (service.equals("com.palm.systemservice"))
                return method.equals("time/getSystemTime") ? systemTime(params) : error(-1, "Unsupported system service method");
            if (service.equals("com.webos.settingsservice"))
                return method.equals("getSystemSettings") ? settings(params) : error(-1, "Unsupported settings method");
            if (service.equals("com.webos.service.tv.systemproperty"))
                return method.equals("getSystemInfo") ? tvInfo(params) : error(-1, "Unsupported TV information method");
            if (service.equals("com.webos.audio"))
                return audio(appId, method, params);
            if (service.equals("com.webos.applicationmanager"))
                return apps(appId, method, params);
            if (service.equals("com.palm.db"))
                return database(appId, method, params);
            if (service.equals("com.webos.service.tv.display") && method.equals("getDisplayStatus")) {
                JSONObject out = result("TV_PROFILE");
                out.put("display", new JSONObject().put("width", 1920).put("height", 1080));
                return out;
            }
            if (service.equals("com.webos.service.config") && method.equals("getConfigs")) {
                JSONObject out = result("MOCK");
                out.put("configs", new JSONObject());
                return out;
            }
            // Explicit opt-out: sensitive/proprietary endpoints must never report false success.
            if (service.contains("drm") || service.contains("keymanager") ||
                    service.equals("com.webos.service.sm") || service.contains("camera") ||
                    service.contains("ble") || service.contains("media") ||
                    service.contains("activitymanager") || service.contains("magicremote")) {
                return error(-1, "Requires proprietary TV hardware/service: " + service);
            }
            JSONObject mocks = profile.optJSONObject("mockResponses");
            JSONObject fixed = mocks == null ? null : mocks.optJSONObject(uri);
            if (fixed != null) {
                JSONObject out = new JSONObject(fixed.toString());
                out.put("__simulator", "MOCK");
                return out;
            }
            return error(-1, "Unsupported Luna API: " + service + "/" + method);
        } catch (JSONException ex) {
            return error(-1, "Invalid Luna arguments: " + ex.getMessage());
        } catch (SecurityException ex) {
            return error(-1, "Android permission unavailable");
        } catch (RuntimeException ex) {
            return error(-1, "Simulated service failed: " + ex.getClass().getSimpleName());
        }
    }

    private JSONObject connectionStatus(JSONObject input) throws JSONException {
        boolean connected = false, wifi = false, wired = false, cellular = false;
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager != null) {
            Network active = manager.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : manager.getNetworkCapabilities(active);
            if (caps != null) {
                connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                wired = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            }
        }
        JSONObject out = result("ANDROID_NETWORK");
        out.put("isInternetConnectionAvailable", connected);
        out.put("wifi", new JSONObject().put("state", wifi ? "connected" : "disconnected"));
        out.put("wired", new JSONObject().put("state", wired ? "connected" : "disconnected"));
        out.put("wifiDirect", new JSONObject().put("state", "disconnected"));
        out.put("cellular", new JSONObject().put("enabled", cellular));
        out.put("subscribed", input.optBoolean("subscribe", false));
        return out;
    }

    private JSONObject systemTime(JSONObject input) throws JSONException {
        long millis = System.currentTimeMillis();
        Calendar local = Calendar.getInstance();
        TimeZone zone = local.getTimeZone();
        JSONObject lt = new JSONObject()
            .put("year", local.get(Calendar.YEAR))
            .put("month", local.get(Calendar.MONTH) + 1)
            .put("day", local.get(Calendar.DAY_OF_MONTH))
            .put("dayOfWeek", local.get(Calendar.DAY_OF_WEEK) - 1)
            .put("hour", local.get(Calendar.HOUR_OF_DAY))
            .put("minute", local.get(Calendar.MINUTE))
            .put("second", local.get(Calendar.SECOND));
        return result("ANDROID_CLOCK")
            .put("utc", millis)
            .put("localtime", lt)
            .put("offset", zone.getOffset(millis) / 60000)
            .put("timezone", zone.getID())
            .put("TZ", zone.getDisplayName(false, TimeZone.SHORT, Locale.US))
            .put("subscribed", input.optBoolean("subscribe", false));
    }

    private JSONObject settings(JSONObject input) throws JSONException {
        String category = input.optString("category", "");
        JSONArray keys = input.optJSONArray("keys");
        if (keys == null && input.has("key")) keys = new JSONArray().put(input.optString("key"));
        if (keys == null || keys.length() == 0 || keys.length() > 64)
            return error(-1, "getSystemSettings requires keys");
        Locale locale = Locale.getDefault();
        String iso2 = locale.getCountry().isEmpty() ? "BR" : locale.getCountry();
        String iso3;
        try { iso3 = new Locale("", iso2).getISO3Country(); }
        catch (Exception ignored) { iso3 = "BRA"; }
        String lang = locale.toLanguageTag();
        JSONObject locales = new JSONObject();
        for (String k : new String[]{"UI","TV","FMT","NLP","STT","AUD","AUD2"}) locales.put(k, lang);
        JSONObject localeInfo = new JSONObject().put("locales", locales)
            .put("clock", "24").put("keyboards", new JSONArray().put(locale.getLanguage()))
            .put("timezone", TimeZone.getDefault().getID());
        JSONObject values = new JSONObject();
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.optString(i, "");
            if (category.isEmpty() && key.equals("localeInfo")) values.put(key, localeInfo);
            else if (category.equals("option") && key.equals("country")) values.put(key, iso3);
            else if (category.equals("option") && key.equals("smartServiceCountryCode2")) values.put(key, iso2);
            else if (category.equals("option") && key.equals("audioGuidance")) values.put(key, "off");
            else return error(-1, "There is no matched result from DB: " + key);
        }
        JSONObject out = result("ANDROID_LOCALE").put("method", "getSystemSettings")
            .put("settings", values)
            .put("subscribed", input.optBoolean("subscribe", false));
        if (!category.isEmpty()) out.put("category", category);
        return out;
    }

    private JSONObject tvInfo(JSONObject input) throws JSONException {
        JSONArray keys = input.optJSONArray("keys");
        if (keys == null || keys.length() == 0 || keys.length() > 64)
            return error(-1, "getSystemInfo requires keys");
        JSONObject out = result("TV_PROFILE");
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.optString(i, "");
            switch (key) {
                case "modelName": out.put(key, profile.optString("modelName", "UQ8050PSB")); break;
                case "sdkVersion": out.put(key, profile.optString("webOSVersion", "7.0")); break;
                case "UHD": out.put(key, "true"); break;
                case "_3d": out.put(key, "false"); break;
                // Firmware, real chipset and TV hardware identifiers are unavailable.
                default: break;
            }
        }
        out.put("subscribed", input.optBoolean("subscribe", false));
        return out;
    }

    private JSONObject audio(String appId, String method, JSONObject params) throws JSONException {
        SharedPreferences prefs = preferences(appId);
        int volume = prefs.getInt("volume", 50);
        boolean muted = prefs.getBoolean("muted", false);
        switch (method) {
            case "volumeUp": volume = Math.min(100, volume + 1); break;
            case "volumeDown": volume = Math.max(0, volume - 1); break;
            case "setMuted":
                if (!params.has("muted") || !(params.opt("muted") instanceof Boolean))
                    return error(1, "setMuted requires boolean muted");
                muted = params.optBoolean("muted");
                break;
            default: return error(-1, "Unsupported audio method");
        }
        prefs.edit().putInt("volume", volume).putBoolean("muted", muted).apply();
        // Deliberately virtual: never change actual Android media volume without user action.
        return result("VIRTUAL_AUDIO").put("virtualVolume", volume).put("virtualMuted", muted);
    }

    private JSONObject apps(String currentId, String method, JSONObject params) throws JSONException {
        switch (method) {
            case "getAppLoadStatus": {
                String requested = params.optString("appId", "");
                if (!requested.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
                    return error(1, "Invalid appId");
                boolean found = false;
                List<IpkInstaller.InstalledApp> apps = IpkInstaller.installed(appsDir);
                for (IpkInstaller.InstalledApp installed : apps)
                    if (installed.id.equals(requested)) { found = true; break; }
                return result("SIMULATOR_LIBRARY").put("exist", found);
            }
            case "launch": {
                String id = params.optString("id", "");
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
                    return error(-105, "Invalid app ID");
                if (!id.equals(currentId))
                    return error(-101, "Cross-IPK/native launching not supported");
                JSONObject launchParams = params.optJSONObject("params");
                if (relaunch == null) return error(-1, "App relaunch unavailable");
                relaunch.relaunch(currentId, launchParams == null ? new JSONObject() : launchParams);
                return result("SIMULATOR_SELF_RELAUNCH");
            }
            default: return error(-1, "Unsupported application method");
        }
    }

    /**
     * Tiny private app-scoped Luna database for legacy web apps.
     * _id and _rev behave predictably; indexing, watchers, and multi-kind
     * permissions are not a complete com.palm.db implementation.
     */
    private JSONObject database(String appId, String method, JSONObject params) throws JSONException {
        SharedPreferences prefs = preferences(appId);
        JSONObject records;
        try { records = new JSONObject(prefs.getString("db", "{}")); }
        catch (JSONException ex) { records = new JSONObject(); }
        int next = prefs.getInt("db_next", 1);
        JSONObject out = result("APP_PRIVATE_DB");
        switch (method) {
            case "put": {
                JSONArray objects = params.optJSONArray("objects");
                if (objects == null || objects.length() < 1 || objects.length() > 64)
                    return error(-1, "put requires objects");
                JSONArray results = new JSONArray();
                // Stage all modifications before committing: avoid partial writes.
                JSONObject copy = new JSONObject(records.toString());
                for (int i = 0; i < objects.length(); i++) {
                    JSONObject object = objects.optJSONObject(i);
                    if (object == null || object.toString().length() > 65536)
                        return error(-1, "Invalid DB object");
                    String kind = object.optString("_kind", "");
                    if (kind.length() > 128 || !kind.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
                        return error(-1, "Invalid DB kind");
                    String id = object.optString("_id", "");
                    boolean create = id.isEmpty();
                    if (create) id = appId + ":" + next++;
                    else if (!id.startsWith(appId + ":") || !records.has(id))
                        return error(-1, "DB object belongs to another app or does not exist");
                    JSONObject stored = new JSONObject(object.toString());
                    int rev = create ? 1 : records.optJSONObject(id).optInt("_rev", 0) + 1;
                    if (!create && object.has("_rev") && object.optInt("_rev") != rev - 1)
                        return error(-1, "DB revision conflict");
                    stored.put("_id", id).put("_rev", rev);
                    copy.put(id, stored);
                    results.put(new JSONObject().put("id", id).put("rev", rev));
                }
                if (copy.length() > MAX_DB_OBJECTS || copy.toString().length() > MAX_DB_BYTES)
                    return error(-1, "DB quota exceeded");
                prefs.edit().putString("db", copy.toString()).putInt("db_next", next).commit();
                return out.put("results", results);
            }
            case "get": {
                JSONArray ids = params.optJSONArray("ids");
                if (ids == null || ids.length() > 128) return error(-1, "get requires ids");
                JSONArray results = new JSONArray();
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.optString(i, "");
                    if (id.startsWith(appId + ":") && records.has(id))
                        results.put(records.getJSONObject(id));
                }
                return out.put("results", results);
            }
            case "find": {
                JSONObject query = params.optJSONObject("query");
                if (query == null || query.optString("from", "").isEmpty())
                    return error(-1, "find requires query.from");
                String kind = query.optString("from");
                JSONArray where = query.optJSONArray("where");
                int limit = Math.max(1, Math.min(100, query.optInt("limit", 100)));
                JSONArray results = new JSONArray();
                java.util.Iterator<String> keys = records.keys();
                while (keys.hasNext() && results.length() < limit) {
                    String key = keys.next();
                    JSONObject doc = records.optJSONObject(key);
                    if (doc == null || !key.startsWith(appId + ":") || !kind.equals(doc.optString("_kind"))) continue;
                    boolean matches = true;
                    if (where != null) for (int i = 0; i < where.length(); i++) {
                        JSONObject cond = where.optJSONObject(i);
                        if (cond == null || !"=".equals(cond.optString("op")) ||
                                !String.valueOf(cond.opt("val")).equals(String.valueOf(doc.opt(cond.optString("prop"))))) {
                            matches = false; break;
                        }
                    }
                    if (matches) results.put(doc);
                }
                return out.put("results", results);
            }
            case "del": {
                JSONArray ids = params.optJSONArray("ids");
                if (ids == null || ids.length() > 128) return error(-1, "del requires ids");
                JSONArray results = new JSONArray();
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.optString(i, "");
                    if (id.startsWith(appId + ":") && records.has(id)) {
                        records.remove(id);
                        results.put(new JSONObject().put("id", id).put("rev", 0));
                    }
                }
                prefs.edit().putString("db", records.toString()).apply();
                return out.put("results", results);
            }
            case "putKind": {
                String id = params.optString("id", "");
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
                    return error(-1, "Invalid DB kind");
                return out.put("id", id);
            }
            default: return error(-1, "DB method unsupported (no live watches, merge, batch, media DB)");
        }
    }
}
