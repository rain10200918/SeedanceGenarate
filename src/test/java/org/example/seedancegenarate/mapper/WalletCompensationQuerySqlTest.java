package org.example.seedancegenarate.mapper;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账务补偿候选查询的 SQL 生成守卫（不连库，只让 MyBatis 把 SQL 拼出来看）。
 * <p>
 * 这条查询里有个动态 {@code <if>}：隔离集合为空时整段 {@code NOT IN} 必须不拼 ——
 * {@code NOT IN ()} 是 SQL 语法错，会把整个账务补偿分支打瘫。
 * 光读注解看不出来拼没拼对，所以这里真的生成一次。
 */
class WalletCompensationQuerySqlTest {

    private static final String STATEMENT =
            VideoTaskMapper.class.getName() + ".findTerminalMissingWalletTransitionSince";

    private MappedStatement statement;

    @BeforeEach
    void setUp() {
        Configuration configuration = new Configuration();
        configuration.addMapper(VideoTaskMapper.class);
        statement = configuration.getMappedStatement(STATEMENT);
    }

    private BoundSql sqlFor(List<Long> excludeIds) {
        Map<String, Object> params = new HashMap<>();
        params.put("since", LocalDateTime.now().minusDays(7));
        params.put("limit", 100);
        params.put("excludeIds", excludeIds);
        return statement.getBoundSql(params);
    }

    @Test
    void emptyExcludeSetGeneratesNoNotInClause() {
        // 【测什么】排除集合为空时不拼 NOT IN
        // 【怎么算红】拼出 NOT IN () —— MySQL 直接语法错，账务补偿分支每 30 秒抛一次异常、
        //            一条退款都补不了，而日志上只是一句「拉取终态账务补偿任务失败」
        String sql = sqlFor(List.of()).getSql();

        assertFalse(sql.contains("NOT IN"), "空集合不该拼 NOT IN，实际 SQL:\n" + sql);
        assertTrue(sql.contains("ORDER BY v.id ASC"), "其余部分要照旧");
    }

    @Test
    void nullExcludeSetGeneratesNoNotInClause() {
        // 【测什么】排除集合为 null（调用方漏传）时同样不拼
        // 【怎么算红】只判空集合不判 null —— NullPointerException 或 NOT IN ()，同上
        String sql = sqlFor(null).getSql();

        assertFalse(sql.contains("NOT IN"), "null 不该拼 NOT IN，实际 SQL:\n" + sql);
    }

    @Test
    void nonEmptyExcludeSetGeneratesOnePlaceholderPerId() {
        // 【测什么】有隔离行时拼出 NOT IN (?, ?)，且占位符数量与 id 数量一致
        // 【怎么算红】foreach 写错（少一个占位符 / 参数错位）—— 排除的是错误的 id，
        //            可能反而把正常任务排除掉，它的退款永远补不上
        BoundSql bound = sqlFor(List.of(764L, 765L));
        String sql = bound.getSql();

        assertTrue(sql.contains("NOT IN"), "实际 SQL:\n" + sql);
        assertEquals(2, sql.split("\\?", -1).length - 1 - countBaseParams(),
                "NOT IN 里应有 2 个占位符，实际 SQL:\n" + sql);
        assertEquals(764L, bound.getParameterObject() == null ? null
                : ((Map<?, ?>) bound.getParameterObject()).get("excludeIds") instanceof List<?> l
                ? l.get(0) : null);
    }

    /** since 与 limit 各占一个 ? */
    private int countBaseParams() {
        return 2;
    }
}
