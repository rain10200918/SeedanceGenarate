package org.example.seedancegenarate.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Versioned, length-framed identity of the original request, not downloaded media or mutable model defaults. */
public final class ApiRequestFingerprint {
    private ApiRequestFingerprint() {}

    public static String of(ApiVideoService.CreateContext request) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            field(out, "api-generation:v1");
            field(out, trim(request.prompt()));
            field(out, trim(request.model()));
            urls(out, request.imageUrls());
            urls(out, request.videoUrls());
            urls(out, request.audioUrls());
            field(out, request.duration() == null ? null : request.duration().toString());
            field(out, request.ratio());
            field(out, request.megapixels() == null ? null : request.megapixels().toString());
            out.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("无法计算请求指纹", failure);
        }
    }

    private static void urls(DataOutputStream out, List<String> values) throws java.io.IOException {
        out.writeInt(values == null ? 0 : values.size());
        if (values != null) for (String value : values) field(out, trim(value));
    }

    private static String trim(String value) { return value == null ? null : value.trim(); }

    private static void field(DataOutputStream out, String value) throws java.io.IOException {
        if (value == null) { out.writeInt(-1); return; }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw org.example.seedancegenarate.exception.ApiException.validation("请求参数包含非法 Unicode 字符");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw org.example.seedancegenarate.exception.ApiException.validation("请求参数包含非法 Unicode 字符");
            }
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
