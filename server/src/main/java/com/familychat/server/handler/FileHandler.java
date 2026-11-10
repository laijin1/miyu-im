package com.familychat.server.handler;

import com.familychat.server.FamilyChatServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class FileHandler {
    private static final String CHAT_DIR = com.familychat.server.model.Database.DATA_DIR + "/chat_files";

    // 短视频目录：DATA_DIR (项目根/server-java/data) 向上两级到项目根，再进入 视频/
    private static final String VIDEO_DIR = com.familychat.server.model.Database.DATA_DIR + "/../../视频";
    // 转码缓存目录
    private static final String VIDEO_CACHE_DIR = com.familychat.server.model.Database.DATA_DIR + "/video_cache";

    // Android 原生可播放的编码与容器
    private static final Set<String> NATIVE_CODECS = Set.of(
            "h264", "hevc", "h265", "vp8", "vp9", "av1", "mpeg4", "mjpeg");
    private static final Set<String> NATIVE_CONTAINERS = Set.of(
            "mp4", "mov", "m4v", "3gp", "3g2", "matroska", "webm", "mpegts", "m2ts");
    // 拖动/seek 可靠的容器（含索引信息）；TS 等不在其中
    private static final Set<String> SEEK_FRIENDLY = Set.of(
            "mp4", "mov", "m4v", "3gp", "3g2", "webm", "matroska");

    // 支持扫描的全部视频扩展名（无点，小写）
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
        "mp4","avi","mkv","mov","wmv","flv","webm","m4v","mpg","mpeg","mpe","m2v","m2ts","mts","ts","vob",
        "m2t","m2p","evo","divx","xvid","3gp","3g2","3gpp","3gpp2","asf","wm","wmx","wvx","wmz",
        "rm","rmvb","ram","ra","qt","qtz","ogv","ogg","ogm","ogx","nsv","nut","f4v","f4p","f4a","f4b",
        "swf","avchd","bik","bk2","mjpeg","mjpg","mj2","mjp2","amv","dpg","dv","dvr-ms","gvi","gxf",
        "hdmov","hkm","ivf","m4b","m4p","m4r","mk3d","mks","mng","mod","moov","mp2","mp2v","mp4v",
        "mpeg4","mpv","mpv2","mqv","msdvd","mxf","nuv","pjs","pss","pva","rmm","rp","rt","rts","sct",
        "sdv","seq","sfcap","smk","svi","tod","tp","tpr","trp","tts","ty","vcd","vdr","vp3","vp6",
        "vp7","vp8","vp9","wmp","wtv","xesc","xspf","yuv","264","265","hevc","h264","h265","h261",
        "h262","h263","av1","vvc","eac3","ec3","mka"
    );

    // Whitelist of allowed file extensions
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt",
            "mp3", "wav", "ogg", "aac",
            "mp4", "mov", "avi", "mkv"
    );

    // MIME type mapping
    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("txt", "text/plain"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("aac", "audio/aac"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("mkv", "video/x-matroska")
    );

    // APK path: configurable via system property, defaults to path relative to DATA_DIR
    // DATA_DIR = 项目根/server-java/data, 向上两级到项目根/release
    private static final String APK_PATH = System.getProperty("apk.path",
            com.familychat.server.model.Database.DATA_DIR + "/../../release/密语.apk");

    // Version file path: alongside APK in project root/release
    private static final String VERSION_FILE = com.familychat.server.model.Database.DATA_DIR + "/../../release/version.properties";

    public FileHandler() {
        new java.io.File(CHAT_DIR).mkdirs();
        new java.io.File(VIDEO_DIR).mkdirs();
        new java.io.File(VIDEO_CACHE_DIR).mkdirs();
        pruneVideoCacheNow();
        // 启动即建立 chat_files 用量基线，避免「进程刚起来的一小段时间里配额读到 0」
        reconcileUploadUsage();
        // TTL / 容量报告（只报告不删）；后续由 FamilyChatServer 的周期任务继续调用
        pruneChatFiles();
    }

    /**
     * 周期/启动时的视频转码缓存维护入口（体检报告 §3.3）。
     *
     * <p>原实现只按「文件个数」限制（{@code MAX_CACHE_FILES=200}），单文件大小完全不设防：
     * 实测生产目录里 2 个文件就占了 912.3MB（1.mp4 440.8MB + 2.mp4 471.5MB），
     * 按 200 个文件的上限推算，量级会滑到几十 GB。
     *
     * <p>现在改为**总大小为主、文件个数为辅**的双条件回收：先按 mtime 从旧到新删到
     * 总大小达标，再删到个数达标。阈值取自系统属性，默认 2GiB / 200 个。
     *
     * <p><b>首次只 audit 不删</b>（与第 3 项 chat_files 的 TTL 同策略，决策 (a)）：
     * 当前生产实际占用 912.3MB 远低于 2GiB 阈值，audit 模式下不会有任何删除；
     * 日志会明确写出「若开启删除会删哪些文件、能释放多少」。确认无误后再把
     * {@code -Dvideo.cache.audit.only=false} 打开真删。
     */
    public static void pruneVideoCacheNow() {
        if (VIDEO_CACHE_AUDIT_ONLY) {
            auditVideoCache(VIDEO_CACHE_MAX_BYTES, VIDEO_CACHE_MAX_FILES, false);
        } else {
            auditVideoCache(VIDEO_CACHE_MAX_BYTES, VIDEO_CACHE_MAX_FILES, true);
        }
    }

    // ===== video_cache 阈值（体检报告 §3.3「只按文件个数限制」）=====
    // 为什么总量定 2GiB：转码缓存是**可再生产物**（源视频在 视频/ 目录），删了最多重新转码；
    // 而 D 盘可用 43.78GB，2GiB ≈ 可用空间的 4.6%，同时是当前实际占用 912.3MB 的 2.2 倍，
    // 留足「同一批视频反复拖进度条」的缓存收益，又把「200 × 500MB = 100GB」这个真实风险封死。
    private static final long VIDEO_CACHE_MAX_BYTES =
            longProp("video.cache.max.bytes", 2L * 1024 * 1024 * 1024);
    private static final int VIDEO_CACHE_MAX_FILES = intProp("video.cache.max.files", 200);
    // 首次只 audit 不删；确认报告无误后再用 -Dvideo.cache.audit.only=false 打开真删
    private static final boolean VIDEO_CACHE_AUDIT_ONLY =
            Boolean.parseBoolean(System.getProperty("video.cache.audit.only", "true"));

    /**
     * 视频转码缓存回收入口（总大小 + 文件个数双条件）。
     *
     * @param maxBytes  总大小上限（字节）
     * @param maxFiles  文件个数上限
     * @param auditOnly true = 只报告不删除；false = 真删
     * @return 人类可读的报告摘要（供日志与测试断言）
     */
    static String pruneVideoCache(long maxBytes, int maxFiles, boolean auditOnly) {
        return auditVideoCache(maxBytes, maxFiles, !auditOnly);
    }

    /**
     * 回收/审计的统一实现。
     *
     * @param doDelete true 时真删，false 时只报告
     */
    private static String auditVideoCache(long maxBytes, int maxFiles, boolean doDelete) {
        StringBuilder sb = new StringBuilder();
        try {
            java.io.File dir = new java.io.File(VIDEO_CACHE_DIR);
            java.io.File[] all = dir.listFiles();
            if (all == null) return "";
            java.util.List<java.io.File> files = new java.util.ArrayList<>();
            long total = 0L;
            for (java.io.File f : all) {
                if (f.isFile()) { files.add(f); total += f.length(); }
            }
            if (files.isEmpty()) {
                sb.append("[CACHE] video_cache 统计: files=0 total=0B");
                System.out.println(sb);
                return sb.toString();
            }

            // 从旧到新排：回收优先淘汰最久未使用的缓存
            files.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

            java.util.List<java.io.File> victims = new java.util.ArrayList<>();
            long remainingBytes = total;
            // 条件 1：总大小
            for (java.io.File f : files) {
                if (remainingBytes <= maxBytes) break;
                if (victims.contains(f)) continue;
                victims.add(f);
                remainingBytes -= f.length();
            }
            // 条件 2：文件个数（在「已按大小要删的」之外继续从旧到新补）
            int remainingCount = files.size() - victims.size();
            if (remainingCount > maxFiles) {
                for (java.io.File f : files) {
                    if (remainingCount <= maxFiles) break;
                    if (victims.contains(f)) continue;
                    victims.add(f);
                    remainingCount--;
                }
            }

            long freed = 0L;
            for (java.io.File f : victims) freed += f.length();

            sb.append(doDelete ? "[CACHE][delete] " : "[CACHE][audit-only] ")
              .append("video_cache: files=").append(files.size())
              .append(" total=").append(total).append("B (").append(mb(total)).append("MB)")
              .append(" max_bytes=").append(maxBytes)
              .append(" max_files=").append(maxFiles)
              .append(" would_reclaim=").append(victims.size())
              .append(" freed_bytes=").append(freed);

            if (doDelete && !victims.isEmpty()) {
                int deleted = 0;
                for (java.io.File f : victims) {
                    if (f.delete()) deleted++;
                }
                sb.append(" deleted=").append(deleted);
            }
            for (java.io.File f : victims) {
                // name= 是 ASCII 键值对，保证「删哪些文件」在任何日志编码下都能被 grep 到
                sb.append("\n[CACHE] ").append(doDelete ? "[deleted]/[to-delete] " : "[would-delete] ")
                  .append("name=").append(f.getName())
                  .append(" size_bytes=").append(f.length())
                  .append("  ").append(mb(f.length())).append("MB")
                  .append("  mtime=").append(new java.util.Date(f.lastModified()));
            }
            if (!doDelete && !victims.isEmpty()) {
                sb.append("\n[CACHE][audit-only][no-delete] 以上为 audit 结果，本次未删除任何文件")
                  .append("（用 -Dvideo.cache.audit.only=false 开启真删）");
            }
            System.out.println(sb);
        } catch (Exception e) {
            System.err.println("[WARN] pruneVideoCache: " + e.getMessage());
        }
        return sb.toString();
    }

    private static String mb(long bytes) {
        return String.format("%.1f", bytes / 1024.0 / 1024.0);
    }

    public void register(Javalin app) {
        app.post("/api/chat/upload", this::uploadFile);
        app.get("/chat_files/{filename}", this::serveFile);
        app.get("/api/version", this::getVersion);
        // 短视频
        app.get("/api/videos/list", this::listVideos);
        app.get("/videos/{filename}", this::serveVideo);
        app.get("/api/videos/stream", this::streamVideo);
        // 根路径与原聊天页地址、/download 都展示 APK 下载页（网页聊天已移除）
        app.get("/", this::serveDownloadPage);
        app.get("/index.html", this::serveDownloadPage);
        app.get("/download", this::serveDownloadPage);
        // APK 更新检查接口（原由 client/version.json 静态伺服，现由服务端直接提供）
        app.get("/version.json", this::getVersion);
        // HEAD 也走 serveApk：下载页用 HEAD 探测 APK 大小（Content-Length），否则拿不到长度
        app.get("/apk", this::serveApk);
        app.head("/apk", this::serveApk);
    }

    /** 扫描 视频/ 目录，返回全部支持格式的视频列表（按数字文件名排序）。 */
    private void listVideos(Context ctx) {
        // 需登录, 防止公网隧道下任何人枚举视频列表/触发转码
        if (AuthHandler.requireAuthQuery(ctx) < 0) return;
        java.io.File dir = new java.io.File(VIDEO_DIR);
        if (!dir.exists()) dir.mkdirs();
        java.io.File[] files = dir.listFiles((d, n) -> {
            String ext = extOf(n);
            return ext != null && VIDEO_EXTENSIONS.contains(ext);
        });
        List<Map<String, Object>> videos = new ArrayList<>();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> Integer.compare(parseVideoNum(a.getName()), parseVideoNum(b.getName())));
            int id = 1;
            for (java.io.File f : files) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", id);
                v.put("url", "/videos/" + f.getName());
                v.put("title", "视频" + id);
                v.put("duration", ffprobeDuration(f)); // 秒，供客户端显示/拖动用
                videos.add(v);
                id++;
            }
        }
        ctx.json(Map.of("code", 200, "data", Map.of("videos", videos, "total", videos.size())));
    }

    private static String extOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase();
    }

    private static int parseVideoNum(String name) {
        try {
            String digits = name.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(digits);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 按扩展名返回常见视频 MIME；未知回退 octet-stream。 */
    private static String videoMimeType(String filename) {
        String ext = extOf(filename);
        if (ext == null) return "application/octet-stream";
        switch (ext) {
            case "mp4": case "m4v": case "mp4v": case "m4p": case "m4b": case "m4r": return "video/mp4";
            case "mkv": case "mk3d": case "mks": case "mka": return "video/x-matroska";
            case "webm": return "video/webm";
            case "avi": case "divx": case "xvid": return "video/x-msvideo";
            case "mov": case "qt": case "moov": return "video/quicktime";
            case "wmv": case "wm": case "asf": case "wmp": return "video/x-ms-wmv";
            case "flv": case "f4v": case "f4p": return "video/x-flv";
            case "mpg": case "mpeg": case "mpe": case "mp2": case "mp2v": case "m2v": case "m2p": case "mpeg4": case "mpv": case "mpv2": return "video/mpeg";
            case "ts": case "m2ts": case "mts": case "m2t": case "tp": case "tts": case "trp": case "tpr": return "video/mp2t";
            case "3gp": case "3g2": case "3gpp": case "3gpp2": return "video/3gpp";
            case "ogv": case "ogm": case "ogg": case "ogx": return "video/ogg";
            case "rm": case "rmvb": case "rmm": return "application/vnd.rn-realmedia";
            default: return "application/octet-stream";
        }
    }

    /** 提供视频文件，支持 HTTP Range 请求（拖动/续播需要）。 */
    private void serveVideo(Context ctx) {
        // 需登录(header 或 ?token=)
        if (AuthHandler.requireAuthQuery(ctx) < 0) return;

        String filename = ctx.pathParam("filename");
        // 路径穿越防护
        if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            ctx.status(400).json(Map.of("error", "无效的文件名"));
            return;
        }
        Path file = Paths.get(VIDEO_DIR, filename).normalize();
        if (!file.startsWith(Paths.get(VIDEO_DIR).normalize())) {
            ctx.status(400).json(Map.of("error", "无效的文件名"));
            return;
        }
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            ctx.status(404).json(Map.of("error", "视频不存在"));
            return;
        }
        serveVideoFile(ctx, file, videoMimeType(filename));
    }

    /**
     * 解析 HTTP {@code Range: bytes=...} 请求头。serveVideoFile 与 serveApk 共用。
     *
     * <p>支持三种标准形式：
     * <ul>
     *   <li>{@code bytes=start-end} —— 闭区间</li>
     *   <li>{@code bytes=start-}   —— 开放结尾（到文件末尾）</li>
     *   <li>{@code bytes=-suffixLen} —— <b>后缀区间</b>：最后 suffixLen 字节</li>
     * </ul>
     *
     * <p>为什么单独抽出来：原实现两处各抄一份，且都把 {@code parts[0]} 直接
     * {@code Long.parseLong}。后缀区间 {@code bytes=-500} 的 {@code parts[0]} 是空串，
     * 会抛 NumberFormatException 被 catch 吞掉 → 静默退化成整文件 200，
     * 客户端请求尾段却拿到全量，且对"断点续传"语义是错的
     * （见 docs/audits/LOGIC-AUDIT-2026-09-21.md 第 3 条）。
     *
     * @return 解析结果；{@link RangeSpec#invalid} 为 true 表示语法非法或区间不可满足，
     *         调用方应回 416（不再静默回退成 200）
     */
    private static final class RangeSpec {
        long start;
        long end;
        boolean isRange;    // 是否命中 Range（决定 200 还是 206）
        boolean invalid;    // 语法非法/不可满足 -> 416

        static RangeSpec full(long size) {
            RangeSpec r = new RangeSpec();
            r.start = 0;
            r.end = size - 1;
            r.isRange = false;
            r.invalid = false;
            return r;
        }

        static RangeSpec bad() {
            RangeSpec r = new RangeSpec();
            r.invalid = true;
            return r;
        }

        long length() {
            return end - start + 1;
        }
    }

    static RangeSpec parseRange(String range, long size) {
        // 无 Range 头：整段
        if (range == null || !range.startsWith("bytes=")) return RangeSpec.full(size);

        String spec = range.substring(6).trim();
        // 多区间（bytes=0-1,5-6）暂不支持：按整段处理（合法但非预期的回退，不是错误）
        if (spec.contains(",")) return RangeSpec.full(size);

        int dash = spec.indexOf('-');
        if (dash < 0) return RangeSpec.bad();          // 缺少 '-'，语法非法

        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();

        // 空文件：任何 Range 都不可满足
        if (size <= 0) return RangeSpec.bad();

        try {
            if (startStr.isEmpty()) {
                // 后缀区间 bytes=-N：最后 N 字节
                if (endStr.isEmpty()) return RangeSpec.bad();   // "bytes=-" 非法
                long suffixLen = Long.parseLong(endStr);
                if (suffixLen <= 0) return RangeSpec.bad();     // "bytes=-0" 不可满足
                RangeSpec r = new RangeSpec();
                r.start = Math.max(0, size - suffixLen);        // 请求量超过文件长度 -> 整个文件
                r.end = size - 1;
                r.isRange = true;
                return r;
            }

            long start = Long.parseLong(startStr);
            long end = endStr.isEmpty() ? size - 1 : Long.parseLong(endStr);
            if (start < 0 || start >= size || end < start) return RangeSpec.bad();
            if (end >= size) end = size - 1;                    // 超范围按标准截断
            RangeSpec r = new RangeSpec();
            r.start = start;
            r.end = end;
            r.isRange = true;
            return r;
        } catch (NumberFormatException e) {
            // bytes=abc-def 之类：明确 416，不再静默 200
            return RangeSpec.bad();
        }
    }

    /**
     * 完整跳过 {@code n} 字节。
     *
     * <p>{@link java.io.InputStream#skip} 的契约允许"少跳"，原实现 {@code in.skip(start)}
     * 忽略返回值：一旦底层流提前返回，会静默返回错误片段。本地 FileInputStream 实测能跳满，
     * 但这是靠实现细节而非契约，故改为循环直到跳满。
     */
    private static void skipFully(java.io.InputStream in, long n) throws java.io.IOException {
        if (n <= 0) return;
        in.skipNBytes(n);   // Java 12+，内部循环，保证跳满或抛 EOFException
    }

    /** 通用视频文件伺服（支持 HTTP Range 拖动/续播）。 */
    private void serveVideoFile(Context ctx, Path file, String contentType) {
        try {
            long size = Files.size(file);
            RangeSpec rs = parseRange(ctx.header("Range"), size);
            if (rs.invalid) {
                ctx.status(416).header("Content-Range", "bytes */" + size);
                return;
            }
            long start = rs.start, end = rs.end;
            boolean isRange = rs.isRange;
            java.io.InputStream in = Files.newInputStream(file);
            skipFully(in, start);
            ctx.header("Accept-Ranges", "bytes");
            ctx.contentType(contentType);
            if (isRange) {
                ctx.status(206);
                ctx.header("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
            final long left = rs.length();
            ctx.result(new java.io.FilterInputStream(in) {
                private long remaining = left;
                @Override public int read() throws java.io.IOException {
                    if (remaining <= 0) return -1;
                    remaining--;
                    return in.read();
                }
                @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                    if (remaining <= 0) return -1;
                    int toRead = (int) Math.min(len, remaining);
                    int n = in.read(b, off, toRead);
                    if (n > 0) remaining -= n;
                    return n;
                }
            });
        } catch (Exception e) {
            System.err.println("[ERROR] serveVideoFile: " + e.getMessage());
            ctx.status(404);
        }
    }

    /** 转码流接口：原生可播直接播，否则 FFmpeg 转码为 H.264+AAC MP4 后播放。 */
    private void streamVideo(Context ctx) {
        // 需登录, 防止公网隧道下任何人反复触发 ffmpeg 转码打满 CPU/磁盘
        if (AuthHandler.requireAuthQuery(ctx) < 0) return;

        String idStr = ctx.queryParam("id");
        if (idStr == null) { ctx.status(400).json(Map.of("error", "缺少 id")); return; }
        int id;
        try { id = Integer.parseInt(idStr); } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "无效 id")); return;
        }
        java.io.File src = resolveVideoById(id);
        if (src == null || !src.isFile()) { ctx.status(404).json(Map.of("error", "视频不存在")); return; }

        java.io.File out = new java.io.File(VIDEO_CACHE_DIR, baseName(src.getName()) + ".mp4");
        if (isNativePlayable(src)) {
            if (isSeekFriendly(containerOf(src))) {
                // 原生可播且容器带索引（mp4/mkv/webm 等）：直接播
                serveVideoFile(ctx, src.toPath(), "video/mp4");
            } else {
                // 可播但 seek 不可靠（TS 等）：先 -c copy 快速封装为 MP4（带 seek 索引），失败则全转码
                if (!ensureOutput(src, out, true)) { serveVideoFile(ctx, src.toPath(), "video/mp4"); return; }
                serveVideoFile(ctx, out.toPath(), "video/mp4");
            }
        } else {
            // 不可播：全转码为 H.264+AAC MP4
            if (!ensureOutput(src, out, false)) { serveVideoFile(ctx, src.toPath(), "video/mp4"); return; }
            serveVideoFile(ctx, out.toPath(), "video/mp4");
        }
    }

    /** 容器名（扩展名映射）。 */
    private String containerOf(java.io.File file) {
        String ext = extOf(file.getName());
        if (ext == null) return "";
        if (ext.equals("mkv")) return "matroska";
        if (ext.equals("ts") || ext.equals("m2ts") || ext.equals("mts") || ext.equals("m2t")) return "mpegts";
        return ext;
    }

    private boolean isSeekFriendly(String container) {
        return SEEK_FRIENDLY.contains(container);
    }

    /** 按 id 解析视频文件（与 /api/videos/list 相同排序）。 */
    private java.io.File resolveVideoById(int id) {
        java.io.File dir = new java.io.File(VIDEO_DIR);
        java.io.File[] files = dir.listFiles((d, n) -> {
            String ext = extOf(n);
            return ext != null && VIDEO_EXTENSIONS.contains(ext);
        });
        if (files == null || id < 1 || id > files.length) return null;
        java.util.Arrays.sort(files, (a, b) -> Integer.compare(parseVideoNum(a.getName()), parseVideoNum(b.getName())));
        return files[id - 1];
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** ffprobe 探测第一个视频流编码；失败返回 null。 */
    private String probeCodec(java.io.File file) {
        if (!acquireFfmpeg("probe")) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder("ffprobe", "-v", "error",
                    "-select_streams", "v:0", "-show_entries", "stream=codec_name",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.getAbsolutePath());
            Process p = pb.start();
            p.getOutputStream().close(); // 关闭 stdin，避免子进程阻塞
            if (waitForWithTimeout(p, 30_000) != 0) return null;
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            // 取第一个非空编码名（.ts 可能有多个视频流，ffprobe 会输出多行）
            for (String ln : out.split("\\r?\\n")) {
                if (!ln.trim().isEmpty()) return ln.trim().toLowerCase();
            }
            return null;
        } catch (Exception e) {
            System.err.println("[WARN] ffprobe failed: " + e.getMessage());
            return null;
        } finally {
            FFMPEG_SEM.release();
        }
    }

    /** 是否 Android 原生可播放（编码 + 容器都在白名单）。 */
    private boolean isNativePlayable(java.io.File file) {
        String ext = extOf(file.getName());
        if (ext == null) return true;
        // 容器白名单
        String container = ext.equals("mkv") ? "matroska"
                : ext.equals("ts") || ext.equals("m2ts") || ext.equals("mts") ? "mpegts"
                : ext.equals("m2t") ? "mpegts" : ext;
        if (!NATIVE_CONTAINERS.contains(container)) return false;
        // 编码白名单（ffprobe 探测）
        String codec = probeCodec(file);
        if (codec == null) return true; // 探测失败默认直播
        return NATIVE_CODECS.contains(codec);
    }

    /** 运行 FFmpeg 并等待完成，返回是否成功。 */
    private boolean runFfmpeg(String tag, java.util.List<String> args, java.io.File out) {
        if (!acquireFfmpeg(tag)) return false;
        // 先写临时文件, 成功后再原子改名, 防止进程崩溃/并发写残留半截文件被当成有效缓存
        java.io.File tmp = new java.io.File(out.getAbsolutePath() + ".tmp");
        try {
            System.out.println("[" + tag + "] " + args.get(2) + " -> " + out.getName());
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                    "ffmpeg", "-y", "-v", "error"));
            // args 最后一个元素是最终输出路径, 替换为临时文件路径
            cmd.addAll(args.subList(0, args.size() - 1));
            cmd.add(tmp.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close(); // 关闭 stdin，避免子进程阻塞
            try (java.io.InputStream is = p.getInputStream()) {
                byte[] buf = new byte[4096];
                while (is.read(buf) > 0) { /* 排空输出 */ }
            }
            // 超时强杀：损坏/异常文件不再能永久挂起工作线程
            int code = waitForWithTimeout(p, 10 * 60_000L);
            if (code != 0 || !tmp.exists() || tmp.length() == 0) {
                tmp.delete();
                return false;
            }
            if (out.exists()) out.delete();
            boolean renamed = tmp.renameTo(out);
            if (!renamed) tmp.delete();
            return renamed && out.exists() && out.length() > 0;
        } catch (Exception e) {
            tmp.delete();
            System.err.println("[ERROR] " + tag + ": " + e.getMessage());
            return false;
        } finally {
            FFMPEG_SEM.release();
        }
    }

    /** 转码单飞锁: key=输出文件绝对路径, 同一输出并发只转一次, 其余请求等待后用缓存。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> transcodeLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    // 全局 ffmpeg/ffprobe 并发上限：防止已登录用户并发请求多个视频打满 CPU/磁盘（DoS）
    private static final java.util.concurrent.Semaphore FFMPEG_SEM =
            new java.util.concurrent.Semaphore(2);

    /** 获取 ffmpeg 并发许可；等待超时返回 false（调用方回退/降级，不无限排队占线程）。 */
    private static boolean acquireFfmpeg(String tag) {
        try {
            if (!FFMPEG_SEM.tryAcquire(60, java.util.concurrent.TimeUnit.SECONDS)) {
                System.err.println("[WARN] " + tag + " busy, rejecting (ffmpeg concurrency limit)");
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 等待子进程结束（带超时），超时强杀并返回 -1；正常结束返回退出码。 */
    private static int waitForWithTimeout(Process p, long timeoutMs) {
        try {
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                System.err.println("[WARN] ffmpeg/ffprobe timed out, killed");
                return -1;
            }
            return p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return -1;
        }
    }

    /** 确保输出已生成(单飞): 并发请求同一视频只转一次; 失败返回 false, 调用方回退直播原片。 */
    private boolean ensureOutput(java.io.File src, java.io.File out, boolean remuxFirst) {
        if (out.exists()) return true;
        Object lock = transcodeLocks.computeIfAbsent(out.getAbsolutePath(), k -> new Object());
        synchronized (lock) {
            if (out.exists()) return true; // 等待期间别的请求已生成
            if (remuxFirst) {
                if (ffmpegRemux(src, out)) return true;
                if (ffmpegTranscode(src, out)) return true;
            } else {
                if (ffmpegTranscode(src, out)) return true;
            }
            return false;
        }
    }

    /** 全转码为 H.264+AAC MP4（不可原生播放的编码）。 */
    private boolean ffmpegTranscode(java.io.File src, java.io.File out) {
        return runFfmpeg("TRANSCODE", java.util.List.of("-i", src.getAbsolutePath(),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart", "-f", "mp4", out.getAbsolutePath()), out);
    }

    /** 快速封装为 MP4（-c copy，不重编码），保留 seek 索引；编码不兼容 MP4 时失败。 */
    private boolean ffmpegRemux(java.io.File src, java.io.File out) {
        return runFfmpeg("REMUX", java.util.List.of("-i", src.getAbsolutePath(),
                "-c", "copy", "-movflags", "+faststart", "-f", "mp4", out.getAbsolutePath()), out);
    }

    /** ffprobe 获取视频时长（秒），失败返回 0。 */
    private double ffprobeDuration(java.io.File file) {
        if (!acquireFfmpeg("probe")) return 0;
        try {
            ProcessBuilder pb = new ProcessBuilder("ffprobe", "-v", "error",
                    "-show_entries", "format=duration", "-of", "csv=p=0", file.getAbsolutePath());
            Process p = pb.start();
            p.getOutputStream().close();
            if (waitForWithTimeout(p, 30_000) != 0) return 0;
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return out.isEmpty() ? 0 : Double.parseDouble(out.trim());
        } catch (Exception e) {
            return 0;
        } finally {
            FFMPEG_SEM.release();
        }
    }

    /** 展示 APK 下载页：DATA_DIR (项目根/server-java/data) 向上两级到项目根，读取 download.html */
    private void serveDownloadPage(Context ctx) {
        try {
            Path downloadPage = Paths.get(com.familychat.server.model.Database.DATA_DIR,
                    "../../download.html").normalize();
            byte[] content = Files.readAllBytes(downloadPage);
            // no-store：防止浏览器缓存旧页面/旧版本号
            ctx.header("Cache-Control", "no-store");
            ctx.result(content).contentType("text/html; charset=UTF-8");
        } catch (Exception e) {
            ctx.result("下载页面未找到").contentType("text/plain; charset=UTF-8").status(404);
        }
    }

    // 聊天文件单文件上限：60MB（全局请求上限 100MB，这里进一步收紧，超限即中止防磁盘被撑爆）
    private static final long MAX_CHAT_FILE_SIZE = 60L * 1024 * 1024;

    // ===== 上传配额（体检报告 §3.2「上传无配额」）=====
    // 现状：只有全局 maxRequestSize 100MB + 单文件 60MB，没有「每个用户用了多少」的概念，
    // 也没有全服总量上限，更没有过期回收 —— 任何账号都能把 data/chat_files 撑爆磁盘。
    //
    // 数值依据（2026-09-18 实测）：D 盘可用 43.78GB（47,011,065,856 字节）；
    // data/chat_files 现为 111 个文件 / 142.8MB，单文件最大 93MB。
    //   * 单用户 2GiB  ≈ 实测最大单文件的 22 倍，家庭 3~6 人正常使用摸不到；
    //   * 全服 12GiB   ≈ 可用空间的 27%，给 <本地路径> 其它项目与本项目视频/出包留余量，
    //                    且 12/2 = 最多 6 个满配额用户就顶到全服线，一个人塞不爆盘。
    private static final long MAX_UPLOAD_BYTES_PER_USER =
            longProp("chat.upload.user.quota.bytes", 2L * 1024 * 1024 * 1024);
    private static final long MAX_UPLOAD_BYTES_TOTAL =
            longProp("chat.upload.total.quota.bytes", 12L * 1024 * 1024 * 1024);
    // TTL 报告阈值：只统计、只打日志，绝不删除（决策 (a)）。
    // 为什么先不删：单聊内容是 E2EE 密文，服务端无法可靠判断某个 chat_files 文件
    // 是否仍被聊天记录引用，按 mtime 直接删可能误删仍在使用的附件。
    private static final long UPLOAD_TTL_MS =
            longProp("chat.upload.ttl.ms", 180L * 24 * 3600 * 1000);
    // 报告目标：超过这些阈值就在日志里点名，供运维决定是否真的开删
    private static final long UPLOAD_REPORT_MAX_FILES =
            longProp("chat.upload.report.max.files", 2000L);
    private static final long UPLOAD_REPORT_MAX_BYTES =
            longProp("chat.upload.report.max.bytes", 8L * 1024 * 1024 * 1024);

    /** 读 long 型系统属性；缺失或非法一律回退默认值（不静默变成 0 把功能关掉）。 */
    private static long longProp(String key, long def) {
        try {
            String v = System.getProperty(key);
            return (v == null || v.trim().isEmpty()) ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[WARN] 非法系统属性 " + key + "，回退默认值 " + def);
            return def;
        }
    }

    /** 读 int 型系统属性；缺失或非法一律回退默认值。 */
    private static int intProp(String key, int def) {
        long v = longProp(key, def);
        return (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) ? def : (int) v;
    }

    // ===== 上传限流：防脚本灌文件 =====
    // 形状照抄 AuthHandler 既有实现（不抽公共类）：窗口内计数 + map 上限 + 过期清理
    private static final java.util.concurrent.ConcurrentHashMap<Integer, long[]> uploadRateLimit =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int UPLOAD_RATE_MAX = intProp("chat.upload.rate.max", 10);
    private static final long UPLOAD_RATE_WINDOW =
            longProp("chat.upload.rate.window.ms", 60_000L);
    private static final int UPLOAD_RATE_MAP_MAX = 10_000;

    /** 上传限流，超限返回 false。compute 原子计数，防并发绕过。 */
    private static boolean checkUploadRateLimit(int userId) {
        long now = System.currentTimeMillis();
        if (uploadRateLimit.size() >= UPLOAD_RATE_MAP_MAX) {
            uploadRateLimit.entrySet().removeIf(e -> now - e.getValue()[0] > UPLOAD_RATE_WINDOW);
            if (uploadRateLimit.size() >= UPLOAD_RATE_MAP_MAX) {
                int half = uploadRateLimit.size() / 2;
                java.util.Iterator<Map.Entry<Integer, long[]>> it = uploadRateLimit.entrySet().iterator();
                for (int i = 0; it.hasNext() && i < half; i++) {
                    it.next();
                    it.remove();
                }
            }
        }
        return uploadRateLimit.compute(userId, (k, e) -> {
            if (e == null || now - e[0] > UPLOAD_RATE_WINDOW) {
                return new long[]{now, 1};
            }
            e[1]++;
            return e;
        })[1] <= UPLOAD_RATE_MAX;
    }

    // ===== 用量统计（按上传者聚合 chat_files 的实际占用）=====
    // 计数来源是磁盘扫描 + 文件名里的上传者 userId（uploaderIdOf），不引入新表，
    // 避免与现有 111 个历史文件产生对账问题；扫描结果缓存，用量变化时增量维护。
    private static final long USAGE_RECONCILE_MS = 30_000L;
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> usagePerUser =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long usageTotalBytes = 0L;
    // 文件名解析不出上传者的文件（老格式/异常名）计入总量，保证 全服总量 不会漏算
    private static volatile long usageUnattributedBytes = 0L;
    private static volatile long usageLastScan = 0L;

    /** 重新扫描 chat_files，重建每用户/总量占用。 */
    private static synchronized void reconcileUploadUsage() {
        java.util.Map<Integer, Long> perUser = new java.util.HashMap<>();
        long total = 0L, unattributed = 0L;
        java.io.File[] files = new java.io.File(CHAT_DIR).listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (!f.isFile()) continue;          // 跳过目录与转码临时文件之外的条目
                long len = f.length();
                total += len;
                int uid = uploaderIdOf(f.getName());
                if (uid > 0) perUser.merge(uid, len, Long::sum);
                else unattributed += len;
            }
        }
        usagePerUser.clear();
        usagePerUser.putAll(perUser);
        usageTotalBytes = total;
        usageUnattributedBytes = unattributed;
        usageLastScan = System.currentTimeMillis();
    }

    /** 缓存过期则重新对账（外部手工删过文件时也能修正）。 */
    private static void reconcileUploadUsageIfStale() {
        if (System.currentTimeMillis() - usageLastScan > USAGE_RECONCILE_MS) {
            reconcileUploadUsage();
        }
    }

    /** 某用户已占用字节数。 */
    private static long uploadBytesOf(int userId) {
        Long v = usagePerUser.get(userId);
        return v == null ? 0L : v;
    }

    /**
     * 每用户配额锁：把「检查配额 + 记账」变成临界区。
     *
     * <p>为什么需要：配额检查读的是 {@code usedByUser} 快照，而记账发生在落盘之后。
     * 两者之间的窗口里，N 个并发请求都会读到同一个旧值、各自认为"还有空间"，
     * 然后一起写进去 —— 实测 40 并发可把 1000 字节配额顶到 4000（超额 4 倍）。
     * {@code usagePerUser} 虽是 ConcurrentHashMap（单次 merge 原子），
     * 但"读—判—写"整个序列不是原子的。
     *
     * <p>为什么按 userId 分段而不是全局锁：上传的主要耗时是磁盘 IO，
     * 全局锁会让所有用户的上传串行化。按用户加锁只串行化同一用户自己的并发上传，
     * 不同用户之间完全不受影响。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Object> quotaLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 取该用户的配额锁对象（computeIfAbsent 保证同一 userId 拿到同一把锁）。 */
    private static Object quotaLockOf(int userId) {
        return quotaLocks.computeIfAbsent(userId, k -> new Object());
    }

    /** 上传成功后增量记账，避免每次都全目录扫描。 */
    private static void recordUploadBytes(int userId, long bytes) {
        usagePerUser.merge(userId, bytes, Long::sum);
        usageTotalBytes += bytes;
    }

    /** 列出 chat_files 下的普通文件（用于 TTL/容量报告）。 */
    private static java.io.File[] listUploadFiles() {
        java.io.File[] files = new java.io.File(CHAT_DIR).listFiles();
        if (files == null) return new java.io.File[0];
        java.util.List<java.io.File> out = new java.util.ArrayList<>();
        for (java.io.File f : files) {
            if (f.isFile()) out.add(f);
        }
        return out.toArray(new java.io.File[0]);
    }

    /**
     * 周期任务：重新对账用量 + 输出 TTL / 容量报告。**只报告，绝不删除。**
     *
     * 回收策略按决策 (a)：单聊 E2EE 密文使引用检测不完整，按 mtime 直接删可能误删
     * 仍在使用的附件，因此先看报告数据再决定是否开删。配额 + 全服总量上限已经
     * 解决了「磁盘被打满」这个原始问题。
     *
     * @return 供测试断言使用的报告摘要
     */
    static String pruneChatFiles(long ttlMs, long maxFiles, long maxBytes) {
        reconcileUploadUsage();
        java.io.File[] files = listUploadFiles();
        long total = 0L;
        for (java.io.File f : files) total += f.length();

        long ttlCutoff = System.currentTimeMillis() - ttlMs;
        java.util.List<java.io.File> expired = new java.util.ArrayList<>();
        for (java.io.File f : files) {
            if (f.lastModified() < ttlCutoff) expired.add(f);
        }

        StringBuilder sb = new StringBuilder();
        // [report-only]：ASCII 标记，保证这条「只报告不删」的关键语义在任何日志编码
        // （Windows 控制台默认 GBK、Linux UTF-8）下都能被 grep 到，不受中文编码影响。
        sb.append("[CACHE][report-only] chat_files 报告: files=").append(files.length)
          .append(" total=").append(total).append("B (")
          .append(String.format("%.1f", total / 1024.0 / 1024.0)).append("MB)")
          .append(" 全服上限=").append(maxBytes).append("B")
          .append(" 超期(>").append(ttlMs / 24 / 3600 / 1000).append("天)=").append(expired.size())
          .append(" 个 [只报告不删除]");

        if (total > maxBytes || files.length > maxFiles) {
            // 超容量时点名最旧的文件（按 mtime 从旧到新），但不动它们
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            sb.append("\n[CACHE] 超容量，建议清理（按最旧优先，本次未删除）:");
            int n = 0;
            for (java.io.File f : files) {
                if (n >= 10) break;
                sb.append("\n[CACHE]   ").append(f.getName())
                  .append("  ").append(f.length() / 1024).append("KB")
                  .append("  mtime=").append(new java.util.Date(f.lastModified()));
                n++;
            }
            if (files.length > n) sb.append("\n[CACHE]   ... 另有 ").append(files.length - n).append(" 个");
        }
        if (!expired.isEmpty()) {
            expired.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            sb.append("\n[CACHE] 超期文件（TTL 报告，未删除）:");
            int n = 0;
            for (java.io.File f : expired) {
                if (n >= 10) break;
                sb.append("\n[CACHE]   ").append(f.getName())
                  .append("  ").append(f.length() / 1024).append("KB")
                  .append("  mtime=").append(new java.util.Date(f.lastModified()));
                n++;
            }
            if (expired.size() > n) sb.append("\n[CACHE]   ... 另有 ").append(expired.size() - n).append(" 个");
        }

        String report = sb.toString();
        System.out.println(report);
        return report;
    }

    /** 生产默认参数下的报告入口（周期任务调用）。 */
    public static void pruneChatFiles() {
        pruneChatFiles(UPLOAD_TTL_MS, UPLOAD_REPORT_MAX_FILES, UPLOAD_REPORT_MAX_BYTES);
    }

    /** 流式拷贝并计数，超过 maxBytes 立即中止返回 false（调用方负责清理半截文件）。 */
    private static boolean copyWithLimit(java.io.InputStream in, Path target, long maxBytes) throws java.io.IOException {
        long total = 0;
        byte[] buf = new byte[65536];
        try (java.io.OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) return false;
                out.write(buf, 0, n);
            }
        }
        return true;
    }

    private void uploadFile(Context ctx) {
        int userId = AuthHandler.requireAuth(ctx);
        if (userId < 0) return;

        // 上传频率限流：防脚本灌文件（正常聊天传文件远低于 10 次/分钟）
        if (!checkUploadRateLimit(userId)) {
            ctx.header("Retry-After", String.valueOf(UPLOAD_RATE_WINDOW / 1000));
            ctx.status(429).json(Map.of("error", "上传过于频繁，请稍后再试"));
            return;
        }

        // 配额前置检查（便宜的一步）：已经超额就没必要收字节了。
        // 这是**乐观快路径**，不承担并发正确性——真正的配额裁决在落盘后的
        // synchronized 临界区里（见下方 quotaLockOf 附近）。这里提前拒绝只是为了
        // 让「大文件被配额挡下」变成快速失败，而不是先收完字节再删。
        reconcileUploadUsageIfStale();
        long usedByUser = uploadBytesOf(userId);

        try {
            var file = ctx.uploadedFile("file");
            if (file == null) {
                ctx.status(400).json(Map.of("error", "未选择文件"));
                return;
            }

            // Validate file extension
            String originalFilename = file.filename();
            if (originalFilename == null || !originalFilename.contains(".")) {
                ctx.status(400).json(Map.of("error", "文件格式不支持"));
                return;
            }
            String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                ctx.status(400).json(Map.of("error", "不支持的文件类型"));
                return;
            }

            // 配额前置检查（精确的一步）：先看 Content-Length，超了就根本不用收字节。
            // 这一步让「大文件被配额挡下」变成可预期的快速失败，而不是先写满磁盘再删。
            long declared = ctx.req().getContentLengthLong();
            if (declared > 0) {
                if (usedByUser + declared > MAX_UPLOAD_BYTES_PER_USER) {
                    ctx.status(413).json(Map.of("error",
                            "你的存储空间已用满（上限 " + (MAX_UPLOAD_BYTES_PER_USER / 1024 / 1024) + "MB）"));
                    return;
                }
                if (usageTotalBytes + declared > MAX_UPLOAD_BYTES_TOTAL) {
                    ctx.status(507).json(Map.of("error", "服务器存储空间不足，请联系管理员"));
                    return;
                }
            }

            // Add UUID to prevent timestamp collision
            String name = System.currentTimeMillis() + "_" +
                    UUID.randomUUID().toString().substring(0, 8) + "_" +
                    userId + "." + ext;
            Path target = Paths.get(CHAT_DIR, name).normalize();

            // Ensure resolved path is within CHAT_DIR (defense in depth)
            if (!target.startsWith(Paths.get(CHAT_DIR).normalize())) {
                ctx.status(400).json(Map.of("error", "无效的文件名"));
                return;
            }

            // Stream directly to disk with size cap（超限中止并清理半截文件，防单文件撑爆磁盘）
            if (!copyWithLimit(file.content(), target, MAX_CHAT_FILE_SIZE)) {
                try { Files.deleteIfExists(target); } catch (Exception ignored) {}
                ctx.status(413).json(Map.of("error", "文件大小不能超过 60MB"));
                return;
            }

            // 配额硬校验 + 记账：必须在同一临界区内完成。
            // 关键点：进来后**重新读** usedByUser，不能用上面那个旧快照——
            // 否则并发下这层"硬校验"与前置检查同时失效（审计第 5b 条）。
            long written = Files.size(target);
            synchronized (quotaLockOf(userId)) {
                long usedNow = uploadBytesOf(userId);          // 锁内重读，拿最新值
                if (usedNow + written > MAX_UPLOAD_BYTES_PER_USER) {
                    try { Files.deleteIfExists(target); } catch (Exception ignored) {}
                    ctx.status(413).json(Map.of("error",
                            "你的存储空间已用满（上限 " + (MAX_UPLOAD_BYTES_PER_USER / 1024 / 1024) + "MB）"));
                    return;
                }
                if (usageTotalBytes + written > MAX_UPLOAD_BYTES_TOTAL) {
                    try { Files.deleteIfExists(target); } catch (Exception ignored) {}
                    ctx.status(507).json(Map.of("error", "服务器存储空间不足，请联系管理员"));
                    return;
                }
                recordUploadBytes(userId, written);            // 检查与记账之间无窗口
            }

            // 记「生成名 → 上传者」台账（单聊附件唯一可靠的明文关联）。
            // 为什么在这里：此刻 URL 还是明文，uploaderId 刚从鉴权拿到，生成名也是刚定的；
            // 一旦客户端把它加密进 messages.content，服务端就再也反查不出来了。
            // 为什么单独包一层 try：外层 catch 会把任何异常变成 500 上传失败，
            // 记账是旁路，绝不能让它的失败影响「文件已落盘、上传已成功」这个事实。
            try {
                if (!FamilyChatServer.getDb().recordChatFileUpload(name, userId, originalFilename, written)) {
                    System.err.println("[WARN] chat_file_uploads 记账失败（不影响上传）: name=" + name
                            + " uploader=" + userId);
                }
            } catch (Exception e) {
                System.err.println("[WARN] chat_file_uploads 记账异常（不影响上传）: name=" + name
                        + " err=" + e.getMessage());
            }

            ctx.json(Map.of("url", "/chat_files/" + name, "name", originalFilename,
                    "size", written));
        } catch (Exception e) {
            System.err.println("[ERROR] uploadFile: " + e.getMessage());
            ctx.status(500).json(Map.of("error", "上传失败"));
        }
    }

    private void serveFile(Context ctx) {
        // 聊天文件必须登录才能下载(header 或 ?token=, 兼容 Glide 加载)
        int userId = AuthHandler.requireAuthQuery(ctx);
        if (userId < 0) return;

        String filename = ctx.pathParam("filename");

        // --- PATH TRAVERSAL PREVENTION ---
        if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            ctx.status(400).json(Map.of("error", "无效的文件名"));
            return;
        }

        Path file = Paths.get(CHAT_DIR, filename).normalize();
        if (!file.startsWith(Paths.get(CHAT_DIR).normalize())) {
            ctx.status(400).json(Map.of("error", "无效的文件名"));
            return;
        }

        // --- 群内容越权防护：群文件库/群相册/群消息附件仅群成员可下载 ---
        Integer groupId = FamilyChatServer.getDb().findGroupIdByFileUrl("/chat_files/" + filename);
        if (groupId != null && !FamilyChatServer.getDb().isGroupMember(groupId, userId)) {
            ctx.status(403).json(Map.of("error", "无权访问该文件"));
            return;
        }

        // --- 单聊媒体越权防护（IDOR）：非群内容 = 单聊媒体，仅上传者本人或其已接受好友可下载 ---
        // 为什么这样取「会话参与方」：单聊媒体 URL 在 messages.content 里是 E2EE 密文
        // （客户端 maybeEncrypt 后才发送），服务端无法用 URL 反查 messages 的
        // sender_id/receiver_id；唯一明文链路是服务端自己生成的文件名里的上传者 userId。
        // 单聊一条媒体的两个参与方就是「上传者」与「某好友」，故等价校验为
        // 请求者 == 上传者 || areFriends(请求者, 上传者)。
        // 失败返回 404（而非 403），避免向非参与方暴露该资源是否存在。
        if (groupId == null) {
            int uploaderId = uploaderIdOf(filename);
            boolean allowed = uploaderId > 0
                    && (uploaderId == userId
                        || FamilyChatServer.getDb().areFriends(userId, uploaderId));
            if (!allowed) {
                ctx.status(404).json(Map.of("error", "文件不存在"));
                return;
            }
        }

        if (Files.exists(file) && Files.isRegularFile(file)) {
            try {
                // Detect content type
                String ext = getExtension(filename);
                String contentType = MIME_TYPES.getOrDefault(ext, "application/octet-stream");

                // Stream the file instead of loading all bytes into memory
                InputStream in = Files.newInputStream(file);
                ctx.result(in).contentType(contentType);
            } catch (Exception e) {
                System.err.println("[ERROR] serveFile: " + e.getMessage());
                ctx.status(404);
            }
        } else {
            ctx.status(404);
        }
    }

    private void getVersion(Context ctx) {
        try {
            // no-store：版本信息必须实时，禁止任何缓存
            ctx.header("Cache-Control", "no-store");
            java.io.File vf = new java.io.File(VERSION_FILE);
            java.io.File apk = new java.io.File(APK_PATH);
            if (vf.exists() && apk.exists()) {
                java.util.Properties props = new java.util.Properties();
                // Properties.load(InputStream) 默认按 ISO-8859-1 读，中文更新日志会乱码，需用 UTF-8 Reader
                try (java.io.InputStreamReader isr = new java.io.InputStreamReader(
                        new java.io.FileInputStream(vf),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    props.load(isr);
                }
                // 发布窗口保护：APK 文件比 version.properties 旧 = 版本号已升、但新 APK 尚未就位。
                // 此时绝不对外提供下载，否则客户端/网页会拿到与版本号不符的旧包（装完发现版本不对）。
                // 只有 APK 就位（mtime ≥ 版本文件 mtime）后才对外公布版本并提供下载。
                boolean published = apk.lastModified() >= vf.lastModified();
                if (!published) {
                    System.out.println("[UPDATE] 发布窗口: 版本文件已更新但 APK 未就位，暂不提供下载");
                    ctx.json(Map.of(
                        "versionCode", props.getProperty("versionCode", "0"),
                        "versionName", props.getProperty("versionName", ""),
                        "downloadUrl", "/apk",
                        "changelog", "新版本正在发布中，请稍后刷新重试",
                        "apkSha256", "",
                        "published", false));
                    return;
                }
                ctx.json(Map.of(
                    "versionCode", props.getProperty("versionCode", "0"),
                    "versionName", props.getProperty("versionName", "1.0.0"),
                    "downloadUrl", "/apk",
                    "changelog", props.getProperty("changelog", ""),
                    "apkSha256", apkSha256(),
                    "published", true
                ));
            } else {
                ctx.json(Map.of("versionCode", "0", "versionName", "1.0.0",
                        "downloadUrl", "/apk", "changelog", "", "apkSha256", "", "published", false));
            }
        } catch (Exception e) {
            System.err.println("[ERROR] getVersion: " + e.getMessage());
            ctx.status(500).json(Map.of("error", "获取版本信息失败"));
        }
    }

    // APK SHA-256 缓存: 文件大小/修改时间变化时重算
    private static volatile String cachedApkSha256 = null;
    private static volatile long cachedApkSize = -1;
    private static volatile long cachedApkMtime = -1;

    /** 计算发布 APK 的 SHA-256, 供客户端下载后校验完整性(防 MITM 替换)。 */
    private static String apkSha256() {
        try {
            java.io.File apk = new java.io.File(APK_PATH);
            if (!apk.exists()) return "";
            long size = apk.length();
            long mtime = apk.lastModified();
            if (cachedApkSha256 != null && size == cachedApkSize && mtime == cachedApkMtime) {
                return cachedApkSha256;
            }
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(apk.toPath())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            cachedApkSha256 = sb.toString();
            cachedApkSize = size;
            cachedApkMtime = mtime;
            return cachedApkSha256;
        } catch (Exception e) {
            System.err.println("[ERROR] apkSha256: " + e.getMessage());
            return "";
        }
    }

    /** 提供 APK 下载，支持 HTTP Range 断点续传（客户端更新页网络中断后可续传，不用从头下）。 */
    private void serveApk(Context ctx) {
        try {
            Path apk = Paths.get(APK_PATH).toAbsolutePath().normalize();
            if (!Files.exists(apk) || !Files.isRegularFile(apk)) {
                ctx.status(404).json(Map.of("error", "APK文件未找到"));
                return;
            }
            // 发布窗口保护：版本文件比 APK 新 = 新 APK 尚未就位，此时禁止下载旧包（双保险，
            // version.json 已先挡一道，这里再挡直接拿 /apk 访问的用户）。
            java.io.File vf = new java.io.File(VERSION_FILE);
            if (vf.exists() && vf.lastModified() > apk.toFile().lastModified()) {
                ctx.status(404).json(Map.of("error", "新版本发布中，请稍后重试"));
                return;
            }
            long size = Files.size(apk);
            RangeSpec rs = parseRange(ctx.header("Range"), size);
            if (rs.invalid) {
                ctx.status(416).header("Content-Range", "bytes */" + size);
                return;
            }
            long start = rs.start, end = rs.end;
            boolean isRange = rs.isRange;
            java.io.InputStream in = Files.newInputStream(apk);
            skipFully(in, start);
            ctx.header("Accept-Ranges", "bytes");
            // no-store：APK 必须实时下载，禁止浏览器/CDN 缓存旧版本
            ctx.header("Cache-Control", "no-store");
            ctx.contentType("application/vnd.android.package-archive");
            long length = end - start + 1;
            if (isRange) {
                ctx.status(206);
                ctx.header("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
            // 显式 Content-Length：客户端靠它算下载百分比；Range 分段时精确到本段长度，不丢字节
            ctx.header("Content-Length", String.valueOf(length));
            final long leftTotal = length;
            ctx.result(new java.io.FilterInputStream(in) {
                private long remaining = leftTotal;
                @Override public int read() throws java.io.IOException {
                    if (remaining <= 0) return -1;
                    remaining--;
                    return in.read();
                }
                @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                    if (remaining <= 0) return -1;
                    int toRead = (int) Math.min(len, remaining);
                    int n = in.read(b, off, toRead);
                    if (n > 0) remaining -= n;
                    return n;
                }
            });
        } catch (Exception e) {
            System.err.println("[ERROR] serveApk: " + e.getMessage());
            ctx.status(500);
        }
    }

    private static String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 文件名 → 上传者 userId 的解析式。当前格式
     * {@code {毫秒时间戳}_{8位uuid}_{userId}.{ext}}（见 uploadFile），
     * 兼容早期格式 {@code {时间戳}_{userId}.{ext}}（uuid 段可选）。
     * 两种格式的尾巴都是 {@code _{纯数字}.{扩展名}}，而扩展名限定为字母数字且非纯数字，
     * 因此不会把 uuid 误当成 userId。
     */
    private static final java.util.regex.Pattern UPLOAD_FILENAME_RE =
            java.util.regex.Pattern.compile("^\\d+_(?:[0-9a-f]{8}_)?(\\d+)\\.[A-Za-z0-9]{1,16}$");

    /** 解析文件名的上传者 userId；解析不出返回 -1（调用方 fail closed）。 */
    private static int uploaderIdOf(String filename) {
        if (filename == null) return -1;
        java.util.regex.Matcher m = UPLOAD_FILENAME_RE.matcher(filename);
        if (!m.matches()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
