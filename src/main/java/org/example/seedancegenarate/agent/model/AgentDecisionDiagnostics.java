package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Opt-in local development diagnostics; never part of Agent state or execution. */
@Component
public class AgentDecisionDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(AgentDecisionDiagnostics.class);
    private final ObjectMapper json;
    private final boolean enabled;
    private final String directory;

    public AgentDecisionDiagnostics(ObjectMapper json,
            @Value("${agent.decision-diagnostics.enabled:false}") boolean enabled,
            @Value("${agent.decision-diagnostics.directory:./.agent-diagnostics}") String directory) {
        this.json = json.copy().disable(SerializationFeature.INDENT_OUTPUT);
        this.enabled = enabled;
        this.directory = directory;
    }

    public void record(String turnId, long epoch, int step, String channel, String reason, String raw) {
        String id = UUID.randomUUID().toString();
        try {
            var record = json.createObjectNode().put("id", id).put("createdAt", Instant.now().toString())
                    .put("turnId", bounded(turnId, 128)).put("epoch", epoch).put("step", step)
                    .put("channel", bounded(channel, 128)).put("reason", bounded(reason, 512))
                    .put("enabled", enabled);
            log.info("Agent invalid decision diagnostic id={} metadataJson={}", id, escaped(json.writeValueAsString(record)));
            if (!enabled) return;

            String captured = bounded(raw, 65536);
            record.put("rawCaptured", raw != null).put("raw", captured)
                    .put("truncated", raw != null && captured.length() != raw.length());
            if (raw == null) record.putNull("originalChars"); else record.put("originalChars", raw.length());
            // Raw logging is deliberately restricted to this explicit development opt-in.
            log.info("Agent invalid decision development raw id={} rawJson={}", id, escaped(json.writeValueAsString(captured)));

            Path folder = Path.of(directory);
            var privateDirectory = PosixFilePermissions.fromString("rwx------");
            Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(privateDirectory));
            // Never chmod an existing user directory or follow its final symlink.
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(folder, LinkOption.NOFOLLOW_LINKS).equals(privateDirectory))
                throw new IOException("Diagnostic directory is not private");
            Path output = folder.resolve(id + ".json");
            try (var file = Files.newByteChannel(output,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer data = ByteBuffer.wrap(json.writeValueAsBytes(record));
                while (data.hasRemaining()) file.write(data);
            }
            log.info("Agent invalid decision diagnostic saved id={}", id);
        } catch (Exception failure) {
            // Diagnostic storage must not interfere with approval/state recovery, nor expose paths or raw errors.
            log.warn("Agent invalid decision diagnostic writeFailed id={} kind={}", id, failure.getClass().getSimpleName());
        }
    }

    private static String bounded(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        int end = limit;
        if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }

    private static String escaped(String encodedJson) {
        return encodedJson.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
