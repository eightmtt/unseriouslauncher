package net.kdt.pojavlaunch.modmanager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class ModrinthApi {
    private static final String BASE_URL = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "unseriouslauncher/1.0";

    // Поиск модов
    // mcVersion — чистая версия MC ("1.21.1")
    // rawVersion — сырая строка для определения лоадера ("fabric-loader-0.18.4-1.21.1")
    public static List<JSONObject> searchMods(String query, String mcVersion, String rawVersion) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL + "/search");
        url.append("?facets=[[\"project_type:mod\"]]");
        url.append("&limit=20");
        if (query != null && !query.isEmpty()) {
            url.append("&query=").append(URLEncoder.encode(query, "UTF-8"));
        }

        // Версия и лоадер через facets
        if (mcVersion != null && !mcVersion.isEmpty() || rawVersion != null && !rawVersion.isEmpty()) {
            StringBuilder facets = new StringBuilder("[[\"project_type:mod\"]");
            if (mcVersion != null && !mcVersion.isEmpty()) {
                facets.append(",[\"versions:").append(mcVersion).append("\"]");
            }
            if (rawVersion != null && !rawVersion.isEmpty()) {
                String loader = extractLoader(rawVersion);
                if (loader != null) {
                    facets.append(",[\"categories:").append(loader).append("\"]");
                }
            }
            facets.append("]");
            url = new StringBuilder(BASE_URL + "/search");
            url.append("?facets=").append(URLEncoder.encode(facets.toString(), "UTF-8"));
            url.append("&limit=20");
            if (query != null && !query.isEmpty()) {
                url.append("&query=").append(URLEncoder.encode(query, "UTF-8"));
            }
        }

        JSONObject response = get(url.toString());
        List<JSONObject> result = new ArrayList<>();
        JSONArray hits = response.optJSONArray("hits");
        if (hits != null) {
            for (int i = 0; i < hits.length(); i++) {
                result.add(hits.getJSONObject(i));
            }
        }
        return result;
    }

    // Список версий проекта
    public static JSONArray getProjectVersions(String projectId, String mcVersion, String rawVersion) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL + "/project/" + projectId + "/version");
        boolean first = true;
        if (mcVersion != null && !mcVersion.isEmpty()) {
            url.append("?game_versions=[\"").append(mcVersion).append("\"]");
            first = false;
        }
        if (rawVersion != null && !rawVersion.isEmpty()) {
            String loader = extractLoader(rawVersion);
            if (loader != null) {
                url.append(first ? "?" : "&");
                url.append("loaders=[\"").append(loader).append("\"]");
            }
        }
        return getArray(url.toString());
    }

    // Получить конкретную версию по ID
    public static JSONObject getVersion(String versionId) throws Exception {
        return get(BASE_URL + "/version/" + versionId);
    }

    // Определяем лоадер из сырой строки версии
    private static String extractLoader(String rawVersion) {
        if (rawVersion == null) return null;
        String lower = rawVersion.toLowerCase();
        if (lower.startsWith("fabric")) return "fabric";
        if (lower.startsWith("neoforge")) return "neoforge";
        if (lower.startsWith("forge")) return "forge";
        if (lower.startsWith("quilt")) return "quilt";
        return null;
    }

    private static JSONObject get(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.connect();

        InputStream in = conn.getInputStream();
        byte[] buf = new byte[65536];
        int n = 0, read;
        while ((read = in.read(buf, n, buf.length - n)) != -1) n += read;
        String json = new String(buf, 0, n, "UTF-8");
        conn.disconnect();
        return new JSONObject(json);
    }

    private static JSONArray getArray(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.connect();

        InputStream in = conn.getInputStream();
        byte[] buf = new byte[65536];
        int n = 0, read;
        while ((read = in.read(buf, n, buf.length - n)) != -1) n += read;
        String json = new String(buf, 0, n, "UTF-8");
        conn.disconnect();
        return new JSONArray(json);
    }
}
