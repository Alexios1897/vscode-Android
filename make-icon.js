// 用用户给的 VS Code 标志生成各密度启动图标（圆角白底）
const sharp = require("sharp");
const fs = require("fs");
const path = require("path");

const SRC = "icon-src.webp";
const SIZES = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };

(async () => {
  const meta = await sharp(SRC).metadata();
  console.log("源图", meta.width + "x" + meta.height, meta.format);
  for (const [d, size] of Object.entries(SIZES)) {
    const r = Math.round(size * 0.2);
    const base = await sharp(SRC).resize(size, size, { fit: "cover" }).toBuffer();
    const mask = Buffer.from(
      `<svg width="${size}" height="${size}"><rect width="${size}" height="${size}" rx="${r}" ry="${r}" fill="#fff"/></svg>`
    );
    const out = await sharp(base)
      .composite([{ input: mask, blend: "dest-in" }])
      .png()
      .toBuffer();
    const dir = path.join("app/res", "mipmap-" + d);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, "ic_launcher.png"), out);
    console.log(d, size + "px", out.length + "B");
  }
})().catch((e) => { console.error("失败", e); process.exit(1); });
