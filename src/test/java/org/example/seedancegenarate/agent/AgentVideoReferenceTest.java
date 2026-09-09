package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentVideoReferenceTest {
    final ObjectMapper json=new ObjectMapper();
    // 【测什么】准确图片与风格入报价，重报价不重复拼提示；提交传稳定typed引用，不传短签名。
    // 【怎么算红】去掉_reference冻结、SubmitRequest引用赋值或拼接后长度守卫，相应断言失败。
    @Test void referenceIsFrozenAndCarriedToOriginalSubmission() throws Exception {
        var engine=mock(VideoEngine.class);when(engine.provider()).thenReturn("local");
        var spec=new ModelSpec("local","ref","参考",true,1,1,List.of("16:9"),5,15,List.of(),OutputType.VIDEO)
                .withImageInputMode(ModelSpec.ImageInputMode.REFERENCE_IMAGE);
        when(engine.models()).thenReturn(List.of(spec));var access=mock(ModelAccessService.class);when(access.isOpen("ref")).thenReturn(true);
        var submit=mock(VideoSubmitService.class);when(submit.estimate("local","ref",7)).thenReturn(new VideoSubmitService.PriceEstimate("local","ref",7,"VIDEO",BigDecimal.ONE,BigDecimal.ONE,"CNY"));
        var gateway=new AgentGenerationGateway(new VideoEngineRegistry(List.of(engine)),access,submit,mock(VideoTaskService.class),new ContentModerationPolicy(),new ArtifactExpiryPolicy(30),json,mock(AgentDirectGenerationGateway.class));
        var resolver=mock(AgentVideoReference.class);ReflectionTestUtils.setField(gateway,"videoReference",resolver);
        when(resolver.resolve(eq(1L),eq("s"),any())).thenReturn(json.createObjectNode().put("userId",1).put("sessionId","s").put("taskId","source").put("objectKey","outputs/cat.png").put("title","cat").put("mediaPath","/api/agent/media/source"));
        var input=json.readTree("{\"model\":\"ref\",\"prompt\":\"跳跃\",\"duration\":7,\"visualStyle\":\"温馨3D\",\"referenceImage\":{\"artifactId\":\"a\",\"version\":1}}");
        var context=new AgentContext(1L,"s",null,null,null,null,List.of(),List.of(),0);
        var quote=gateway.quoteVideo(context,input);
        assertEquals("AGENT",quote.origin());assertEquals("outputs/cat.png",quote.inputSnapshot().path("_reference").path("objectKey").asText());
        assertEquals(quote.inputSnapshot(),gateway.quoteVideo(context,gateway.videoParameters(quote.inputSnapshot())).inputSnapshot());
        var oversized=input.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)oversized).put("prompt","x".repeat(4000));
        assertThrows(Exception.class,()->gateway.quoteVideo(context,oversized));
        var task=new VideoTask();task.setBizTaskId("accepted");when(submit.submitApproved(any(),any())).thenReturn(task);
        assertEquals("accepted",gateway.submit(1,quote,"r"));
        verify(submit).submitApproved(argThat(r->r.imageUrls().isEmpty() && r.storedImageReferences().size()==1 && r.storedImageReferences().get(0).objectKey().equals("outputs/cat.png")),any());
        assertThrows(Exception.class,()->gateway.submit(2,quote,"other"));
        when(engine.models()).thenReturn(List.of(new ModelSpec("local","ref","unknown",true,1,1,List.of("16:9"),5,15,List.of())));
        assertThrows(Exception.class,()->gateway.quoteVideo(context,input));
    }
    // 【测什么】引用只接受精确版本身份，不接受URL、额外字段、零/小数版本。
    // 【怎么算红】删掉shape字段数或整数检查，这些assertThrows失败。
    @Test void referenceShapeIsStrict() throws Exception {
        for(String input:List.of("{\"artifactId\":\"a\",\"version\":0}","{\"artifactId\":\"a\",\"version\":1.5}","{\"artifactId\":\"a\",\"version\":1,\"url\":\"https://evil\"}"))
            assertThrows(Exception.class,()->AgentVideoReference.validateShape(json.readTree(input)));
    }
    // 【测什么】真实SQL仅解析本用户、本Session的准确IMAGE版本，旧版本不能被最新版本替换。
    // 【怎么算红】去掉SQL session/user/version过滤，越权或错误版本断言失败。
    @Test void artifactLookupUsesExactOwnedSessionVersion() throws Exception {
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:video-ref-"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE agent_session(id VARCHAR PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE video_task(biz_task_id VARCHAR PRIMARY KEY,artifact_key VARCHAR)");
        jdbc.execute("CREATE TABLE agent_artifact_version(artifact_id VARCHAR,version_no INT,session_id VARCHAR,user_id BIGINT,type VARCHAR,title VARCHAR,task_id VARCHAR)");
        jdbc.update("INSERT INTO agent_session VALUES('s',1),('other',2)");
        jdbc.update("INSERT INTO video_task VALUES('t1','outputs/one'),('t2','outputs/two')");
        jdbc.update("INSERT INTO agent_artifact_version VALUES('a',1,'s',1,'IMAGE','v1','t1'),('a',2,'s',1,'IMAGE','v2','t2')");
        var refs=mock(StoredImageReferences.class);var resolver=new AgentVideoReference(jdbc,refs,json);
        var ref=json.readTree("{\"artifactId\":\"a\",\"version\":1}");
        assertEquals("outputs/one",resolver.resolve(1L,"s",ref).path("objectKey").asText());
        assertThrows(Exception.class,()->resolver.resolve(2L,"s",ref));
        assertThrows(Exception.class,()->resolver.resolve(1L,"other",ref));
        assertThrows(Exception.class,()->resolver.resolve(1L,"s",json.readTree("{\"artifactId\":\"a\",\"version\":3}")));
        verify(refs).validateAvailable(1L,new StoredImageReferences.Reference("t1","outputs/one"));
    }
}
