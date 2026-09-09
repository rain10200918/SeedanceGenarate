package org.example.seedancegenarate.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentVideoPromptDiagnosticsTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    final Logger logger=(Logger)LoggerFactory.getLogger(AgentVideoPromptDiagnostics.class);
    ListAppender<ILoggingEvent> logs;boolean additive;
    @BeforeEach void capture(){additive=logger.isAdditive();logger.setAdditive(false);logs=new ListAppender<>();logs.start();logger.addAppender(logs);}
    @AfterEach void detach(){logger.detachAppender(logs);logs.stop();logger.setAdditive(additive);}
    String record(boolean enabled,Path directory,String raw) {
        var context=new AgentContext(1L,"session","turn","channel\nINJECTED","goal",null,List.of(),List.of(),3);
        var quote=new TaskQuote("comfyui","minimax-h3-t2v-hd","model","VIDEO",json.createObjectNode(),BigDecimal.ONE,"CNY","AGENT");
        var scene=new AgentVideoPromptPreparation.Scene("c1",1,json.nullNode(),quote,json.createObjectNode(),"trusted guide","prompts/minimax-h3-t2v-hd.md");
        return new AgentVideoPromptDiagnostics(json,enabled,directory.toString()).record(context,scene,
                VideoPreparationException.invalid(VideoPreparationException.ValidationRule.JSON_INVALID,"$"),raw);
    }
    // 【测什么】默认关闭诊断时仅安全元数据入日志，无原文文件，也不能被通道换行伪造日志。
    // 【怎么算红】默认true、移除enabled或直接拼通道，会导致文件/原文/换行断言失败。
    @Test void defaultOffCannotLeakRawOrCreateFiles() {
        var constructor=AgentVideoPromptDiagnostics.class.getConstructors()[0];
        assertEquals("${agent.video-prompt-diagnostics.enabled:false}",constructor.getParameters()[1].getAnnotation(Value.class).value());
        Path directory=temp.resolve("off");assertNotNull(record(false,directory,"PRIVATE_RAW"));assertFalse(Files.exists(directory));
        assertTrue(logs.list.stream().noneMatch(e->e.getFormattedMessage().contains("PRIVATE_RAW")||e.getFormattedMessage().contains("\n")));
    }
    // 【测什么】显式开发开关保存原始响应和真实模板身份，文件私有、日志转义、原文长度有限。
    // 【怎么算红】去掉私有权限/截断/模板身份或JSON转义，任一对应断言变红。
    @Test void enabledCapturesBoundedEvidenceWithPrivatePermissions() throws Exception {
        Path directory=temp.resolve("on");String raw="x".repeat(65535)+"😀\nPRIVATE_RAW";String id=record(true,directory,raw);
        Path file=directory.resolve(id+".json");var saved=json.readTree(Files.readString(file));
        assertEquals("x".repeat(65535),saved.path("raw").asText());assertTrue(saved.path("truncated").asBoolean());
        assertEquals(raw.length(),saved.path("originalChars").asInt());assertEquals("prompts/minimax-h3-t2v-hd.md",saved.path("templateId").asText());
        assertEquals(64,saved.path("templateHash").asText().length());assertEquals("JSON_INVALID",saved.path("validationCode").asText());
        assertFalse(saved.has("prompt"));assertFalse(saved.has("guide"));
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(directory));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
        assertTrue(logs.list.stream().noneMatch(e->e.getFormattedMessage().contains("\n")||e.getFormattedMessage().contains("\r")));
    }
    // 【测什么】目录权限不安全或符号链接时诊断失败不得影响执行，不修改现有目录权限。
    // 【怎么算红】移除权限/NOFOLLOW守卫或IO兜底，文件数量/权限/不抛错断言失败。
    @Test void unsafeDirectoryOrSymlinkNeverAffectsExecution() throws Exception {
        Path wide=temp.resolve("wide");Files.createDirectory(wide);Files.setPosixFilePermissions(wide,PosixFilePermissions.fromString("rwxr-xr-x"));
        assertDoesNotThrow(()->record(true,wide,"PRIVATE_RAW"));
        try(var files=Files.list(wide)){assertEquals(0,files.count());}
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"),Files.getPosixFilePermissions(wide));
        Path linked=temp.resolve("linked");Files.createSymbolicLink(linked,wide);assertDoesNotThrow(()->record(true,linked,null));
        assertTrue(logs.list.stream().anyMatch(e->e.getFormattedMessage().contains("writeFailed")));
        assertTrue(logs.list.stream().allMatch(e->e.getThrowableProxy()==null));
    }
}
