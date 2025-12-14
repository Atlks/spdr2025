import { getAllJSDocTags } from "typescript";
import { promises as fs } from "fs";
import * as cheerio from "cheerio";

/** a 标签结构 */
interface ATag {
  url: string;
  text: string;
}
 

(async () => {
    const resultHtml = await readFilex("srch.htm");

   // 写个类似于 list<aTag> 

   const list: ATag[] = parseToList(resultHtml);

  for (const a of list) {
    console.log("目标页面：", a);
  }
    
  //  console.log("提取到的 emails：", resultHtml.emails);
})();

/** 读取文件 */
async function readFilex(f: string): Promise<string> {
  return fs.readFile(f, "utf-8");
}

/** 解析 HTML，提取搜索结果链接 */
function parseToList(html: string): ATag[] {
  const $ = cheerio.load(html);
  const result: ATag[] = [];

  // Bing 搜索结果通常在 li.b_algo 里
 // $("li.b_algo h2 a").each((_, el) => {
     $("li  a").each((_, el) => {
    const url = $(el).attr("href");
    const text = $(el).text().trim();

    if (url) {
      result.push({ url, text });
    }
  });

  return result;
}