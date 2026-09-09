package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.application.AgentArtifactQueryApplication;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentArtifactMediaTest extends AgentArtifactQueryTest {
    // 【测什么】媒体来自准确历史版本而不是最新消息窗口，反复浏览数据库不变。
    // 【怎么算红】换成最新版查询或触发snapshot/touch时，taskId或数据库快照断言失败。
    @Test void exactHistoricalMediaIsReadOnly() {
        String id=add(1,conversation,null);add(1,conversation,id);
        db.update("UPDATE agent_artifact_version SET type='IMAGE',task_id=CASE WHEN version_no=1 THEN 'old-task' ELSE 'new-task' END WHERE artifact_id=?",id);
        var gateway=mock(AgentGenerationGateway.class);
        when(gateway.read(1,"old-task")).thenReturn(new AgentGenerationGateway.TaskView("old-task","SUCCESS","IMAGE","/api/agent/media/old-task",false,false,null));
        var service=new AgentArtifactQueryApplication(store,gateway);var before=database();
        var media=service.media(1,conversation,id,1);
        assertEquals("old-task",media.taskId());assertEquals("task",media.type());assertEquals("",media.approvalId());
        assertEquals("/api/agent/media/old-task",media.mediaPath());assertEquals(media,service.media(1,conversation,id,1));
        assertEquals(before,database());verify(gateway,times(2)).read(1,"old-task");verifyNoMoreInteractions(gateway);
    }
    // 【测什么】先验证会话/作品归属，无媒体、坏版本、跨会话不能读取Task。
    // 【怎么算红】把gateway.read放到owned之前或省去类型守卫将调用mock或不再404。
    @Test void ownershipAndNonMediaFailBeforeTaskRead() {
        String id=add(1,conversation,null);var gateway=mock(AgentGenerationGateway.class);
        var service=new AgentArtifactQueryApplication(store,gateway);
        code(404,()->service.media(2,conversation,id,1));code(404,()->service.media(1,conversation,id,1));
        code(404,()->service.media(1,conversation,id,2));code(400,()->service.media(1,conversation,id,0));
        long other=store.create(1,"同用户其他会话");code(404,()->service.media(1,other,id,1));
        // Valid media must still fail ownership independently of the non-media guard.
        db.update("UPDATE agent_artifact_version SET type='IMAGE',task_id='owned-image' WHERE artifact_id=?",id);
        code(404,()->service.media(2,conversation,id,1));
        code(404,()->service.media(1,other,id,1));
        verifyNoInteractions(gateway);
    }
    // 【测什么】历史媒体每次复查审核/过期，屏蔽和过期不返回可访问路径。
    // 【怎么算红】缓存第一次TaskView或不清path将返回旧可见地址。
    @Test void moderationAndExpiryRemainAuthoritative() {
        String id=add(1,conversation,null);db.update("UPDATE agent_artifact_version SET type='VIDEO',task_id='v' WHERE artifact_id=?",id);
        var gateway=mock(AgentGenerationGateway.class);var service=new AgentArtifactQueryApplication(store,gateway);
        when(gateway.read(1,"v")).thenReturn(new AgentGenerationGateway.TaskView("v","SUCCESS","VIDEO","/api/agent/media/v",true,false,"不可查看"),
                new AgentGenerationGateway.TaskView("v","SUCCESS","VIDEO","/api/agent/media/v",false,true,"过期"));
        var blocked=service.media(1,conversation,id,1);assertTrue(blocked.blocked());assertNull(blocked.mediaPath());
        var expired=service.media(1,conversation,id,1);assertTrue(expired.expired());assertNull(expired.mediaPath());
    }
}
