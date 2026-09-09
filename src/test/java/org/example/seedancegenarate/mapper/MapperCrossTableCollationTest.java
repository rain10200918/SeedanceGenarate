package org.example.seedancegenarate.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【测什么】所有带 JOIN 的注解 SQL 里，两张表的<b>字符串列直接相等比较</b>必须带显式 COLLATE。
 * baseline 进来的库老表是 utf8mb4_0900_ai_ci、迁移新建的表是 utf8mb4_unicode_ci，`v.provider = a.provider`
 * 这种跨表列比较会报 1267「Illegal mix of collations」——2026-09-03 23:41 所有生成提交卡死就是它。
 * 【怎么算红】把 VideoTaskMapper.markAttemptSubmitting 里的 `COLLATE utf8mb4_bin` 删掉，这条必须变红。
 */
class MapperCrossTableCollationTest {

    /** `x.col = y.col`（两边都是 别名.列，且不是 id 类列），后面没有紧跟 COLLATE */
    private static final Pattern COLUMN_EQ_COLUMN = Pattern.compile(
            "\\b([a-z])\\.([a-z_]+)\\s*=\\s*([a-z])\\.([a-z_]+)(\\s+COLLATE\\s+\\w+)?", Pattern.CASE_INSENSITIVE);

    private static final Class<?>[] MAPPERS = {
            VideoTaskMapper.class, GenerationAttemptMapper.class, AsyncJobMapper.class,
            WalletMapper.class, CanvasNodeMapper.class, ConversationMessageMapper.class, ConversationMapper.class,
    };

    @Test
    void crossTableStringComparisonsCarryAnExplicitCollation() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> mapper : MAPPERS) {
            for (Method method : mapper.getDeclaredMethods()) {
                String sql = sqlOf(method);
                if (sql == null || !sql.toUpperCase().contains("JOIN")) {
                    continue;
                }
                Matcher m = COLUMN_EQ_COLUMN.matcher(sql);
                while (m.find()) {
                    String left = m.group(2).toLowerCase();
                    String right = m.group(4).toLowerCase();
                    if (isIdColumn(left) || isIdColumn(right) || m.group(5) != null) {
                        continue;
                    }
                    offenders.add(mapper.getSimpleName() + "." + method.getName() + ": " + m.group().trim());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "跨表字符串列比较缺显式 COLLATE（baseline 库会报 1267）: " + offenders);
    }

    /** id / *_id 是 BIGINT，没有排序规则问题 */
    private static boolean isIdColumn(String column) {
        return column.equals("id") || column.endsWith("_id");
    }

    private static String sqlOf(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select != null) {
            return String.join(" ", select.value());
        }
        Update update = method.getAnnotation(Update.class);
        if (update != null) {
            return String.join(" ", update.value());
        }
        Delete delete = method.getAnnotation(Delete.class);
        return delete == null ? null : String.join(" ", delete.value());
    }
}
