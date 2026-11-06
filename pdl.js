#!/usr/bin/env node
// 并行分片下载器（支持断点续传）：绕开微软 CDN 的单连接限速
// 用法: node pdl.js <url> <输出文件> [连接数]
const fs = require("fs");
const path = require("path");

const [url, out, connArg] = process.argv.slice(2);
if (!url || !out) {
  console.error("用法: node pdl.js <url> <输出文件> [连接数]");
  process.exit(1);
}
const CONN = parseInt(connArg || "16", 10);
const CHUNK = 2 * 1024 * 1024;

async function headSize(u) {
  for (let i = 0; i < 5; i++) {
    try {
      const r = await fetch(u, { headers: { Range: "bytes=0-0" }, signal: AbortSignal.timeout(20000) });
      const cr = r.headers.get("content-range");
      if (cr && cr.includes("/")) return parseInt(cr.split("/")[1], 10);
      const cl = r.headers.get("content-length");
      if (cl) return parseInt(cl, 10);
    } catch (e) {
      await new Promise((r) => setTimeout(r, 1500));
    }
  }
  throw new Error("拿不到文件大小");
}

async function fetchRange(u, start, end, dest, tries = 8) {
  const want = end - start + 1;
  for (let t = 1; t <= tries; t++) {
    try {
      const r = await fetch(u, {
        headers: { Range: `bytes=${start}-${end}` },
        signal: AbortSignal.timeout(120000),
      });
      if (r.status !== 206 && r.status !== 200) throw new Error("HTTP " + r.status);
      const buf = Buffer.from(await r.arrayBuffer());
      if (buf.length !== want) throw new Error(`长度不符 ${buf.length}/${want}`);
      fs.writeFileSync(dest + ".tmp", buf);
      fs.renameSync(dest + ".tmp", dest);
      return;
    } catch (e) {
      if (t === tries) throw e;
      await new Promise((res) => setTimeout(res, 1000 * t));
    }
  }
}

(async () => {
  const total = await headSize(url);
  const tmp = out + ".parts";
  fs.mkdirSync(tmp, { recursive: true });

  const ranges = [];
  for (let s = 0; s < total; s += CHUNK)
    ranges.push([s, Math.min(s + CHUNK - 1, total - 1)]);

  const partPath = (i) => path.join(tmp, String(i).padStart(6, "0"));
  const pending = ranges
    .map((r, i) => [r, i])
    .filter(([r, i]) => {
      try {
        return fs.statSync(partPath(i)).size !== r[1] - r[0] + 1;
      } catch {
        return true;
      }
    });

  console.log(`总 ${(total / 1048576).toFixed(1)} MB，${ranges.length} 片，待下 ${pending.length} 片，${CONN} 并发`);

  const t0 = Date.now();
  const timer = setInterval(() => {
    let got = 0;
    for (const f of fs.readdirSync(tmp)) {
      try { got += fs.statSync(path.join(tmp, f)).size; } catch {}
    }
    const sp = got / 1024 / ((Date.now() - t0) / 1000);
    process.stdout.write(`\r  ${(got / 1048576).toFixed(1)}/${(total / 1048576).toFixed(1)} MB  ${sp.toFixed(0)} KB/s   `);
  }, 3000);

  let idx = 0;
  await Promise.all(
    [...Array(CONN)].map(async () => {
      while (idx < pending.length) {
        const [r, i] = pending[idx++];
        await fetchRange(url, r[0], r[1], partPath(i));
      }
    })
  );
  clearInterval(timer);

  const fd = fs.openSync(out, "w");
  for (let i = 0; i < ranges.length; i++) fs.writeSync(fd, fs.readFileSync(partPath(i)));
  fs.closeSync(fd);
  fs.rmSync(tmp, { recursive: true, force: true });

  const size = fs.statSync(out).size;
  console.log(`\n完成: ${out} (${(size / 1048576).toFixed(1)} MB) 用时 ${((Date.now() - t0) / 1000).toFixed(0)}s`);
  if (size !== total) {
    console.error("大小校验失败");
    process.exit(1);
  }
})().catch((e) => {
  console.error("\n失败:", e.message);
  process.exit(1);
});
