package org.example.seedancegenarate.agent;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AgentApprovalMigrationTest {
    // 【测什么】费用确认与稳定提交身份在MySQL，不能仅存在页面或Redis。
    // 【怎么算红】删除V35或request_id唯一约束，本测试失败。
    @Test void approvalPersistsQuoteAndSubmissionIdentity() throws Exception {
        var resource=getClass().getResourceAsStream("/db/migration/V35__agent_approval.sql");
        assertNotNull(resource,"付费确认必须持久化");
        String sql=new String(resource.readAllBytes(),StandardCharsets.UTF_8);
        for(String field:new String[]{"agent_approval","quote_json","request_id","task_id","expires_at","UNIQUE KEY uk_agent_approval_request"}) assertTrue(sql.contains(field),field);
    }
}
