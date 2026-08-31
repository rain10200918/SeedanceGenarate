package org.example.seedancegenarate.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移文件里不许出现 Flyway 占位符语法（美元 + 花括号），<b>注释里也不行</b>。
 *
 * <h3>为什么值得为它写一条测试</h3>
 * 这个错<b>只在真正启动时才炸</b>，而且炸得很彻底 —— Flyway 在解析阶段就抛
 * {@code Unable to parse statement ... No value provided for placeholder}，
 * {@code flywayInitializer} 建不起来，整条 bean 链跟着塌，应用根本起不来。
 * 单元测试、编译、全量回归<b>一条都覆盖不到</b>：迁移文件对 JVM 来说只是一个资源文件。
 * <p>
 * 2026-08-28 真实踩中：V25 的注释里写了一句「yaml 里的地址是 美元花括号 COMFYUI_NODE0_URL
 * 冒号默认值 这种形式」用来解释<b>为什么不能在 SQL 里写死地址</b>，
 * 结果 Flyway 把这句解释本身当成了要替换的占位符。
 * <p>
 * 反直觉的点在于：绝大多数模板引擎只处理"代码"不处理注释，而 Flyway 是纯文本替换。
 */
class MigrationPlaceholderTest {

    /** Flyway 默认占位符：美元 + 花括号。这里不写字面量，免得这个文件自己成为下一个受害者 */
    private static final Pattern PLACEHOLDER =
            Pattern.compile(Pattern.quote("$") + "\\{[^}]*}");

    @Test
    void noMigrationFileContainsFlywayPlaceholderSyntax() throws IOException {
        // 【测什么】db/migration 下的每个 .sql 文件里都没有 美元+花括号
        // 【怎么算红】任何人（包括在注释里举例说明"配置长什么样"的时候）写了这个语法 ——
        //          应用下次启动直接起不来，而 CI 上的全量回归会全绿，
        //          因为没有任何单测会去读迁移文件
        Path dir = Paths.get("src/main/resources/db/migration");
        assertTrue(Files.isDirectory(dir), "迁移目录不在了？路径=" + dir.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = PLACEHOLDER.matcher(content);
                while (m.find()) {
                    int line = (int) content.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add(file.getFileName() + ":" + line + " -> " + m.group());
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "迁移文件里不许有 Flyway 占位符语法（注释里也不行，它会让应用启动即挂）:\n  "
                        + String.join("\n  ", offenders));
    }
}
