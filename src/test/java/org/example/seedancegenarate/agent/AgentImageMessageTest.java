package org.example.seedancegenarate.agent;

import org.junit.jupiter.api.*;
import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.agent.persistence.AgentApprovalStore;
import org.example.seedancegenarate.exception.BusinessException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentImageMessageTest {
    final AgentRuntimeIntegrationTest fixture=new AgentRuntimeIntegrationTest();
    AgentImageInputs images;
    @BeforeEach void setup() throws Exception {
        fixture.setup();images=mock(AgentImageInputs.class);
        when(images.resolve(eq(1L),anyList())).thenAnswer(a->((List<String>)a.getArgument(1)).stream().map(id->new AgentImageInputs.ImageRef(id,"https://media.example.test/"+id+".png")).toList());
        when(images.project(eq(1L),anyCollection())).thenAnswer(a->{
            var result=new java.util.HashMap<String,AgentImageInputs.ImageRef>();
            for(String id:(java.util.Collection<String>)a.getArgument(1)) {
                try {result.put(id,images.resolve(1,List.of(id)).get(0));}
                catch(BusinessException ignored) { }
            }
            return result;
        });
        when(fixture.models.defaultImageChannel()).thenReturn("vision");
        fixture.app=new AgentApplication(fixture.store,fixture.tx,fixture.jobs,fixture.models,fixture.json,
                mock(AgentApprovalApplication.class),new AgentApprovalStore(fixture.db,fixture.store),images);
    }
    // 【测什么】图片按ID随消息持久化，空值继承、空数组清空，同键重放不新建任务且改ID冲突。
    // 【怎么算红】从hash删imageAssetIds或省略workspace写入，重放/刷新断言失败。
    @Test void persistsReferencesAndPreservesRequestIdentity() {
        var request=new AgentApplication.Send("image-1","看看图片",null,List.of("2","1"));
        var sent=fixture.app.send(1,fixture.id,request);
        assertEquals("vision",sent.turn().channel());
        assertEquals("2",sent.messages().get(0).parts().get(1).path("assetId").asText());
        assertEquals(List.of("2","1"),fixture.json.convertValue(sent.state().workspace().path("imageAssetIds"),List.class));
        assertEquals("2",sent.state().workspace().path("imageInputs").get(0).path("assetId").asText());
        assertFalse(fixture.store.workspace(fixture.store.owned(fixture.id,1,false)).has("imageInputs"));
        long jobs=fixture.count("job_probe");fixture.app.send(1,fixture.id,request);assertEquals(jobs,fixture.count("job_probe"));
        assertThrows(BusinessException.class,()->fixture.app.send(1,fixture.id,new AgentApplication.Send("image-1","看看图片",null,List.of("1"))));
        fixture.app.cancel(1,fixture.id,sent.turn().id());
        var inherited=fixture.app.send(1,fixture.id,new AgentApplication.Send("image-2","继续分析",null));
        assertEquals(3,inherited.messages().get(inherited.messages().size()-1).parts().size());
        fixture.app.cancel(1,fixture.id,inherited.turn().id());
        var cleared=fixture.app.send(1,fixture.id,new AgentApplication.Send("image-3","聊点别的",null,List.of()));
        assertEquals(0,cleared.state().workspace().path("imageAssetIds").size());
        assertEquals("self-hosted",cleared.turn().channel());
    }
    // 【测什么】失效图片在刷新时不泄漏旧地址，模型不支持图片时不能创建消息或任务。
    // 【怎么算红】删除projectImages或发送前requireImageChannel，隐私/执行断言失败。
    @Test void rechecksProjectionAndRejectsUnsupportedChannel() {
        var sent=fixture.app.send(1,fixture.id,new AgentApplication.Send("image","看图",null,List.of("1")));
        when(images.resolve(1,List.of("1"))).thenThrow(BusinessException.forbidden("不可用"));
        var part=fixture.app.snapshot(1,fixture.id).messages().get(0).parts().get(1);
        assertFalse(part.has("url"));assertTrue(part.path("unavailable").asBoolean());
        var current=fixture.app.snapshot(1,fixture.id).state().workspace().path("imageInputs").get(0);
        assertFalse(current.has("url"));assertTrue(current.path("unavailable").asBoolean());
        fixture.app.cancel(1,fixture.id,sent.turn().id());
        doReturn(List.of(new AgentImageInputs.ImageRef("1","https://media.example.test/1.png"))).when(images).resolve(1,List.of("1"));
        doThrow(BusinessException.badRequest("不支持读图")).when(fixture.models).requireImageChannel("text-only");
        long before=fixture.count("conversation_message"),jobs=fixture.count("job_probe");
        assertThrows(BusinessException.class,()->fixture.app.send(1,fixture.id,new AgentApplication.Send("bad","看图","text-only",List.of("1"))));
        assertEquals(before,fixture.count("conversation_message"));assertEquals(jobs,fixture.count("job_probe"));
    }
    // 【测什么】事务内归属复核失败时图片上下文、消息和作业均回滚。
    // 【怎么算红】删除锁内images.resolve，第二次校验失败不再阻止消息落地。
    @Test void ownershipChangeBeforeCommitCannotPersistCommand() {
        when(images.resolve(1,List.of("1"))).thenReturn(List.of(new AgentImageInputs.ImageRef("1","https://media.example.test/1.png")))
                .thenThrow(BusinessException.forbidden("已移除"));
        assertThrows(BusinessException.class,()->fixture.app.send(1,fixture.id,new AgentApplication.Send("racy","看图",null,List.of("1"))));
        assertEquals(0,fixture.count("conversation_message"));assertEquals(0,fixture.count("job_probe"));
        assertFalse(fixture.store.workspace(fixture.store.owned(fixture.id,1,false)).has("imageAssetIds"));
    }
    // 【测什么】暂停轮次不能换图后继续旧冻结内容；同图明确恢复仍沿既有step推进。
    // 【怎么算红】删除SUSPENDED换图守卫，修改图片请求不再409而持久化。
    @Test void suspendedTurnRequiresStopBeforeChangingImages() {
        var sent=fixture.app.send(1,fixture.id,new AgentApplication.Send("first","看图",null,List.of("1")));
        fixture.store.status(sent.turn().id(),"SUSPENDED","模型暂停");
        long jobs=fixture.count("job_probe");
        var e=assertThrows(BusinessException.class,()->fixture.app.send(1,fixture.id,new AgentApplication.Send("change","换图",null,List.of("2"))));
        assertEquals(409,e.getCode());assertEquals(jobs,fixture.count("job_probe"));
        assertEquals("1",fixture.app.snapshot(1,fixture.id).state().workspace().path("imageAssetIds").get(0).asText());
        var resumed=fixture.app.send(1,fixture.id,new AgentApplication.Send("resume","继续",null));
        assertEquals(sent.turn().id(),resumed.turn().id());assertEquals("QUEUED",resumed.turn().status());
    }
}
