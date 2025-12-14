
import axios from "axios";
import * as cheerio from "cheerio";
import { promises as fs } from 'fs';
import path from 'path';
import { wrapper } from "axios-cookiejar-support";
import { CookieJar } from "tough-cookie";
import { chromium } from "playwright";
/**
 * 获取 Bing 搜索结果
 */
async function srchBin(kwd: string): Promise<string | null> {
    const BING_SEARCH_URL =
        "https://www.bing.com/search?q=" + encodeURIComponent(kwd);

    //   const res = await axios.get<string>(BING_SEARCH_URL, {
    //   headers: {
    //     "User-Agent":
    //       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120",
    //     "Accept":
    //       "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    //     "Accept-Language": "zh-CN,zh;q=0.9",
    //     "Referer": "https://www.bing.com/",
    //     "Connection": "keep-alive",
    //   },
    //   decompress: true,
    // });



    const browser = await chromium.launch({ headless: false });
    const page = await browser.newPage();

    await page.goto(
        "https://www.bing.com/search?q=" + encodeURIComponent(kwd),
        { waitUntil: "networkidle" }
    );

    const html = await page.content();


    await fs.writeFile("srch.htm", html, "utf-8");




    return html;
}

async function writeFile(file: string, data: string): Promise<void> {
    await fs.mkdir(path.dirname(file), { recursive: true });
    await fs.writeFile(file, data, 'utf-8');
}


(async () => {
    const result = await srchBin("台积电");
    console.log("目标页面：", result.url);
    console.log("提取到的 emails：", result.emails);
})();