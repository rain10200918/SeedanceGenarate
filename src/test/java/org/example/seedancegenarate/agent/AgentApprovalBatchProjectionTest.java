package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.application.AgentApprovalApplication;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentApprovalBatchProjectionTest {
    final AgentMessageBatchProjectionTest f=new AgentMessageBatchProjectionTest();
    final AgentGenerationGateway gateway=mock(AgentGenerationGateway.class);
    AgentApprovalStore approvals; AgentApprovalApplication app;
    @BeforeEach void setup() throws Exception {
        f.setup();approvals=new AgentApprovalStore((JdbcTemplate)ReflectionTestUtils.getField(f.store,"jdbc"),f.store);
        app=new AgentApprovalApplication(f.store,approvals,f.f.tx,f.f.jobs,gateway,f.f.json);
    }
    String approval(int n) {
        f.f.db.update("INSERT INTO agent_skill_call(id,turn_id,skill_id,skill_version,input_json,status,epoch,step_no,context_json) VALUES(?,?,'image-generation','1','{}','READY',1,?,?)",
                "call"+n,f.turn,n,"{\"version\":1,\"sourceRef\":{\"artifactId\":\"source"+n+"\",\"version\":1}}");
        var quote=new TaskQuote("p","m","model","IMAGE",f.f.json.createObjectNode().put("prompt","prompt"+n),BigDecimal.ONE,"CNY");
        return approvals.create(f.session,f.f.store.turn(f.turn),f.f.store.call("call"+n),quote);
    }
    AgentViews.Message message(String id,String type) {
        var parts=f.f.json.createArrayNode();parts.addObject().put("type",type).put("approvalId",id);
        return new AgentViews.Message(id,1,"ASSISTANT",parts,"time",null);
    }
    // 【测什么】100张不同审批卡只查一次审批和一次context，提示词/精确source不变。
    // 【怎么算红】恢复逐卡get/callContext会超过2次SQL，删context投影会丢source。
    @Test void hundredApprovalsUseTwoQueries() {
        var messages=new ArrayList<AgentViews.Message>();for(int i=0;i<100;i++)messages.add(message(approval(i),"approval"));
        f.queries.set(0);app.project(f.session,messages);assertEquals(2,f.queries.get());
        for(int i=0;i<100;i++) {
            var p=messages.get(i).parts().get(0);assertEquals("PENDING",p.path("status").asText());
            assertEquals("prompt"+i,p.path("prompt").asText());assertEquals("source"+i,p.path("sourceRef").path("artifactId").asText());
        }
    }
    // 【测什么】同任务重复卡片每请求一次媒体读取，下一请求重新读取屏蔽和不可用状态。
    // 【怎么算红】不去重会调用多次gateway.read；跨请求缓存会沿用旧URL。
    @Test void taskReadsAreDeduplicatedOnlyWithinRequest() {
        String id=approval(0);approvals.bind(id,"task");
        when(gateway.read(1,"task")).thenReturn(new AgentGenerationGateway.TaskView("task","SUCCESS","IMAGE","/media",false,false,null));
        var messages=List.of(message(id,"task"),message(id,"task"));app.project(f.session,messages);
        verify(gateway).read(1,"task");assertEquals("/media",messages.get(1).parts().get(0).path("mediaPath").asText());
        when(gateway.read(1,"task")).thenThrow(BusinessException.forbidden("blocked"));app.project(f.session,messages);
        verify(gateway,times(2)).read(1,"task");assertTrue(messages.get(0).parts().get(0).path("mediaPath").isNull());
        assertEquals("UNAVAILABLE",messages.get(0).parts().get(0).path("status").asText());
    }
    // 【测什么】缺失/其他会话审批不透出quote和context，空消息不查询，数据库异常不吞掉。
    // 【怎么算红】去掉session条件会返回其他会话审批；吞异常则无法触发故障断言。
    @Test void scopeEmptyAndFailureRemainClosed() {
        f.queries.set(0);app.project(f.session,List.of());assertEquals(0,f.queries.get());
        String id=approval(0);var other=f.f.store.owned(f.f.store.create(2,"other"),2,false);
        var messages=List.of(message(id,"approval"),message("missing","task"));app.project(other,messages);
        for(var m:messages)assertEquals("作品不可用",m.parts().get(0).path("text").asText());
        f.f.db.execute("DROP TABLE agent_approval");
        assertThrows(org.springframework.dao.DataAccessException.class,()->app.project(f.session,List.of(message(id,"approval"))));
    }
}
