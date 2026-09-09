package org.example.seedancegenarate.agent.model;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class AgentDecisionDiagnosticsTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AgentDecisionDiagnostics.class);
    private ListAppender<ILoggingEvent> logs;
    private boolean additive;

    @BeforeEach void capture() { additive = logger.isAdditive(); logger.setAdditive(false); logs = new ListAppender<>(); logs.start(); logger.addAppender(logs); }
    @AfterEach void detach() { logger.detachAppender(logs); logs.stop(); logger.setAdditive(additive); }

    // 【测什么】默认配置关闭，关闭时不产生诊断文件且日志不包含模型原文。
    // 【怎么算红】默认值变true或删除enabled守卫，文件/敏感日志断言失败。
    @Test void disabledIsDefaultAndCreatesNoFilesOrRawLogs() throws Exception {
        var constructor = AgentDecisionDiagnostics.class.getConstructors()[0];
        assertEquals("${agent.decision-diagnostics.enabled:false}", constructor.getParameters()[1].getAnnotation(Value.class).value());
        Path directory = temp.resolve("off");
        new AgentDecisionDiagnostics(json, false, directory.toString()).record("t", 1, 2, "own", "JSON_SYNTAX $", "PRIVATE_RAW");
        assertFalse(Files.exists(directory));
        assertFalse(messages().contains("PRIVATE_RAW"));
    }

    // 【测什么】开发显式启用后原文精确保存，日志JSON转义可还原，目录和文件限制访问。
    // 【怎么算红】删除raw保存/转义或改权限为宽泛默认，数据/权限断言失败。
    @Test void enabledPreservesRawAndEscapesLogWithPrivatePermissions() throws Exception {
        Path directory = temp.resolve("on");
        String raw = "{\"type\":\"WAIT\"}\nline\t\"quoted\"\r\n伪日志\u2028end";
        json.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        new AgentDecisionDiagnostics(json, true, directory.toString()).record("turn", 5, 2, "own", "ACTION_NOT_ALLOWED $.type", raw);
        Path file = onlyFile(directory);
        var saved = json.readTree(Files.readString(file));
        assertEquals(raw, saved.path("raw").textValue());
        assertTrue(saved.path("rawCaptured").asBoolean());
        assertEquals(raw.length(), saved.path("originalChars").asInt());
        assertFalse(saved.path("truncated").asBoolean());
        assertEquals(5, saved.path("epoch").asLong());
        assertEquals(2, saved.path("step").asInt());
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        String rawLog = logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(v -> v.contains("rawJson=")).findFirst().orElseThrow();
        assertFalse(rawLog.contains("\n")); assertFalse(rawLog.contains("\r")); assertFalse(rawLog.contains("\u2028"));
        assertEquals(raw, json.readTree(rawLog.substring(rawLog.indexOf("rawJson=") + 8)).textValue());
        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage).noneMatch(v -> v.contains("\n") || v.contains("\r")));
        assertTrue(json.isEnabled(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT));
    }

    // 【测什么】没拿到原始响应时明确标记未捕获，不把Decision重新序列化成伪原文。
    // 【怎么算红】把null变为空文本或默认标记captured=true时失败。
    @Test void nullRawIsExplicitlyNotCaptured() throws Exception {
        Path directory = temp.resolve("null");
        new AgentDecisionDiagnostics(json, true, directory.toString()).record("t", 1, 0, "own", "reason", null);
        var saved = json.readTree(Files.readString(onlyFile(directory)));
        assertFalse(saved.path("rawCaptured").asBoolean());
        assertTrue(saved.path("raw").isNull());
        assertTrue(saved.path("originalChars").isNull());
    }

    // 【测什么】诊断写入失败不影响Runtime，不能修改已有宽权限目录或泄漏异常正文。
    // 【怎么算红】移除IO兜底或chmod既有目录、直接log异常时断言失败。
    @Test void ioFailuresAndUnsafeExistingDirectoryDoNotEscape() throws Exception {
        Path occupied = temp.resolve("PRIVATE_PATH"); Files.writeString(occupied, "not a directory");
        assertDoesNotThrow(() -> new AgentDecisionDiagnostics(json, true, occupied.toString()).record("t", 1, 0, "own", "reason", null));
        assertTrue(messages().contains("writeFailed"));
        assertFalse(messages().contains("PRIVATE_PATH"));
        Path wide = temp.resolve("wide"); Files.createDirectory(wide);
        Files.setPosixFilePermissions(wide, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertDoesNotThrow(() -> new AgentDecisionDiagnostics(json, true, wide.toString()).record("t", 1, 0, "own", "reason", null));
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(wide));
        try (var files = Files.list(wide)) { assertEquals(0, files.count()); }
        assertTrue(logs.list.stream().allMatch(v -> v.getThrowableProxy() == null));
    }

    // 【测什么】原文上限64Ki字符有明确截断标记，边界不会截半个Unicode代理对。
    // 【怎么算红】去掉raw长度上限、原长度或代理对守卫，保存长度/末字符断言失败。
    @Test void boundsRawWithoutSplittingSurrogate() throws Exception {
        Path directory = temp.resolve("large"); String raw = "x".repeat(65535) + "😀tail";
        new AgentDecisionDiagnostics(json, true, directory.toString()).record("t", 1, 0, "own", "reason", raw);
        var saved = json.readTree(Files.readString(onlyFile(directory)));
        assertEquals("x".repeat(65535), saved.path("raw").asText());
        assertTrue(saved.path("truncated").asBoolean());
        assertEquals(raw.length(), saved.path("originalChars").asInt());
    }

    // 【测什么】符号链接目录拒绝写入，通道/原因中的换行只能成为JSON转义不能伪造日志行。
    // 【怎么算红】去掉NOFOLLOW目录检查或把channel拼进普通日志，目标文件或换行断言失败。
    @Test void symlinkIsRefusedAndMetadataIsEscaped() throws Exception {
        Path actual = temp.resolve("actual"); Files.createDirectory(actual, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path linked = temp.resolve("linked"); Files.createSymbolicLink(linked, actual);
        assertDoesNotThrow(() -> new AgentDecisionDiagnostics(json, true, linked.toString()).record("t", 1, 0, "channel\nINJECTED", "reason\r\nINJECTED", null));
        try (var files = Files.list(actual)) { assertEquals(0, files.count()); }
        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage).noneMatch(v -> v.contains("\n") || v.contains("\r")));
        assertTrue(messages().contains("\\nINJECTED"));
    }

    private Path onlyFile(Path directory) throws Exception {
        try (var files = Files.list(directory)) { var all = files.toList(); assertEquals(1, all.size()); return all.get(0); }
    }
    private String messages() { return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n")); }
}
