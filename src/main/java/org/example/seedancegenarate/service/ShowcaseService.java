package org.example.seedancegenarate.service;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.ShowcaseWork;
import org.example.seedancegenarate.entity.ShowcaseWork.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ShowcaseService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShowcaseService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final JdbcTemplate db;
    private final ShowcaseStorage storage;
    public ShowcaseService(JdbcTemplate db, ShowcaseStorage storage) { this.db = db; this.storage = storage; }

    public ShowcaseWork create(String clientRequestId, String title, String description,
                               MultipartFile file, MultipartFile cover) {
        long owner = requireAdmin();
        String request = uuid(clientRequestId);
        validateTitle(title); validateDescription(description);
        String desc = description == null ? "" : description;
        // Fail closed even when called by another service inside its transaction.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw error(409, "请在独立请求中上传作品");
        try (Prepared media = prepare(file, false); Prepared image = cover == null ? null : prepare(cover, true)) {
            MessageDigest hash = digest();
            for (String value : List.of(title, desc, media.type, media.hash,
                    image == null ? "" : image.type, image == null ? "" : image.hash)) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
            }
            String fingerprint = HexFormat.of().formatHex(hash.digest());
            ShowcaseWork replay = replay(owner, request, fingerprint);
            if (replay != null) return replay;
            String id = UUID.randomUUID().toString();
            String prefix = "showcase/" + owner + "/" + request + "/" + fingerprint;
            String key = prefix + "/media." + media.extension;
            String coverKey = image == null ? null : prefix + "/cover." + image.extension;
            try {
                upload(key, media);
                if (image != null) upload(coverKey, image);
            } catch (RuntimeException failure) {
                log.warn("Showcase orphan candidates: mediaKey={} coverKey={}", key, coverKey);
                throw failure;
            }
            try {
                db.update("""
                    INSERT INTO showcase_work(id,owner_id,client_request_id,request_hash,title,description,
                    media_type,media_key,cover_key) VALUES (?,?,?,?,?,?,?,?,?)
                    """, id, owner, request, fingerprint, title, desc,
                    media.type.startsWith("video/") ? "VIDEO" : "IMAGE", key, coverKey);
            } catch (DuplicateKeyException concurrent) {
                try {
                    ShowcaseWork winner = replay(owner, request, fingerprint);
                    if (winner != null) return winner;
                    throw error(409, "上传冲突，请重试");
                } catch (RuntimeException conflict) {
                    log.warn("Showcase orphan candidates: mediaKey={} coverKey={}", key, coverKey);
                    throw conflict;
                }
            } catch (RuntimeException failure) {
                log.warn("Showcase orphan candidates: mediaKey={} coverKey={}", key, coverKey);
                throw error(503, "保存失败，请使用原请求重试");
            }
            return get(id);
        } catch (IOException e) {
            throw error(503, "文件处理失败，请重试");
        }
    }

    public Page<ShowcaseWork> adminPage(long current, long size) { requireAdmin(); return page(current, size, false); }
    public Page<ShowcaseWork> publicPage(long current, long size) { requireLogin(); return page(current, size, true); }
    public URI adminMedia(String id, boolean cover) { requireAdmin(); return media(id, cover, false); }
    public URI publicMedia(String id, boolean cover) { requireLogin(); return media(id, cover, true); }

    public ShowcaseWork edit(String id, Edit edit) {
        requireAdmin(); uuid(id);
        if (edit == null || edit.expectedVersion() == null || edit.expectedVersion() < 0 || edit.expectedVersion() == Long.MAX_VALUE)
            throw error(400, "请提供有效版本号");
        if (edit.title() != null) validateTitle(edit.title());
        validateDescription(edit.description());
        if (edit.sortOrder() != null && (edit.sortOrder() < 0 || edit.sortOrder() > 999999))
            throw error(400, "排序值无效");
        if (edit.status() != null && !Set.of("DRAFT", "PUBLISHED", "OFFLINE").contains(edit.status()))
            throw error(400, "作品状态无效");
        int changed = db.update("""
            UPDATE showcase_work SET title=COALESCE(?,title),description=COALESCE(?,description),
            sort_order=COALESCE(?,sort_order),
            published_at=CASE WHEN ?='PUBLISHED' AND status<>'PUBLISHED' THEN CURRENT_TIMESTAMP(6) ELSE published_at END,
            status=COALESCE(?,status),version=version+1 WHERE id=? AND version=?
            """, edit.title(), edit.description(), edit.sortOrder(), edit.status(), edit.status(), id, edit.expectedVersion());
        if (changed == 0) { get(id); throw error(409, "作品已更新，请刷新后重试"); }
        return get(id);
    }

    private Page<ShowcaseWork> page(long current, long size, boolean published) {
        if (current < 1 || size < 1 || size > 100 || current > Long.MAX_VALUE / size)
            throw error(400, "分页参数无效");
        String filter = published ? " WHERE status='PUBLISHED'" : "";
        var records = db.query("SELECT * FROM showcase_work" + filter
                + " ORDER BY sort_order DESC,published_at DESC,id DESC LIMIT ? OFFSET ?",
                ShowcaseService::map, size, (current - 1) * size);
        Long total = db.queryForObject("SELECT COUNT(*) FROM showcase_work" + filter, Long.class);
        return new Page<>(records, total == null ? 0 : total, current, size);
    }

    private URI media(String id, boolean cover, boolean published) {
        uuid(id);
        var keys = db.query("SELECT media_key,cover_key FROM showcase_work WHERE id=?"
                + (published ? " AND status='PUBLISHED'" : ""),
                (rs, n) -> rs.getString(cover ? "cover_key" : "media_key"), id);
        if (keys.isEmpty() || keys.get(0) == null) throw error(404, "作品不存在");
        try { return storage.sign(keys.get(0)); }
        catch (RuntimeException e) { throw error(503, "媒体暂时不可用，请重试"); }
    }

    private ShowcaseWork replay(long owner, String request, String hash) {
        return db.query("SELECT * FROM showcase_work WHERE owner_id=? AND client_request_id=?", (rs, n) -> {
            if (!hash.equals(rs.getString("request_hash"))) throw error(409, "请求编号已用于其他内容");
            return map(rs, n);
        }, owner, request).stream().findFirst().orElse(null);
    }
    private ShowcaseWork get(String id) {
        return db.query("SELECT * FROM showcase_work WHERE id=?", ShowcaseService::map, id)
                .stream().findFirst().orElseThrow(() -> error(404, "作品不存在"));
    }
    private void upload(String key, Prepared file) throws IOException {
        try (InputStream input = Files.newInputStream(file.path)) {
            storage.put(key, input, file.type, file.size);
        } catch (RuntimeException | IOException e) {
            log.warn("Showcase upload uncertain; private orphan candidate key={}", key);
            throw error(503, "上传失败，请使用原请求重试");
        }
    }

    private record Prepared(Path path, String extension, String type, String hash, long size) implements AutoCloseable {
        public void close() throws IOException { Files.deleteIfExists(path); }
    }

    private static Prepared prepare(MultipartFile file, boolean cover) throws IOException {
        long limit = (cover ? 5L : 200L) * 1024 * 1024;
        if (file == null || file.getSize() <= 0) throw error(400, "请选择非空文件");
        if (file.getSize() > limit) throw error(413, "文件超过大小限制");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if (name.lastIndexOf('.') < 0) throw error(400, "不支持的文件格式");
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("jpeg")) extension = "jpg";
        String type = switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "mp4", "mov" -> cover ? null : "video/mp4";
            default -> null;
        };
        if (type == null) throw error(400, "不支持的文件格式");
        Path path = Files.createTempFile("showcase-", ".upload");
        boolean complete = false;
        try (InputStream in = file.getInputStream(); OutputStream out = Files.newOutputStream(path)) {
            byte[] header = in.readNBytes(32);
            boolean valid = switch (type) {
                case "image/png" -> header.length >= 8 && Arrays.equals(Arrays.copyOf(header, 8),
                        new byte[]{(byte)137,80,78,71,13,10,26,10});
                case "image/jpeg" -> header.length >= 3 && (header[0] & 255) == 255
                        && (header[1] & 255) == 216 && (header[2] & 255) == 255;
                default -> validMp4(header, file.getSize());
            };
            if (!valid) throw error(400, "文件内容与格式不符");
            if (type.startsWith("video/")) {
                boolean quickTime = "qt  ".equals(new String(header, 8, 4, StandardCharsets.US_ASCII));
                type = quickTime ? "video/quicktime" : "video/mp4";
                extension = quickTime ? "mov" : "mp4";
            }
            MessageDigest hash = digest();
            hash.update(header); out.write(header);
            long size = header.length;
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                size += count;
                if (size > limit) throw error(413, "文件超过大小限制");
                hash.update(buffer, 0, count); out.write(buffer, 0, count);
            }
            if (size != file.getSize()) throw error(400, "文件长度不匹配");
            Prepared prepared = new Prepared(path, extension, type, HexFormat.of().formatHex(hash.digest()), size);
            complete = true;
            return prepared;
        } finally {
            if (!complete) Files.deleteIfExists(path);
        }
    }

    private static boolean validMp4(byte[] header, long size) {
        if (header.length < 16 || !"ftyp".equals(new String(header, 4, 4, StandardCharsets.US_ASCII))) return false;
        long box = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt());
        if (box < 16 || box > size) return false;
        String brand = new String(header, 8, 4, StandardCharsets.US_ASCII);
        return Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "MSNV", "dash", "qt  ").contains(brand);
    }

    public static long requireAdmin() {
        long id = requireLogin();
        if (!UserContext.isAdmin()) throw error(403, "无权限访问");
        return id;
    }
    private static long requireLogin() {
        Long id = UserContext.getUserId();
        if (id == null) throw error(401, "请先登录");
        return id;
    }
    private static String uuid(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches())
            throw error(400, "请求编号无效");
        return UUID.fromString(value).toString();
    }
    private static void validateTitle(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw error(400, "标题需为1至128个字符");
    }
    private static void validateDescription(String value) {
        if (value != null && value.length() > 2000) throw error(400, "简介最多2000个字符");
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ResponseStatusException error(int status, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status), message);
    }

    private static ShowcaseWork map(ResultSet rs, int row) throws SQLException {
        var published = rs.getTimestamp("published_at");
        return new ShowcaseWork(rs.getString("id"), rs.getString("title"), rs.getString("description"),
                rs.getString("media_type"), rs.getString("cover_key") != null, rs.getString("status"),
                rs.getInt("sort_order"), rs.getLong("version"), rs.getTimestamp("created_at").toLocalDateTime(),
                published == null ? null : published.toLocalDateTime());
    }
}
