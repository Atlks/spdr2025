import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

public class SearchBing {

    private static final int MAX_RETRIES = 1;
    private static final double MIN_DELAY = 0.8;
    private static final double MAX_DELAY = 1.8;

    public static List<Map<String, String>> searchBing(String companyName) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {

            String cleanName = companyName.replace(" ", "").replace("\u200b", "");
            String url = "https://www.bing.com/search?q=" + cleanName;

            log("[搜索] " + companyName + " - 第" + attempt + "次 - URL: " + url);

            String html = fetch(url);
            if (html == null || html.isEmpty()) {
                sleepRandom();
                continue;
            }
            CompanyLoader.writeFil(html,companyName+".srch.htm");

            log("[搜索] 响应长度: " + html.length());

            List<Map<String, String>> results = parseResults(html);
            if (!results.isEmpty()) {
                return results;
            }

            // 检查是否被限流
            if (!html.contains("b_algo")) {
                log("[搜索] ⚠ 页面无搜索结果，可能被限流");
            } else {
                log("[搜索] 有结果但未匹配");
            }

            sleepRandom();
        }

        return Collections.emptyList();
    }

    /** 解析 Bing 搜索结果 */
    private static List<Map<String, String>> parseResults(String html) {
        List<Map<String, String>> results = new ArrayList<>();

        Document doc = Jsoup.parse(html);
        Elements items = doc.select("li.b_algo");

        for (Element item : items) {
            Element link = item.selectFirst("h2 a");
            if (link == null) continue;

            String href = link.attr("href");

            // 处理 Bing 跳转链接
            if (href.contains("/ck/a?")) {
                href = decodeBingRedirect(href);
            }

            String title = link.text().trim();

            if (href != null && isValidUrl(href)) {
                Map<String, String> entry = new HashMap<>();
                entry.put("title", title);
                entry.put("url", href);
                results.add(entry);

                log("[搜索] 发现: " + title + " -> " + href);
            }
        }

        return results;
    }

    /** 解析 Bing 跳转链接，提取真实 URL */
    private static String decodeBingRedirect(String href) {
        try {
            java.net.URI uri = new java.net.URI(href);
            String query = uri.getRawQuery();
            if (query == null) return href;

            Map<String, List<String>> params = splitQuery(query);
            if (!params.containsKey("u")) return href;

            String encoded = params.get("u").get(0);

            if (encoded.startsWith("a1")) {
                encoded = encoded.substring(2);
            }

            byte[] decoded = Base64.getDecoder().decode(encoded + "==");
            return new String(decoded, StandardCharsets.UTF_8);

        } catch (Exception e) {
            return href;
        }
    }

    /** 解析 URL 查询参数 */
    private static Map<String, List<String>> splitQuery(String query) {
        Map<String, List<String>> queryPairs = new LinkedHashMap<>();
        String[] pairs = query.split("&");

        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);

            queryPairs.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return queryPairs;
    }

    /** 简单 URL 校验 */
    private static boolean isValidUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /** 发起 HTTP 请求 */
    private static String fetch(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(8000)
                    .ignoreContentType(true)
                    .get()
                    .html();
        } catch (Exception e) {
            return null;
        }
    }

    /** 随机延迟 */
    private static void sleepRandom() {
        try {
            double delay = MIN_DELAY + Math.random() * (MAX_DELAY - MIN_DELAY);
            Thread.sleep((long) (delay * 1000));
        } catch (InterruptedException ignored) {}
    }

    private static void log(String msg) {
        System.out.println(msg);
    }
}
