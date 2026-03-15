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
    private static final String USER_AGENT = "unseriouslauncher/1.0 (github.com/eightmtt/unseriouslauncher)";

    public static List<JSONObject> searchMods(String query, String mcVersion, String rawVersion,
                                               int offset, int limit, String sortBy, int[] totalHolder) throws Exception {
        StringBuilder facets = new StringBuilder("[");
        facets.append("[\"project_type:mod\"]");
        if (mcVersion != null && !mcVersion.isEmpty()) {
            facets.append(",[\"versions:").append(mcVersion).append("\"]");
        }
        String loader = extractLoader(rawVersion);
        if (loader != null) {
            facets.append(",[\"categories:").append(loader).append("\"]");
        }
        facets.append("]");

        StringBuilder url = new StringBuilder(BASE_URL + "/search");
        url.append("?facets=").append(URLEncoder.encode(facets.toString(), "UTF-8"));
        url.append("&limit=").append(limit);
        url.append("&offset=").append(offset);
        if (sortBy != null && !sortBy.isEmpty()) {
            url.append("&index=").append(sortBy);
        }
        if (query != null && !query.isEmpty()) {
            url.append("&query=").append(URLEncoder.encode(query, "UTF-8"));
        }

        JSONObject response = get(url.toString());
        if (totalHolder != null) totalHolder[0] = response.optInt("total_hits", 0);

        List<JSONObject> result = new ArrayList<>();
        JSONArray hits = response.optJSONArray("hits");
        if (hits != null) {
            for (int i = 0; i < hits.length(); i++) result.add(hits.getJSONObject(i));
        }
        return result;
    }

    public static JSONArray getProjectVersions(String projectId, String mcVersion, String rawVersion) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL + "/project/" + projectId + "/version");
        boolean first = true;
        if (mcVersion != null && !mcVersion.isEmpty()) {
            url.append("?game_versions=").append(URLEncoder.encode("[\"" + mcVersion + "\"]", "UTF-8"));
            first = false;
        }
        String loader = extractLoader(rawVersion);
        if (loader != null) {
            url.append(first ? "?" : "&");
            url.append("loaders=").append(URLEncoder.encode("[\"" + loader + "\"]", "UTF-8"));
        }
        return getArray(url.toString());
    }

    public static JSONObject getVersion(String versionId) throws Exception {
        return get(BASE_URL + "/version/" + versionId);
    }

    private static String extractLoader(String rawVersion) {
        if (rawVersion == null || rawVersion.isEmpty()) return null;
        String lower = rawVersion.toLowerCase();
        if (lower.startsWith("fabric")) return "fabric";
        if (lower.startsWith("neoforge")) return "neoforge";
        if (lower.startsWith("forge")) return "forge";
        if (lower.startsWith("quilt")) return "quilt";
        return null;
    }

    private static JSONObject get(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
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

    private static JSONArray getArray(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
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
        return new JSONArray(json);
    }
}
