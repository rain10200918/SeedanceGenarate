package org.example.seedancegenarate.agent.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Local opt-in evidence only: never an artifact, prompt input, or approval source. */
@Component
public class AgentVideoPromptDiagnostics {
    private static final Logger log=LoggerFactory.getLogger(AgentVideoPromptDiagnostics.class);
    private final ObjectMapper json;
    private final boolean enabled;
    private final String directory;
    public AgentVideoPromptDiagnostics(ObjectMapper json,
            @Value("${agent.video-prompt-diagnostics.enabled:false}") boolean enabled,
            @Value("${agent.video-prompt-diagnostics.directory:./.agent-diagnostics}") String directory) {
        this.json=json.copy().disable(SerializationFeature.INDENT_OUTPUT);this.enabled=enabled;this.directory=directory;
    }
    public String record(AgentContext context,AgentVideoPromptPreparation.Scene scene,VideoPreparationException failure,String raw) {
        String id=UUID.randomUUID().toString();
        try {
            var record=json.createObjectNode().put("id",id).put("createdAt",Instant.now().toString())
                    .put("turnId",bounded(context.turnId(),128)).put("step",context.step()).put("sceneOrdinal",scene.ordinal())
                    .put("channel",bounded(context.channel(),128)).put("generationModel",bounded(scene.quote().modelId(),128))
                    .put("templateId",bounded(scene.templateId(),256)).put("templateHash",HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(scene.guide().getBytes(StandardCharsets.UTF_8))))
                    .put("validationCode",failure.validationCode()).put("validationDetail",failure.validationDetail()).put("enabled",enabled);
            log.info("Agent video prompt diagnostic id={} metadataJson={}",id,escaped(json.writeValueAsString(record)));
            if(!enabled)return id;
            String captured=bounded(raw,65536);
            record.put("rawCaptured",raw!=null).put("raw",captured).put("truncated",raw!=null&&captured.length()!=raw.length());
            if(raw==null)record.putNull("originalChars");else record.put("originalChars",raw.length());
            log.info("Agent video prompt development raw id={} rawJson={}",id,escaped(json.writeValueAsString(captured)));
            Path folder=Path.of(directory);var permissions=PosixFilePermissions.fromString("rwx------");
            Files.createDirectories(folder,PosixFilePermissions.asFileAttribute(permissions));
            if(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(folder,LinkOption.NOFOLLOW_LINKS).equals(permissions))
                throw new java.io.IOException("Diagnostic directory is not private");
            try(var file=Files.newByteChannel(folder.resolve(id+".json"),
                    Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                var bytes=ByteBuffer.wrap(json.writeValueAsBytes(record));while(bytes.hasRemaining())file.write(bytes);
            }
            log.info("Agent video prompt diagnostic saved id={}",id);
        } catch(Exception error) {log.warn("Agent video prompt diagnostic writeFailed id={} kind={}",id,error.getClass().getSimpleName());}
        return id;
    }
    private static String bounded(String value,int limit) {
        if(value==null||value.length()<=limit)return value;
        int end=limit;if(Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;
        return value.substring(0,end);
    }
    private static String escaped(String value){return value.replace("\u2028","\\u2028").replace("\u2029","\\u2029");}
}
