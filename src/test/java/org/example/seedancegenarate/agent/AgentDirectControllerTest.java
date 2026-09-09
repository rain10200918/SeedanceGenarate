package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.api.*;
import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.agent.generation.AgentDirectGenerationGateway;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentDirectControllerTest {
    MockMvc mvc; AgentDirectApplication direct; AgentApplication app;
    final String payload="{\"clientMsgId\":\"c1\",\"expectedWorkspaceVersion\":0,\"outputType\":\"IMAGE\",\"input\":{\"model\":\"m\",\"prompt\":\"雷达\"},\"attachments\":[]}";
    @BeforeEach void setup() {
        direct=mock(AgentDirectApplication.class); app=mock(AgentApplication.class);
        var rate=mock(TokenBucketRateLimitService.class); when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(true,0));
        var controller=new AgentController(app,direct,mock(AgentDirectGenerationGateway.class),mock(AgentWorkspaceApplication.class),mock(AgentApprovalApplication.class),mock(AgentModelGateway.class),mock(AgentStream.class),rate);
        mvc=MockMvcBuilders.standaloneSetup(controller).build();
        var user=new AppUser(); user.setId(7L); UserContext.setUser(user);
        when(app.snapshot(7,12)).thenReturn(new AgentViews.Snapshot("12","对话",1,new AgentViews.State("",""),null,List.of(),List.of()));
    }
    @AfterEach void cleanup() { UserContext.clear(); }
    // 【测什么】JSON直接生成只使用认证身份，并返回202及同一对话快照。
    // 【怎么算红】改变路由/状态码、信任请求userId或误调普通Agent.send，这条失败。
    @Test void jsonCommandUsesAuthenticatedOwner() throws Exception {
        mvc.perform(post("/api/agent/conversations/12/direct?userId=99").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.id").value("12"));
        verify(direct).submit(eq(7L),eq(12L),argThat(r->r.outputType().equals("IMAGE") && r.input().path("prompt").asText().equals("雷达")),any());
        verify(app,never()).send(anyLong(),anyLong(),any());
    }
    // 【测什么】multipart保留图片顺序及视频/音频文件，各媒体进入同一个Direct入口。
    // 【怎么算红】漏传任一数组、payload或imageOrder，捕获的实际参数断言失败。
    @Test void multipartKeepsReferenceFilesAndImageOrder() throws Exception {
        mvc.perform(multipart("/api/agent/conversations/12/direct")
                .file(new MockMultipartFile("payload","","application/json",payload.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("images","a.png","image/png",new byte[]{1}))
                .file(new MockMultipartFile("videos","v.mp4","video/mp4",new byte[]{2}))
                .file(new MockMultipartFile("audios","a.mp3","audio/mpeg",new byte[]{3}))
                .param("imageOrder","file"))
                .andExpect(status().isAccepted());
        var files=ArgumentCaptor.forClass(ConversationMediaResolver.LocalFiles.class);
        verify(direct).submit(eq(7L),eq(12L),any(),files.capture());
        assertEquals(List.of("file"),files.getValue().imageOrder());
        assertArrayEquals(new byte[]{1},files.getValue().images()[0].getBytes());
        assertArrayEquals(new byte[]{2},files.getValue().videos()[0].getBytes());
        assertArrayEquals(new byte[]{3},files.getValue().audios()[0].getBytes());
    }
}
