package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.api.*;
import org.example.seedancegenarate.agent.application.AgentArtifactQueryApplication;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.controller.GlobalExceptionHandler;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentArtifactQueryControllerTest {
    MockMvc mvc; AgentStore store;
    @BeforeEach void setup() {
        store=mock(AgentStore.class);
        mvc=MockMvcBuilders.standaloneSetup(new AgentArtifactQueryController(new AgentArtifactQueryApplication(store)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var user=new AppUser(); user.setId(7L); UserContext.setUser(user);
    }
    @AfterEach void cleanup() {UserContext.clear();}
    // 【测什么】媒体GET使用登录身份、准确版本且禁止缓存；不接受客户端userId冒名。
    // 【怎么算红】路由/身份/版本或cache header改变，协议断言失败。
    @Test void mediaRouteIsExactAndUncached() throws Exception {
        var query=mock(AgentArtifactQueryApplication.class);
        mvc=MockMvcBuilders.standaloneSetup(new AgentArtifactQueryController(query)).build();
        when(query.media(7,12,"a",3)).thenReturn(new AgentArtifactQueryApplication.Media("task","","t","SUCCESS","IMAGE","/api/agent/media/t",false,false,null));
        mvc.perform(get("/api/agent/conversations/12/artifacts/a/versions/3/media").param("userId","999"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.outputType").value("IMAGE")).andExpect(jsonPath("$.data.taskId").value("t"));
        verify(query).media(7,12,"a",3);verifyNoMoreInteractions(query);
    }
    // 【测什么】GET取认证身份、精确版本和不丢精度cursor，省略limit=20；不使用快照或命令。
    // 【怎么算红】路由/参数/身份/返回视图或owned锁标志改变，调用与JSON断言失败。
    @Test void usesIdentityAndReadOnlyContract() throws Exception {
        var artifact=new AgentViews.Artifact("a",2,"SCRIPT","标题","正文");
        when(store.artifactPage(null,9007199254740993L,20,"a")).thenReturn(new AgentViews.ArtifactPage(List.of(artifact),"9007199254740992"));
        when(store.artifactVersion(null,"a",2)).thenReturn(artifact);
        mvc.perform(get("/api/agent/conversations/12/artifacts").param("beforeId","9007199254740993").param("artifactId","a").param("userId","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nextCursor").value("9007199254740992"))
                .andExpect(jsonPath("$.data.items[0].version").value(2)).andExpect(jsonPath("$.data.items[0].rowId").doesNotExist());
        mvc.perform(get("/api/agent/conversations/12/artifacts/a/versions/2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("a"));
        verify(store,times(2)).owned(12,7,false);
        verify(store).artifactPage(null,9007199254740993L,20,"a"); verify(store).artifactVersion(null,"a",2); verifyNoMoreInteractions(store);
    }
    // 【测什么】MVC类型绑定/溢出与业务参数错误真HTTP400，不能被GlobalHandler变500。
    // 【怎么算红】移除局部绑定handler，非法limit/version/id落通用500，本测试失败。
    @Test void invalidBindingsAre400() throws Exception {
        for(String path:List.of("/api/agent/conversations/12/artifacts?limit=oops","/api/agent/conversations/12/artifacts?limit=2147483648",
                "/api/agent/conversations/12/artifacts/a/versions/no","/api/agent/conversations/bad/artifacts",
                "/api/agent/conversations/12/artifacts?limit=0","/api/agent/conversations/12/artifacts?beforeId=9223372036854775808"))
            mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(store);
    }
    // 【测什么】归属不存在404、登录缺失401；意外业务异常不回显底层正文。
    // 【怎么算红】移除局部业务handler或使用异常原文做500，状态/正文断言失败。
    @Test void deniedAndUnexpectedErrorsStaySafe() throws Exception {
        when(store.owned(12,7,false)).thenThrow(BusinessException.notFound("对话不存在"));
        mvc.perform(get("/api/agent/conversations/12/artifacts")).andExpect(status().isNotFound());
        doThrow(new BusinessException(500,"SQL secret")).when(store).owned(12,7,false);
        mvc.perform(get("/api/agent/conversations/12/artifacts")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("作品读取失败，请稍后重试"));
        UserContext.clear(); mvc.perform(get("/api/agent/conversations/12/artifacts")).andExpect(status().isUnauthorized());
    }
}
