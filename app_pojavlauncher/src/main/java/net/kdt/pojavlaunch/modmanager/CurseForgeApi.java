package net.kdt.pojavlaunch.modmanager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class CurseForgeApi {
    private static final String BASE_URL = "https://api.curseforge.com/v1";
    public static final String API_KEY = "$2a$10$nmgqE1JtzyaSe9gxDlRTWuGCWlVGMq9qE5QRXyAP13hYDUvmXYXa2";
    private static final int MINECRAFT_GAME_ID = 432;
    private static final int MOD_CLASS_ID = 6;

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    // sortBy — числовой код поля сортировки CurseForge
    // 1=LastUpdated, 2=Popularity, 3=Name, 6=Downloads, 10=Newest
    public static List<JSONObject> searchMods(String query, String mcVersion, String rawVersion,
                                               int offset, int pageSize, String sortBy,
                                               int[] totalHolder) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL + "/mods/search");
        url.append("?gameId=").append(MINECRAFT_GAME_ID);
        url.append("&classId=").append(MOD_CLASS_ID);
        url.append("&pageSize=").append(pageSize);
        url.append("&index=").append(offset);
        if (sortBy != null && !sortBy.isEmpty()) {
            url.append("&sortField=").append(sortBy);
            url.append("&sortOrder=desc");
        }
        if (query != null && !query.isEmpty()) {
            url.append("&searchFilter=").append(URLEncoder.encode(query, "UTF-8"));
        }
        if (mcVersion != null && !mcVersion.isEmpty()) {
            url.append("&gameVersion=").append(URLEncoder.encode(mcVersion, "UTF-8"));
        }
        if (rawVersion != null && !rawVersion.isEmpty()) {
            int loader = extractModLoader(rawVersion);
            if (loader != -1) url.append("&modLoaderType=").append(loader);
        }

        JSONObject response = get(url.toString());
        if (totalHolder != null) {
            JSONObject pagination = response.optJSONObject("pagination");
            totalHolder[0] = pagination != null ? pagination.optInt("totalCount", 0) : 0;
        }

        List<JSONObject> result = new ArrayList<>();
        JSONArray data = response.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) result.add(data.getJSONObject(i));
        }
        return result;
    }

    public static JSONArray getModFiles(int modId, String mcVersion, String rawVersion) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL + "/mods/" + modId + "/files");
        url.append("?pageSize=15");
        if (mcVersion != null && !mcVersion.isEmpty()) {
            url.append("&gameVersion=").append(URLEncoder.encode(mcVersion, "UTF-8"));
        }
        if (rawVersion != null && !rawVersion.isEmpty()) {
            int loader = extractModLoader(rawVersion);
            if (loader != -1) url.append("&modLoaderType=").append(loader);
        }
        JSONObject response = get(url.toString());
        return response.optJSONArray("data");
    }

    public static String extractMcVersion(String versionId) {
        if (versionId == null || versionId.isEmpty()) return "";
        String[] parts = versionId.split("-");
        for (String part : parts) {
            if (part.startsWith("1.") && part.matches("1\\.\\d+(\\.\\d+)?")) return part;
        }
        for (String part : parts) {
            if (part.matches("\\d+\\.\\d+(\\.\\d+)?")) return part;
        }
        return "";
    }

    public static int extractModLoader(String versionId) {
        if (versionId == null) return -1;
        String lower = versionId.toLowerCase();
        if (lower.startsWith("fabric")) return 4;
        if (lower.startsWith("neoforge")) return 6;
        if (lower.startsWith("forge")) return 1;
        if (lower.startsWith("quilt")) return 5;
        return -1;
    }

    public static void downloadFile(String urlStr, File dest) throws Exception {
        downloadFileWithProgress(urlStr, dest, null);
    }

    public static void downloadFileWithProgress(String urlStr, File dest, ProgressCallback callback) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();
        int fileSize = conn.getContentLength();
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[16384];
            int n;
            long downloaded = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (callback != null && fileSize > 0)
                    callback.onProgress((int)(downloaded * 100 / fileSize));
            }
            if (callback != null) callback.onProgress(100);
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject get(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("x-api-key", API_KEY);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.connect();
        int code = conn.getResponseCode();
        InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) throw new Exception("HTTP " + code);
        byte[] buf = new byte[131072];
        int n = 0, read;
        while ((read = in.read(buf, n, buf.length - n)) != -1) n += read;
        String json = new String(buf, 0, n, "UTF-8");
        conn.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code + ": " + json);
        return new JSONObject(json);
    }
}
