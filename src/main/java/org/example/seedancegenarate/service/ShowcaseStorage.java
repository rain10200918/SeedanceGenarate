package org.example.seedancegenarate.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import org.example.seedancegenarate.config.OssConfig;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.net.URI;
import java.util.Date;

/** Persistent curated media; does not inherit generated-output prefixes or expiry. */
@Service
public class ShowcaseStorage {
    private final OSS oss;
    private final OssConfig config;
    public ShowcaseStorage(OSS oss, OssConfig config) { this.oss = oss; this.config = config; }

    public void put(String key, InputStream stream, String contentType, long size) {
        checkKey(key);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setContentLength(size);
        metadata.setObjectAcl(CannedAccessControlList.Private);
        PutObjectRequest request = new PutObjectRequest(config.getBucketName(), key, stream, metadata);
        oss.putObject(request);
    }

    public URI sign(String key) {
        checkKey(key);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(config.getBucketName(), key);
        request.setExpiration(new Date(System.currentTimeMillis() + 60_000));
        ResponseHeaderOverrides headers = new ResponseHeaderOverrides();
        headers.setCacheControl("no-store");
        request.setResponseHeaders(headers);
        return URI.create(oss.generatePresignedUrl(request).toString().replaceFirst("^http://", "https://"));
    }

    private static void checkKey(String key) {
        if (key == null || !key.startsWith("showcase/") || key.contains(".."))
            throw new IllegalArgumentException("Invalid showcase key");
    }
}
