package io.dsh.vscode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * 极简 tar(.gz) 解包器：自己按头部里的 mode 设权限，
 * 不依赖 /system/bin/tar（后者以应用身份运行时可能丢可执行位）。
 */
public final class TarX {

    public interface Log { void log(String s); }

    private final Log log;
    private final int strip;

    public TarX(Log log) { this(log, 0); }

    public TarX(Log log, int stripComponents) {
        this.log = log;
        this.strip = stripComponents;
    }

    private String stripName(String name) {
        if (strip <= 0) return name;
        String[] parts = name.split("/");
        if (parts.length <= strip) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = strip; i < parts.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    public void extract(File archive, File dest) throws IOException {
        InputStream raw = new java.io.FileInputStream(archive);
        try {
            extract(raw, dest, archive.getName());
        } finally {
            closeQuietly(raw);
        }
    }

    /**
     * 直接从（assets / 网络来的）流解压，省掉"先把 105MB/225MB 的 tar 复制到私有目录"这一步。
     * 注意：本方法会关闭传入的流。
     */
    public void extract(InputStream source, File dest) throws IOException {
        extract(source, dest, "stream");
    }

    private void extract(InputStream source, File dest, String label) throws IOException {
        dest.mkdirs();
        InputStream in = new GZIPInputStream(source, 1 << 16);
        byte[] hdr = new byte[512];
        long files = 0, dirs = 0, links = 0;
        String pendingPath = null;

        long entries = 0;
        try {
            while (readFully(in, hdr, 512) == 512) {
                if (allZero(hdr)) break;
                if (++entries % 2000 == 0) {
                    log.log("[tarx] " + label + " 已处理 " + entries + " 项");
                }

                String name = str(hdr, 0, 100);
                String prefix = str(hdr, 345, 155);
                if (prefix.length() > 0) name = prefix + "/" + name;
                int mode = (int) octal(hdr, 100, 8);
                long size = octal(hdr, 124, 12);
                char type = (char) (hdr[156] & 0xff);
                String link = str(hdr, 157, 100);

                if (type == 'L') { // GNU long name
                    byte[] data = readData(in, size);
                    pendingPath = new String(data, StandardCharsets.UTF_8).replace("\0", "").trim();
                    continue;
                }
                if (type == 'x' || type == 'g') { // pax header：跳过内容
                    byte[] data = readData(in, size);
                    String s = new String(data, StandardCharsets.UTF_8);
                    int i = s.indexOf(" path=");
                    if (i >= 0) {
                        String p = s.substring(i + 6);
                        int nl = p.indexOf('\n');
                        pendingPath = nl > 0 ? p.substring(0, nl) : p;
                    }
                    continue;
                }
                if (pendingPath != null) { name = pendingPath; pendingPath = null; }

                name = stripName(name);
                if (name.isEmpty()) { skip(in, size); continue; }
                File out = new File(dest, name);
                if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath())) {
                    skip(in, size);
                    continue;
                }

                if (type == '5' || name.endsWith("/")) {
                    out.mkdirs();
                    chmod(out, mode);
                    dirs++;
                    skip(in, size);
                } else if (type == '2') {
                    out.getParentFile().mkdirs();
                    out.delete();
                    try {
                        java.nio.file.Files.createSymbolicLink(
                                java.nio.file.Paths.get(out.getAbsolutePath()),
                                java.nio.file.Paths.get(link));
                    } catch (Throwable t) {
                        try {
                            Process p = Runtime.getRuntime().exec(new String[] {
                                    "/system/bin/ln", "-s", link, out.getAbsolutePath() });
                            p.waitFor();
                        } catch (Throwable ignored) {}
                    }
                    links++;
                    skip(in, size);
                } else if (type == '1') {
                    File src = new File(dest, link);
                    out.getParentFile().mkdirs();
                    copyFile(src, out);
                    chmod(out, mode);
                    files++;
                    skip(in, size);
                } else if (type == '0' || type == '\0' || type == '7') {
                    out.getParentFile().mkdirs();
                    OutputStream os = new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16);
                    copy(in, os, size);
                    os.close();
                    chmod(out, mode);
                    files++;
                } else {
                    log.log("[tarx] 跳过类型 " + type + " " + name);
                    skip(in, size);
                }
                // 512 对齐
                long pad = (512 - (size % 512)) % 512;
                if (pad > 0) skip(in, pad);
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        log.log("[tarx] 完成 " + label + ": 文件 " + files + " 目录 " + dirs + " 链接 " + links);
    }

    private void chmod(File f, int mode) {
        boolean exec = (mode & 0111) != 0;
        f.setReadable((mode & 0400) != 0, false);
        f.setWritable((mode & 0200) != 0, true);
        f.setExecutable(exec, false);
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new java.io.FileInputStream(src);
        OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(dst), 1 << 16);
        copy(in, out, -1);
        in.close();
        out.close();
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[1 << 16];
        long left = size < 0 ? Long.MAX_VALUE : size;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int n = in.read(buf, 0, want);
            if (n < 0) throw new IOException("tar 数据不完整");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) return;
            left -= r;
        }
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        byte[] d = new byte[(int) size];
        int off = 0;
        while (off < size) {
            int n = in.read(d, off, (int) size - off);
            if (n < 0) break;
            off += n;
        }
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) skip(in, pad);
        return d;
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return off;
            off += n;
        }
        return off;
    }

    private static void closeQuietly(InputStream in) {
        try { in.close(); } catch (IOException ignored) {}
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xff;
            if (c == 0 || c == ' ') { if (v != 0) break; else continue; }
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }
}
