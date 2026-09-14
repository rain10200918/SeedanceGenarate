package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSnapshotProjectionTest {
    // Reuse the existing local H2 schema fixture without changing shared setup or running its tests here.
    final AgentWorkspaceIntegrationTest f=new AgentWorkspaceIntegrationTest();
    AgentStore store; AgentPlanStore plans; AgentApplication app;
    @BeforeEach void setup() throws Exception {
        f.setup();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V53__agent_video_prompt_repair.sql")).execute(f.db.getDataSource());
        store=spy(f.store); plans=spy(store.plans());
        ReflectionTestUtils.setField(store,"plans",plans);
        app=new AgentApplication(store,f.tx,f.jobs,f.models,f.json,mock(AgentApprovalApplication.class),
                f.approvals,mock(AgentImageInputs.class));
    }

    // 【测什么】有持久计划的snapshot只执行一次workspace/project，完整响应与旧的再次投影路径相同。
    // 【怎么算红】snapshot恢复artifacts(s)后workspace/project次数变2；改变返回内容则完整record比较失败。
    @Test void snapshotReusesProjectionAndMatchesLegacyResponse() {
        var plan=f.plan();
        f.apply("adopt",1,"ADOPT_PLAN",new ArtifactRef(plan.id(),plan.version(),null));
        doAnswer(call->new AgentStore(f.db,f.json).artifacts(call.getArgument(0)))
                .when(store).artifacts(any(),any(ObjectNode.class));
        var legacy=app.snapshot(1,f.id);
        doCallRealMethod().when(store).artifacts(any(),any(ObjectNode.class));
        clearInvocations(store,plans);
        var actual=app.snapshot(1,f.id);
        assertEquals(legacy,actual);
        verify(store).workspace(any()); verify(plans).project(any(),any());
        verify(store,never()).artifacts(any());
        verify(store).artifacts(any(),same((ObjectNode)actual.state().workspace()));
    }

    // 【测什么】两次请求间选择和作品更新立即可见；旧artifacts入口也每次重新投影，不缓存跨请求。
    // 【怎么算红】把workspace或作品列表存成字段复用，第二次选择/作品和调用次数断言失败。
    @Test void subsequentRequestsAndDefaultEntryRemainFresh() {
        var s=f.session();
        var first=store.artifact(s,"first",null,"SCRIPT","first","one");
        var w=f.json.createObjectNode().put("version",1);
        w.set("selection",f.json.valueToTree(new ArtifactRef(first.id(),1,null)));
        store.saveWorkspace(s,w);
        assertEquals(first.id(),app.snapshot(1,f.id).artifacts().get(0).id());
        var second=store.artifact(s,"second",null,"SCRIPT","second","two");
        w.put("version",2).set("selection",f.json.valueToTree(new ArtifactRef(second.id(),1,null)));
        store.saveWorkspace(s,w);
        clearInvocations(store,plans);
        var next=app.snapshot(1,f.id);
        assertEquals(2,next.state().workspace().path("version").asInt());
        assertEquals(second.id(),next.artifacts().get(0).id());
        verify(store).workspace(any()); verify(plans).project(any(),any());
        clearInvocations(store,plans);
        assertEquals(next.artifacts(),store.artifacts(s));
        assertEquals(next.artifacts(),store.artifacts(s));
        verify(store,times(2)).workspace(s); verify(plans,times(2)).project(eq(s),any());
    }
    // 【测什么】暂停且含可修复错误时，错误版本校验复用同次投影，过期错误仍隐藏。
    // 【怎么算红】错误读取另查workspace或移除版本校验，次数/错误内容断言失败。
    @Test void suspendedErrorReusesSnapshotWithoutServingStaleRepair() {
        var s=f.session();store.saveWorkspace(s,f.json.createObjectNode().put("version",4));
        var call=f.call("storyboard-generation");
        f.db.update("UPDATE agent_skill_call SET context_json=? WHERE id=?","{\"actionableError\":{\"workspaceVersion\":4,\"code\":\"TEST\"}}",call.id());
        store.status(call.turnId(),"SUSPENDED","test");clearInvocations(store,plans);
        var snapshot=app.snapshot(1,f.id);
        verify(store).workspace(any());verify(plans).project(any(),any());
        assertEquals("TEST",snapshot.state().actionableError().path("code").asText());
        store.saveWorkspace(s,f.json.createObjectNode().put("version",5));clearInvocations(store,plans);
        assertNull(app.snapshot(1,f.id).state().actionableError());verify(store).workspace(any());
        assertNull(store.actionableError(s,store.turn(call.turnId())));
    }

    // 【测什么】planRef、selection、当前幕冻结来源优先且去重，最近作品补齐最多20；传入投影不被修改。
    // 【怎么算红】交换pin顺序、删除冻结来源pin/去重/limit或改写投影，列表或JSON比较失败。
    @Test void pinsPriorityDeduplicationAndLimitArePreserved() {
        var s=f.session();
        var pins=java.util.stream.IntStream.range(0,3).mapToObj(i->store.artifact(s,"pin"+i,null,"SCRIPT","pin","text")).toList();
        for(int i=0;i<25;i++)store.artifact(s,"later"+i,null,"SCRIPT","recent","text");
        var w=f.json.createObjectNode().put("version",1).put("currentStepId","scene");
        w.set("planRef",f.json.valueToTree(new ArtifactRef(pins.get(0).id(),1,null)));
        w.set("selection",f.json.valueToTree(new ArtifactRef(pins.get(1).id(),1,null)));
        w.putArray("steps").addObject().put("id","scene").put("scope","STORYBOARD_SCENES")
                .putArray("scenes").addObject().set("sourceRef",f.json.valueToTree(new ArtifactRef(pins.get(2).id(),1,null)));
        var before=w.deepCopy();
        var result=store.artifacts(s,w);
        assertEquals(pins,result.subList(0,3)); assertEquals(20,result.size()); assertEquals(before,w);
        var recent=store.artifacts(s,f.json.createObjectNode());
        assertEquals(recent.subList(0,17),result.subList(3,20));
        w.set("selection",w.get("planRef"));
        result=store.artifacts(s,w);
        assertEquals(List.of(pins.get(0),pins.get(2)),result.subList(0,2));
        assertEquals(20,result.size()); assertEquals(20,result.stream().map(a->a.id()+":"+a.version()).distinct().count());
    }

    // 【测什么】不存在的pin仍404；其他属主的会话及作品不可通过传入投影读取；无作品返回空列表。
    // 【怎么算红】漏session/user SQL条件、忽略缺失pin或跳过owned检查，会泄漏作品或不再报404。
    @Test void missingPinsAndOwnershipRemainFailClosed() {
        var s=f.session();
        assertTrue(store.artifacts(s,f.json.createObjectNode()).isEmpty());
        long other=store.create(2,"other");
        var foreign=store.artifact(store.owned(other,2,false),"foreign",null,"SCRIPT","private","secret");
        for(String key:List.of("planRef","selection")) {
            var w=f.json.createObjectNode();
            w.set(key,f.json.valueToTree(new ArtifactRef(foreign.id(),1,null)));
            assertEquals(404,assertThrows(BusinessException.class,()->store.artifacts(s,w)).getCode());
        }
        var missing=f.json.createObjectNode().put("currentStepId","scene");
        missing.putArray("steps").addObject().put("id","scene").put("scope","STORYBOARD_SCENES")
                .putArray("scenes").addObject().set("sourceRef",f.json.valueToTree(new ArtifactRef("missing",1,null)));
        assertEquals(404,assertThrows(BusinessException.class,()->store.artifacts(s,missing)).getCode());
        assertTrue(store.artifacts(s,f.json.createObjectNode()).isEmpty());
        clearInvocations(store);
        assertEquals(404,assertThrows(BusinessException.class,()->app.snapshot(2,f.id)).getCode());
        verify(store,never()).workspace(any());
    }

    // 【测什么】snapshot先过期问题并重读属主会话，再投影作品；响应含FAILED与过期消息，重复读取不重复副作用。
    // 【怎么算红】把workspace提前到expireInteraction之前、漏会话重读或重复过期消息，顺序/响应断言失败。
    @Test void expirationStillPrecedesProjection() {
        var s=f.session(); store.saveWorkspace(s,f.json.createObjectNode().put("version",1));
        String turn=store.newTurn(s,"local","question");
        store.status(turn,"WAITING_USER",null);
        String question=store.newInteraction(store.turn(turn),"choose",f.json.createArrayNode());
        f.db.update("UPDATE agent_interaction SET expires_at=TIMESTAMPADD(DAY,-1,NOW()) WHERE id=?",question);
        clearInvocations(store,plans);
        var snapshot=app.snapshot(1,f.id);
        var order=inOrder(store);
        order.verify(store).owned(f.id,1,true);
        order.verify(store).expireInteraction(question);
        order.verify(store).owned(f.id,1,false);
        order.verify(store).workspace(any());
        verify(store).workspace(any()); verify(plans).project(any(),any());
        assertEquals("FAILED",snapshot.turn().status());
        assertEquals("EXPIRED",store.interaction(question).status());
        assertTrue(snapshot.messages().toString().contains("这个问题已过期"));
        assertEquals(snapshot,app.snapshot(1,f.id));
    }
}
