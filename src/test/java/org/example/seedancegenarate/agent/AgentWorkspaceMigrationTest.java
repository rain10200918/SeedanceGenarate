package org.example.seedancegenarate.agent;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AgentWorkspaceMigrationTest {
    // 【测什么】计划身份、准确作品来源和Skill冻结上下文由增量迁移持久化。
    // 【怎么算红】删去V36或任一关联列时，本测试失败。
    @Test void workspaceAndLineageAreDurable() throws Exception {
        var resource=getClass().getResourceAsStream("/db/migration/V36__agent_workspace.sql");
        assertNotNull(resource,"工作区必须持久化");
        String sql=new String(resource.readAllBytes(),StandardCharsets.UTF_8);
        for(String column:new String[]{"workspace_json","context_json","data_json","source_ref_json","plan_ref_json","step_id"})
            assertTrue(sql.contains(column),column);
    }
}
