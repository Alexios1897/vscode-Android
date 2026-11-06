// 生成与「本机服务端构建」对齐的中文 NLS 消息表
// 数据源：服务端包里的 nls.keys.json + nls.messages.json（英文兜底）+ 中文语言包 main.i18n.json
const fs = require("fs");
const path = require("path");

const SERVER = process.argv[2];                       // 服务端解压目录
const PACK = process.argv[3];                         // 语言包目录
const OUT = process.argv[4] || "app/assets/nls.zh-cn.js";

const keys = JSON.parse(fs.readFileSync(path.join(SERVER, "out/nls.keys.json"), "utf8"));
const msgJs = fs.readFileSync(path.join(SERVER, "out/nls.messages.js"), "utf8");
const en = JSON.parse(msgJs.slice(msgJs.indexOf("["), msgJs.lastIndexOf("]") + 1));
const pack = JSON.parse(fs.readFileSync(path.join(PACK, "translations/main.i18n.json"), "utf8")).contents || {};

const out = [];
let hit = 0, total = 0;
for (const [mod, list] of keys) {
  const m = pack[mod] || {};
  for (const k of list) {
    total++;
    const t = m[k];
    if (typeof t === "string" && t.length) { out.push(t); hit++; }
    else out.push(en[out.length] !== undefined ? en[out.length] : k);
  }
}
if (out.length !== en.length) {
  console.error(`长度不一致: keys=${out.length} en=${en.length}`);
  process.exit(1);
}
const body = "/* generated for local build */\nglobalThis._VSCODE_NLS_MESSAGES=" + JSON.stringify(out) + ";\n";
fs.writeFileSync(OUT, body);
console.log(`生成 ${OUT}: ${out.length} 条，命中翻译 ${hit} 条 (${(hit / total * 100).toFixed(1)}%)，${(body.length / 1024).toFixed(0)}KB`);
