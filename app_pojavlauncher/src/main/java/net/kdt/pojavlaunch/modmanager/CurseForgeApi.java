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

    public static List<JSONObject> searchMods(String query, String mcVersion) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL + "/mods/search");
        url.append("?gameId=").append(MINECRAFT_GAME_ID);
        url.append("&classId=").append(MOD_CLASS_ID);
        url.append("&pageSize=20");
        if (query != null && !query.isEmpty()) {
            url.append("&searchFilter=").append(URLEncoder.encode(query, "UTF-8"));
        }
        if (mcVersion != null && !mcVersion.isEmpty()) {
            String ver = extractMcVersion(mcVersion);
            url.append("&gameVersion=").append(URLEncoder.encode(ver, "UTF-8"));
        }

        JSONObject response = get(url.toString());
        List<JSONObject> result = new ArrayList<>();
        JSONArray data = response.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                result.add(data.getJSONObject(i));
            }
        }
        return result;
    }

    public static JSONArray getModFiles(int modId, String mcVersion) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL + "/mods/" + modId + "/files");
        url.append("?pageSize=15");
        if (mcVersion != null && !mcVersion.isEmpty()) {
            String ver = extractMcVersion(mcVersion);
            url.append("&gameVersion=").append(URLEncoder.encode(ver, "UTF-8"));
        }
        JSONObject response = get(url.toString());
        return response.optJSONArray("data");
    }

    // Исправленный extractMcVersion — корректно парсит форматы:
    // "fabric-loader-0.18.4-1.21.11" → "1.21.11"
    // "1.21.1-neoforge-21.1.0" → "1.21.1"
    // "1.21.1" → "1.21.1"
    public static String extractMcVersion(String versionId) {
        if (versionId == null) return "";
        String[] parts = versionId.split("-");
        // Ищем часть которая выглядит как версия MC (начинается с цифры и содержит точки)
        for (String part : parts) {
            if (part.matches("\\d+\\.\\d+.*")) {
                return part;
            }
        }
        return parts[0];
    }

    public static void downloadFile(String urlStr, File dest) throws Exception {
        downloadFileWithProgress(urlStr, dest, null);
    }

    public static void downloadFileWithProgress(String urlStr, File dest, ProgressCallback callback) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("x-api-key", API_KEY);
        conn.connect();

        int fileSize = conn.getContentLength();

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            long downloaded = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (callback != null && fileSize > 0) {
                    callback.onProgress((int)(downloaded * 100 / fileSize));
                }
            }
            if (callback != null) callback.onProgress(100);
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject get(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("x-api-key", API_KEY);
        conn.setRequestProperty("Accept", "application/json");
        conn.connect();

        InputStream in = conn.getInputStream();
        byte[] buf = new byte[65536];
        int n = 0, read;
        while ((read = in.read(buf, n, buf.length - n)) != -1) n += read;
        String json = new String(buf, 0, n, "UTF-8");
        conn.disconnect();
        return new JSONObject(json);
    }
}
