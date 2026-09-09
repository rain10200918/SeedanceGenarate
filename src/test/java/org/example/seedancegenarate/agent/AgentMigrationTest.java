package org.example.seedancegenarate.agent;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AgentMigrationTest {
    // 【测什么】跨重启需要的运行、交互和作品身份必须进入数据库。
    // 【怎么算红】删掉 V34 或去掉任意关键表/幂等约束，断言失败。
    @Test void durableIdentitiesExist() throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/V34__agent_runtime.sql")) {
            assertNotNull(in, "Agent 不能只用内存运行");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String name : new String[]{"agent_session", "agent_turn", "agent_decision", "agent_skill_call",
                    "agent_interaction", "agent_artifact_version", "UNIQUE KEY uk_agent_artifact_version"}) {
                assertTrue(sql.contains(name), name);
            }
        }
    }

    // 【测什么】Turn Yield 的系统触发、父子身份、顺序和原因必须持久化，且同一父Turn只能有一个续接Turn。
    // 【怎么算红】删掉V43、trigger/parent/sequence/reason任一列或两个唯一键，这条必须变红。
    @Test void turnYieldIdentityIsDurableAndUnique() throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/V43__agent_turn_lifecycle.sql")) {
            assertNotNull(in, "Turn Yield 不能只靠内存续接");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String name : new String[]{"trigger_type", "parent_turn_id", "turn_seq", "yield_reason",
                    "uk_agent_turn_session_seq", "uk_agent_turn_parent"}) {
                assertTrue(sql.contains(name), name);
            }
            assertTrue(sql.contains("DEFAULT 'USER_MESSAGE'"), "存量/普通Turn默认是用户触发");
        }
    }
}
