import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.util.IOUtils;
import okhttp3.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 谷歌搜索爬虫 - 代理池版本
 */
public class GoogleSearchCrawler {

    static {
        IOUtils.setByteArrayMaxOverride(500_000_000);
    }

    // ============ 配置区 ============
    private static final int CONCURRENCY = 3;              // 并发数（测试用3个）
    private static final int MIN_DELAY_MS = 2000;          // 最小延迟（每个IP间隔2秒）
    private static final int MAX_DELAY_MS = 4000;          // 最大延迟
    private static final int MAX_RETRIES = 2;              // 最大重试次数（减少重试，节省IP）
    private static final int TIMEOUT_MS = 15000;           // 请求超时
    private static final String INPUT_FILE = "src/faren.xlsx";
    private static final int COMPANY_COLUMN = 1;           // B列=公司名
    private static final int LEGAL_COLUMN = 2;             // C列=法人
    private static final int EMAIL_COLUMN = 4;             // E列=邮箱
    private static final int WEBSITE_COLUMN = 5;           // F列=官网
    private static final int REMARK_COLUMN = 6;            // G列=备注
    private static final String OUTPUT_CSV = "results.csv";
    private static final String PROGRESS_FILE = "progress.txt";
    
    // Cliproxy代理API配置（台湾住宅IP，测试用3个）
    private static final String PROXY_API_URL = "https://ipapi.cliproxy.com/start?key=u6j7vdprilup4u8ssokb&port=443&num=3&country=TW&state=&type=2";
    private static final int PROXY_POOL_MIN_SIZE = 1;      // 有代理就启动
    private static final int PROXY_LIFETIME_MS = 86400000; // 不自动过期（24小时）

    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605.1.15"
    };

    private static final String GOOGLE_DOMAIN = "www.google.com";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Random random = new Random();
    
    // 代理池：轮换使用
    private static final List<String> proxyList = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger proxyIndex = new AtomicInteger(0);
    private static final Map<String, AtomicInteger> proxyUsageCount = new ConcurrentHashMap<>();  // 每个IP使用次数
    private static final Set<String> failedProxies = ConcurrentHashMap.newKeySet();  // 失败的代理
    
    private static final Set<String> completedCompanies = ConcurrentHashMap.newKeySet();
    private static final List<CompanyInfo> resultList = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, String> companyLegalMap = new ConcurrentHashMap<>();
    private static final Map<String, Integer> companyRowMap = new ConcurrentHashMap<>();
    
    private static final AtomicInteger totalProcessed = new AtomicInteger(0);
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failCount = new AtomicInteger(0);
    private static int totalCompanies = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("  谷歌搜索爬虫 - 代理池版本");
        System.out.println("  并发数: " + CONCURRENCY);
        System.out.println("========================================\n");

        fetchAllProxies();       // 一次性获取所有代理
        waitForProxyPool();      // 等待代理池就绪
        loadProgress();
        initCsvFile();
        
        List<String> companies = loadCompanies();
        totalCompanies = companies.size();
        System.out.println("待处理公司数: " + totalCompanies);

        startProgressMonitor();

        long startTime = System.currentTimeMillis();
        
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore semaphore = new Semaphore(CONCURRENCY);

            for (String company : companies) {
                semaphore.acquire();
                executor.submit(() -> {
                    try {
                        processCompany(company);
                    } finally {
                        semaphore.release();
                    }
                });
            }
            
            // 等待所有任务完成
            semaphore.acquire(CONCURRENCY);
        }

        updateSourceExcel();

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("\n========================================");
        System.out.println("  爬取完成！总耗时: " + elapsed + " 秒");
        System.out.println("  成功: " + successCount.get() + " | 失败: " + failCount.get());
        System.out.println("========================================");
    }

    // ============ 代理池管理 ============

    /**
     * 启动时一次性获取所有代理
     */
    private static void fetchAllProxies() {
        log("正在从Cliproxy获取代理...");
        try {
            Document doc = Jsoup.connect(PROXY_API_URL)
                    .ignoreContentType(true)
                    .timeout(30000)
                    .get();
            
            String response = doc.body().text().trim();
            log("[代理API] 响应: " + response.substring(0, Math.min(500, response.length())));
            
            // 尝试解析JSON格式 {"code":0,"data":["ip:port","ip:port"]}
            if (response.contains("\"data\"")) {
                int start = response.indexOf("[");
                int end = response.lastIndexOf("]");
                if (start > 0 && end > start) {
                    String arr = response.substring(start + 1, end);
                    for (String item : arr.split(",")) {
                        String proxy = item.replaceAll("[\"\\s]", "").trim();
                        if (proxy.contains(":")) {
                            proxyList.add(proxy);
                            proxyUsageCount.put(proxy, new AtomicInteger(0));
                            log("✅ 代理: " + proxy);
                        }
                    }
                }
            } else {
                // 纯文本格式：IP:端口:用户名:密码（空格或换行分隔）
                for (String line : response.split("[\\s\\n\\r,]+")) {
                    String proxy = line.trim();
                    if (proxy.contains(":") && !proxy.contains("{") && proxy.split(":").length >= 2) {
                        proxyList.add(proxy);
                        proxyUsageCount.put(proxy, new AtomicInteger(0));
                        // 只显示IP:端口，隐藏用户名密码
                        String[] parts = proxy.split(":");
                        log("✅ 代理: " + parts[0] + ":" + parts[1] + (parts.length >= 4 ? " (带认证)" : ""));
                    }
                }
            }
            
            log("✅ 共获取 " + proxyList.size() + " 个代理");
        } catch (Exception e) {
            log("❌ 获取代理失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 轮换获取代理（均匀分配）
     */
    private static String getProxy() {
        if (proxyList.isEmpty()) return null;
        
        // 过滤掉失败的代理
        List<String> available = proxyList.stream()
            .filter(p -> !failedProxies.contains(p))
            .toList();
        
        if (available.isEmpty()) {
            log("⚠️ 所有代理都已失效！");
            return null;
        }
        
        // 轮换选择
        int idx = proxyIndex.getAndIncrement() % available.size();
        String proxy = available.get(idx);
        proxyUsageCount.get(proxy).incrementAndGet();
        return proxy;
    }
    
    /**
     * 标记代理失败
     */
    private static void markProxyFailed(String proxy) {
        if (proxy != null) {
            failedProxies.add(proxy);
            log("❌ 代理失效: " + proxy + " (剩余: " + (proxyList.size() - failedProxies.size()) + ")");
        }
    }

    /**
     * 等待代理池就绪
     */
    private static void waitForProxyPool() throws InterruptedException {
        while (proxyList.size() < PROXY_POOL_MIN_SIZE) {
            log("⏳ 等待代理池... 当前: " + proxyList.size() + "/" + PROXY_POOL_MIN_SIZE);
            Thread.sleep(5000);
        }
        log("✅ 代理池就绪: " + proxyList.size() + " 个");
    }

    // ============ 爬虫逻辑 ============

    private static void processCompany(String company) {
        if (company == null || company.isBlank() || completedCompanies.contains(company)) return;

        log("处理: " + company);

        CompanyInfo info = new CompanyInfo();
        info.companyName = company;
        info.legalPerson = companyLegalMap.getOrDefault(company, "");

        try {
            List<SearchResult> results = searchGoogle(company);
            log("[" + company + "] 搜索到 " + results.size() + " 条结果");
            
            // 打印前3条结果用于调试
            for (int i = 0; i < Math.min(3, results.size()); i++) {
                log("[" + company + "] 结果" + (i+1) + ": " + results.get(i).title);
            }
            
            SearchResult match = findExactMatch(results, company);

            if (match != null) {
                log("[" + company + "] ✓ 匹配: " + match.url);
                info.website = match.url;
                extractEmails(match.url, info);
                successCount.incrementAndGet();
            } else {
                log("[" + company + "] ✗ 未匹配");
                log("[" + company + "] 公司名标准化: [" + normalize(company) + "]");
                // 打印所有结果的标准化标题用于调试
                for (int i = 0; i < Math.min(5, results.size()); i++) {
                    log("[" + company + "] 结果" + (i+1) + "标准化: [" + normalize(results.get(i).title) + "]");
                }
                info.website = "未找到匹配";
                failCount.incrementAndGet();
            }
        } catch (Exception e) {
            log("[" + company + "] ✗ 异常: " + e.getMessage());
            failCount.incrementAndGet();
        }

        // 保存前打印完整数据
        log("========== 保存数据 ==========");
        log("公司名称: " + info.companyName);
        log("法人: " + info.legalPerson);
        log("官网: " + info.website);
        log("邮箱: " + (info.emails.isEmpty() ? "无" : String.join("; ", info.emails)));
        log("备注: " + (info.website != null && !info.website.equals("未找到匹配") ? "已找到" : "未找到官网"));
        log("==============================");
        
        resultList.add(info);
        saveResultToCsv(info);
        saveProgress(company);
        totalProcessed.incrementAndGet();
        randomDelay();
    }

    private static List<SearchResult> searchGoogle(String companyName) {
        List<SearchResult> results = new ArrayList<>();
        String lastProxy = null;
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String url = "https://" + GOOGLE_DOMAIN + "/search?q=" + 
                    URLEncoder.encode(companyName, StandardCharsets.UTF_8) + 
                    "&num=20&hl=zh-TW&gl=TW&gws_rd=cr";

                log("[搜索] 第" + attempt + "次尝试: " + url);
                String proxy = getProxy();
                lastProxy = proxy;
                String[] proxyParts = proxy != null ? proxy.split(":") : null;
                log("[搜索] 使用代理: " + (proxyParts != null ? proxyParts[0] + ":" + proxyParts[1] : "无代理"));
                
                String html = fetchWithProxy(url, proxy);
                if (html == null) {
                    log("[搜索] 请求失败，重试...");
                    randomDelay();
                    continue;
                }
                
                Document doc = Jsoup.parse(html);
                String text = doc.text();
                
                log("[搜索] 响应长度: " + text.length() + " 字符");
                log("[搜索] 响应前200字: " + text.substring(0, Math.min(200, text.length())));
                
                if (text.contains("unusual traffic") || text.contains("captcha")) {
                    log("[" + companyName + "] ⚠️ 验证码，标记代理失效");
                    markProxyFailed(proxy);
                    randomDelay();
                    continue;
                }

                // 打印HTML结构用于调试
                var searchResults = doc.select("div.g, div.Gx5Zad");
                log("[搜索] 找到 div.g/Gx5Zad 元素: " + searchResults.size() + " 个");
                
                searchResults.forEach(el -> {
                    var link = el.selectFirst("a[href^=http]");
                    var title = el.selectFirst("h3");
                    if (link != null) {
                        String href = link.attr("href");
                        String titleText = title != null ? title.text() : "(无标题)";
                        log("[搜索] 发现链接: " + titleText + " -> " + href);
                        if (isValidUrl(href)) {
                            results.add(new SearchResult(titleText, href));
                        } else {
                            log("[搜索] 跳过无效URL: " + href);
                        }
                    }
                });

                log("[搜索] 有效结果数: " + results.size());
                if (!results.isEmpty()) return results;
                
                // 如果没找到结果，打印整个HTML用于调试
                log("[搜索] ⚠️ 未找到结果，打印HTML片段:");
                log(html.substring(0, Math.min(2000, html.length())));
                
            } catch (Exception e) {
                log("[搜索] 第" + attempt + "次异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                if (attempt == MAX_RETRIES) break;
            }
            randomDelay();
        }
        log("[搜索] 所有重试失败，返回空结果");
        return results;
    }

    private static SearchResult findExactMatch(List<SearchResult> results, String companyName) {
        String clean = normalize(companyName);
        
        // 第一轮：精确匹配或包含
        for (SearchResult r : results) {
            String t = normalize(r.title);
            if (t.equals(clean) || t.contains(clean) || clean.contains(t)) {
                return r;
            }
        }
        
        // 第二轮：去掉"有限公司"/"股份有限公司"后匹配
        String shortName = clean.replaceAll("股份有限公司|有限公司|公司", "");
        if (shortName.length() >= 2) {
            for (SearchResult r : results) {
                String t = normalize(r.title).replaceAll("股份有限公司|有限公司|公司", "");
                if (t.contains(shortName) || shortName.contains(t)) {
                    log("[匹配] 短名匹配: " + shortName + " <-> " + t);
                    return r;
                }
            }
        }
        
        return null;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s　\\-–—·•.,，。、|\\|]", "")
                .replaceAll("官网|官方网站|首页|首頁|官方網站|公司簡介|關於我們|关于我们|公司简介|Home|About", "")
                .toLowerCase()
                .trim();
    }

    private static void extractEmails(String url, CompanyInfo info) {
        log("[邮箱提取] 开始访问: " + url);
        try {
            String html = fetchWithProxy(url, getProxy());
            if (html == null) return;
            
            Document doc = Jsoup.parse(html);
            String pageText = doc.text();
            log("[邮箱提取] 页面长度: " + pageText.length() + " 字符");
            
            Matcher m = EMAIL_PATTERN.matcher(pageText);
            int count = 0;
            while (m.find()) {
                String email = m.group();
                if (!email.endsWith(".png") && !email.endsWith(".jpg")) {
                    info.emails.add(email);
                    count++;
                    log("[邮箱提取] 找到: " + email);
                }
            }
            log("[邮箱提取] 共找到 " + count + " 个邮箱");
        } catch (Exception e) {
            log("[邮箱提取] 失败: " + e.getMessage());
        }
    }

    /**
     * 使用OkHttp发送请求（支持认证代理）
     */
    private static String fetchWithProxy(String url, String proxyStr) {
        try {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .followRedirects(true);

            if (proxyStr != null) {
                String[] parts = proxyStr.split(":");
                if (parts.length >= 2) {
                    String host = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
                    builder.proxy(proxy);
                    
                    // 如果有用户名密码
                    if (parts.length >= 4) {
                        String username = parts[2];
                        String password = parts[3];
                        builder.proxyAuthenticator((route, response) -> {
                            String credential = Credentials.basic(username, password);
                            return response.request().newBuilder()
                                    .header("Proxy-Authorization", credential)
                                    .build();
                        });
                    }
                }
            }

            OkHttpClient client = builder.build();
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENTS[random.nextInt(USER_AGENTS.length)])
                    .header("Accept-Language", "zh-TW,zh;q=0.9")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.body() != null) {
                    return response.body().string();
                }
            }
        } catch (Exception e) {
            log("[HTTP] 请求失败: " + e.getMessage());
        }
        return null;
    }

    private static boolean isValidUrl(String url) {
        return url.startsWith("http") && !url.contains("google.com") && 
               !url.contains("youtube.com") && !url.contains("webcache");
    }

    private static void randomDelay() {
        try { Thread.sleep(MIN_DELAY_MS + random.nextInt(MAX_DELAY_MS - MIN_DELAY_MS)); } 
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void log(String msg) {
        System.out.println("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
    }

    // ============ 文件操作 ============

    private static List<String> loadCompanies() throws IOException {
        List<String> companies = new ArrayList<>();
        try (InputStream is = Files.newInputStream(Paths.get(INPUT_FILE));
             Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                String name = getCellValue(row.getCell(COMPANY_COLUMN));
                String legal = getCellValue(row.getCell(LEGAL_COLUMN));
                if (name != null && !name.isBlank() && !completedCompanies.contains(name.trim())) {
                    name = name.trim();
                    companies.add(name);
                    companyLegalMap.put(name, legal != null ? legal.trim() : "");
                    companyRowMap.put(name, row.getRowNum());
                }
            }
        }
        return companies;
    }

    private static String getCellValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> null;
        };
    }

    private static void loadProgress() {
        try {
            Path p = Paths.get(PROGRESS_FILE);
            if (Files.exists(p)) {
                completedCompanies.addAll(Files.readAllLines(p));
                log("断点续爬: 已完成 " + completedCompanies.size() + " 条");
            }
        } catch (Exception e) { }
    }

    private static final Object progressLock = new Object();
    private static void saveProgress(String company) {
        synchronized (progressLock) {
            try {
                Files.writeString(Paths.get(PROGRESS_FILE), company + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                completedCompanies.add(company);
            } catch (Exception e) { }
        }
    }

    private static void initCsvFile() {
        try {
            Path p = Paths.get(OUTPUT_CSV);
            if (!Files.exists(p)) {
                Files.writeString(p, "\uFEFF公司名称,邮箱,法人,官网,备注\n", StandardCharsets.UTF_8);
            }
        } catch (Exception e) { }
    }

    private static final Object csvLock = new Object();
    private static void saveResultToCsv(CompanyInfo info) {
        synchronized (csvLock) {
            try {
                String remark = info.website != null && !info.website.equals("未找到匹配") ? "已找到" : "未找到官网";
                String emails = String.join("; ", info.emails);
                String line = String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                    esc(info.companyName), esc(emails),
                    esc(info.legalPerson), esc(info.website), remark);
                
                log("[CSV保存] 写入: " + info.companyName + " | 邮箱: " + (emails.isEmpty() ? "无" : emails) + " | 官网: " + info.website);
                
                Files.writeString(Paths.get(OUTPUT_CSV), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log("[CSV保存] ✓ 成功");
            } catch (Exception e) {
                log("[CSV保存] ✗ 失败: " + e.getMessage());
            }
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "\"\"").replace("\n", " ");
    }

    private static void updateSourceExcel() {
        try (InputStream is = Files.newInputStream(Paths.get(INPUT_FILE));
             Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header.getCell(EMAIL_COLUMN) == null) header.createCell(EMAIL_COLUMN).setCellValue("邮箱");
            if (header.getCell(WEBSITE_COLUMN) == null) header.createCell(WEBSITE_COLUMN).setCellValue("官网");
            if (header.getCell(REMARK_COLUMN) == null) header.createCell(REMARK_COLUMN).setCellValue("备注");

            for (CompanyInfo info : resultList) {
                Integer rowNum = companyRowMap.get(info.companyName);
                if (rowNum == null) continue;
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;
                
                boolean found = info.website != null && !info.website.equals("未找到匹配");
                getCell(row, EMAIL_COLUMN).setCellValue(String.join("; ", info.emails));
                getCell(row, WEBSITE_COLUMN).setCellValue(found ? info.website : "");
                getCell(row, REMARK_COLUMN).setCellValue(found ? "已找到" : "未找到官网");
            }

            try (FileOutputStream fos = new FileOutputStream(INPUT_FILE)) {
                wb.write(fos);
            }
            log("已更新: " + INPUT_FILE);
        } catch (Exception e) {
            System.err.println("更新Excel失败: " + e.getMessage());
        }
    }

    private static Cell getCell(Row row, int col) {
        Cell c = row.getCell(col);
        return c != null ? c : row.createCell(col);
    }

    private static void startProgressMonitor() {
        Thread.startVirtualThread(() -> {
            while (totalProcessed.get() < totalCompanies) {
                try {
                    Thread.sleep(10000);
                    int p = totalProcessed.get();
                    int availableProxies = proxyList.size() - failedProxies.size();
                    System.out.printf("[进度] %d/%d (%.1f%%) | 成功: %d | 失败: %d | 可用代理: %d/%d%n",
                        p, totalCompanies, p * 100.0 / totalCompanies, 
                        successCount.get(), failCount.get(), availableProxies, proxyList.size());
                } catch (InterruptedException e) { break; }
            }
        });
    }

    static class SearchResult {
        String title, url;
        SearchResult(String t, String u) { title = t; url = u; }
    }

    static class CompanyInfo {
        String companyName, legalPerson, website;
        Set<String> emails = new HashSet<>();
    }
}
