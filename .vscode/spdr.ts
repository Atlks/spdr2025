import axios from "axios";
import * as cheerio from "cheerio";

const BING_SEARCH_URL =
  "https://www.bing.com/search?q=%E5%A4%A7%E6%B0%B4%E5%85%AC%E5%8F%B8";

/**
 * 从网页文本中提取 email
 */
function extractEmails(html: string): string[] {
  const emailRegex =
    /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g;

  const matches = html.match(emailRegex);
  return matches ? Array.from(new Set(matches)) : [];
}

/**
 * 获取 Bing 第一个搜索结果链接
 */
async function getFirstBingResult(): Promise<string | null> {
  const res = await axios.get(BING_SEARCH_URL, {
    headers: {
      // 模拟浏览器，避免被简单拦截
      "User-Agent":
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120",
    },
  });

  const $ = cheerio.load(res.data);

  /**
   * Bing 搜索结果一般在：
   * li.b_algo h2 a
   */
  const firstLink = $("li.b_algo h2 a").first().attr("href");

  return firstLink ?? null;
}

/**
 * 主函数：搜索 → 打开链接 → 提取 email
 */
export async function fetchFirstResultEmails(): Promise<{
  url: string;
  emails: string[];
}> {
  const firstUrl = await getFirstBingResult();

  if (!firstUrl) {
    throw new Error("未找到搜索结果");
  }

  const pageRes = await axios.get(firstUrl, {
    timeout: 15000,
    headers: {
      "User-Agent":
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120",
    },
  });

  const emails = extractEmails(pageRes.data);

  return {
    url: firstUrl,
    emails,
  };
}



(async () => {
  const result = await fetchFirstResultEmails();
  console.log("目标页面：", result.url);
  console.log("提取到的 emails：", result.emails);
})();
