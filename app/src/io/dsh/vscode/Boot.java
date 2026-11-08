package io.dsh.vscode;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** 负责：解压资源、启动 proot、拉起 VS Code 服务端、写日志 */
public final class Boot {
    public static final String TAG = "VSCodeAPK";
    public static final int PORT = 18080;
    /**
     * 容器内可见的令牌文件路径：persist/cli 绑定到 /root/.vscode，
     * 因此宿主机上的 persist/cli/token 就是容器里的这个路径。
     */
    static final String TOKEN_IN_CONTAINER = "/root/.vscode/token";
    /** 每次改动解压/启动逻辑都要加一，用于判断是否需要重新解压（只改启动参数不用加） */
    private static final String BUILD = "2.5";
    /** 与 assets/vscode.tar.gz 对应的构建 commit（换包时要一起改） */
    static final String COMMIT = "6bab64e2d12b04d04c0d99c27ff166023efd1dda";

    private static Process proc;
    private static File base;
    private static File logFile;
    private static volatile String state = "idle";
    /** 本次运行使用的连接令牌，WebView 加载时作为 ?tkn= 参数传过去 */
    private static volatile String token = "";

    private static final String[] TAIL = new String[40];
    private static int tailPos = 0;

    private Boot() {}

    public static String state() {
        return state;
    }

    public static synchronized void start(final Context ctx) {
        if (proc != null) return;
        base = ctx.getFilesDir();
        logFile = pickLogFile(ctx);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    prepare(ctx);
                    state = "starting";
                    spawn();
                    state = "running";
                } catch (Throwable t) {
                    state = "failed";
                    log("FATAL: " + Log.getStackTraceString(t));
                }
            }
        }, "vscode-boot").start();
    }

    private static File pickLogFile(Context ctx) {
        File d = new File("/sdcard/Download");
        if (d.isDirectory() && d.canWrite()) return new File(d, "vscode-apk.log");
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) return new File(ext, "vscode-apk.log");
        return new File(ctx.getFilesDir(), "vscode-apk.log");
    }

    public static void log(String msg) {
        Log.i(TAG, msg);
        if (logFile == null) return;
        try {
            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable ignored) {}
    }

    private static void prepare(Context ctx) throws Exception {
        File rootfs = new File(base, "rootfs");
        File stamp = new File(base, "installed.stamp");

        // 用户数据与 CLI 缓存放在 rootfs 之外：升级/重解压都不会丢插件、设置和登录态
        File persist = new File(base, "persist");
        persist.mkdirs();
        File hostData = new File(persist, "data");
        File hostCli = new File(persist, "cli");
        if (!hostData.exists()) {
            File old = new File(rootfs, "root/data");
            if (old.isDirectory()) log("迁移旧数据: " + old.renameTo(hostData));
        }
        hostData.mkdirs();
        if (!hostCli.exists()) {
            File old = new File(rootfs, "root/.vscode");
            if (old.isDirectory()) log("迁移旧 CLI 缓存: " + old.renameTo(hostCli));
        }
        hostCli.mkdirs();
        token = loadOrCreateToken(hostCli);
        File hostCodex = new File(persist, "codex");
        hostCodex.mkdirs();
        new File(hostCli, "cli").mkdirs();
        pruneOtherServers(new File(hostCli, "cli/serve-web"));
        String want = BUILD + "-" + PORT + "-" + COMMIT;
        String have = stamp.exists() ? readText(stamp) : "";

        if (!want.equals(have)) {
            log("首次启动：解压 rootfs（约 105MB）…");
            deleteTree(rootfs);
            extractAsset(ctx, "rootfs.tar.gz", rootfs, new TarX(LOG));

            log("解压 VS Code 服务端（约 660MB，第一次要几分钟）…");
            File srvCache = new File(hostCli, "cli/serve-web/" + COMMIT);
            srvCache.getParentFile().mkdirs();
            extractAsset(ctx, "vscode.tar.gz", srvCache, new TarX(LOG, 1));

            // 官方 CLI：登录态靠它下发的 cookie（vscode-cli-secret-half / vscode-secret-key-path）才能持久化
            File cli = new File(rootfs, "opt/vscode/code");
            new File(rootfs, "opt/vscode").mkdirs();
            copyAsset(ctx, "code", cli);
            chmod(cli, 0755);

            File lib = new File(base, "lib");
            lib.mkdirs();
            copyAsset(ctx, "loader", new File(base, "loader"));
            chmod(new File(base, "loader"), 0755);
            copyAsset(ctx, "loader32", new File(base, "loader32"));
            chmod(new File(base, "loader32"), 0755);
            copyAsset(ctx, "libtalloc.so.2", new File(lib, "libtalloc.so.2"));
            copyAsset(ctx, "libandroid-shmem.so", new File(lib, "libandroid-shmem.so"));

            File pr = new File(base, "proot");
            copyAsset(ctx, "proot", pr);
            chmod(pr, 0755);
            diag(pr);

            diag(new File(rootfs, "usr/bin/env"));
            diag(new File(rootfs, "bin/bash"));
            diag(new File(hostCli, "cli/serve-web/" + COMMIT + "/bin/code-server"));

            new File(base, "tmp").mkdirs();
            new File(rootfs, "root/data").mkdirs();      // 绑定点占位
            new File(rootfs, "root/.vscode").mkdirs();   // 绑定点占位
            new File(rootfs, "root/.codex").mkdirs();    // 绑定点占位
            new File(rootfs, "dev/pts").mkdirs();        // pty 绑定点
            new File(rootfs, "dev/shm").mkdirs();
            new File(rootfs, "root/workspace").mkdirs();
            writeText(stamp, want);
            log("解压完成");
        }

        ensureCaCerts(ctx, rootfs);
    }

    /** 只保留打包对应的服务端版本，清掉自动升级留下的半成品 */
    private static void pruneOtherServers(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory() && !k.getName().equals(COMMIT)) {
                deleteTree(k);
                log("清理多余的服务端缓存: " + k.getName());
            }
        }
    }

    /** ubuntu-base 不带 CA 证书：没有它扩展主机的 HTTPS 全部验签失败（GitHub 登录、Codex 都会挂） */
    private static void ensureCaCerts(Context ctx, File rootfs) {
        try {
            File dir = new File(rootfs, "etc/ssl/certs");
            File bundle = new File(dir, "ca-certificates.crt");
            File legacy = new File(rootfs, "etc/ssl/cert.pem");
            if (!bundle.exists() || bundle.length() == 0) {
                dir.mkdirs();
                copyAsset(ctx, "ca-certificates.crt", bundle);
                log("已补 CA 证书: " + bundle.length() + " bytes");
            }
            if (!legacy.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(legacy.toPath(),
                            java.nio.file.Paths.get("certs/ca-certificates.crt"));
                } catch (Throwable t) {
                    copyAsset(ctx, "ca-certificates.crt", legacy);
                }
            }
        } catch (Throwable t) {
            log("补 CA 证书失败: " + t);
        }
    }

    private static void spawn() throws Exception {
        File rootfs = new File(base, "rootfs");
        File proot = new File(base, "proot");
        String[] cmd = new String[] {
                proot.getAbsolutePath(),
                "--link2symlink", "--kill-on-exit", "-0",
                "-r", rootfs.getAbsolutePath(),
                "-w", "/root",
                "-b", "/dev", "-b", "/dev/pts", "-b", "/dev/shm",
                "-b", "/dev/urandom:/dev/random",
                "-b", "/proc", "-b", "/proc/self/fd:/dev/fd", "-b", "/sys",
                "-b", "/sdcard:/sdcard",
                "-b", new File(base, "persist/data").getAbsolutePath() + ":/root/data",
                "-b", new File(base, "persist/cli").getAbsolutePath() + ":/root/.vscode",
                "-b", new File(base, "persist/codex").getAbsolutePath() + ":/root/.codex",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8", "TERM=xterm",
                "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
                "NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt",
                "/opt/vscode/code", "serve-web",
                "--host", "127.0.0.1", "--port", String.valueOf(PORT),
                // 不再关掉令牌：没有它，同机任何 App 都能直接接管这个端口
                "--connection-token-file", TOKEN_IN_CONTAINER,
                "--accept-server-license-terms",
                "--disable-telemetry",
                "--commit-id", COMMIT,
                "--server-data-dir", "/root/data",
                "--default-folder", "/root/workspace"
        };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("PROOT_LOADER", new File(base, "loader").getAbsolutePath());
        pb.environment().put("PROOT_TMP_DIR", new File(base, "tmp").getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", new File(base, "lib").getAbsolutePath());

        log("启动: " + pb.command().toString());
        final Process p = pb.start();
        proc = p;

        final BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        Thread pump = new Thread(new Runnable() {
            @Override public void run() {
                String line;
                try {
                    while ((line = r.readLine()) != null) {
                        line = redact(line);
                        lastLines(line);
                        log("[srv] " + line);
                    }
                } catch (IOException ignored) {}
                try { int code = p.waitFor(); log("服务端进程退出 code=" + code); } catch (Exception ignored) {}
            }
        });
        pump.setDaemon(true);
        pump.start();
    }

    private static synchronized void lastLines(String line) {
        TAIL[tailPos++ % TAIL.length] = line;
    }

    public static synchronized String tail() {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(tailPos, TAIL.length);
        for (int i = 0; i < n; i++) {
            String s = TAIL[(tailPos - n + i) % TAIL.length];
            if (s != null) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    /** WebView 要加载的地址：必须带 tkn 参数，否则 serve-web 会拒绝 */
    public static String webUrl() {
        StringBuilder sb = new StringBuilder("http://127.0.0.1:").append(PORT)
                .append("/?folder=/root/workspace");
        String t = token;
        if (t != null && !t.isEmpty()) sb.append("&tkn=").append(t);
        return sb.toString();
    }

    /** 停止服务端进程（通知栏「停止」按钮用）；proot 的 --kill-on-exit 会顺带清掉子进程 */
    public static synchronized void stop() {
        Process p = proc;
        if (p == null) return;
        proc = null;
        state = "stopped";
        log("收到停止请求，结束服务端进程");
        try { p.destroy(); } catch (Throwable ignored) {}
    }

    /** 流式解压 assets 里的归档：不再把 105MB/225MB 的 tar 先复制一份到私有目录 */
    private static void extractAsset(Context ctx, String name, File dest, TarX tarx) throws IOException {
        log("解压 " + name + " → " + dest.getAbsolutePath());
        tarx.extract(ctx.getAssets().open(name), dest);   // 流由 TarX 负责关闭
    }

    /**
     * 连接令牌持久化在 persist/cli/token：解压、升级都不会动它，
     * 于是 WebView 里已经下发的 cookie 下次启动仍然有效。
     */
    private static String loadOrCreateToken(File hostCli) {
        File f = new File(hostCli, "token");
        String t = f.exists() ? readText(f) : "";
        if (t.length() < 32) {
            t = newToken();
            writeText(f, t);
            try { android.system.Os.chmod(f.getAbsolutePath(), 0600); } catch (Throwable ignored) {}
            log("已生成连接令牌: " + f.getAbsolutePath());
        } else {
            log("复用已有连接令牌: " + f.getAbsolutePath());
        }
        return t;
    }

    private static String newToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    /** 服务端会把自己的带 tkn 的地址打到 stdout，这里统一打码，别让令牌流进日志文件 */
    private static String redact(String s) {
        if (s == null) return "";
        String t = token;
        if (t != null && !t.isEmpty()) s = s.replace(t, "<token>");
        return s.replaceAll("(tkn=)[^\\s&\"']+", "$1<token>");
    }

    public static boolean isUp() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final TarX.Log LOG = new TarX.Log() {
        @Override public void log(String s) { Boot.log(s); }
    };

    /** 记录关键文件的存在与权限，便于排查 */
    static void diag(File f) {
        StringBuilder sb = new StringBuilder("diag ").append(f.getAbsolutePath())
                .append(" exists=").append(f.exists())
                .append(" read=").append(f.canRead())
                .append(" exec=").append(f.canExecute())
                .append(" len=").append(f.length());
        try {
            android.system.StructStat st = android.system.Os.stat(f.getAbsolutePath());
            sb.append(" mode=").append(Integer.toOctalString(st.st_mode));
        } catch (Throwable t) {
            sb.append(" stat_err=").append(t.getClass().getSimpleName());
        }
        log(sb.toString());
    }

    private static String readText(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b); in.close();
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : "";
        } catch (Throwable t) { return ""; }
    }

    private static void writeText(File f, String s) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable t) { log("写入失败 " + f.getName() + ": " + t); }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static void copyAsset(Context ctx, String name, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        InputStream in = ctx.getAssets().open(name);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
        log("已释放资源 " + name + " (" + (dst.length() / 1048576) + "MB)");
    }

    private static void chmod(File f, int mode) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "/system/bin/chmod", Integer.toOctalString(mode), f.getAbsolutePath() });
            p.waitFor();
            f.setExecutable(true, false);
            f.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    public static String readLog(int maxBytes) {
        if (logFile == null || !logFile.exists()) return "";
        try {
            FileInputStream in = new FileInputStream(logFile);
            long len = logFile.length();
            if (len > maxBytes) in.skip(len - maxBytes);
            byte[] buf = new byte[maxBytes];
            int n = in.read(buf);
            in.close();
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
