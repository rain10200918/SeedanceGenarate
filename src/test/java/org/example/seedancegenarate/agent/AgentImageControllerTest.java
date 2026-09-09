package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.api.AgentImageController;
import org.example.seedancegenarate.agent.application.AgentImageInputs;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.controller.GlobalExceptionHandler;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentImageControllerTest {
    AgentImageInputs images=mock(AgentImageInputs.class);MockMvc mvc;
    MockMultipartFile file=new MockMultipartFile("file","x.png","image/png",new byte[]{1});
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new AgentImageController(images)).setControllerAdvice(new GlobalExceptionHandler()).build();var user=new AppUser();user.setId(7L);UserContext.setUser(user);}
    @AfterEach void cleanup(){UserContext.clear();}
    // 【测什么】图片上传只使用登录身份，返回字符串assetId，缺文件/登录和业务错误是真HTTP错误。
    // 【怎么算红】移除本地异常映射或让query userId覆盖身份，对应状态/调用断言失败。
    @Test void uploadUsesOwnerAndErrorsAreHttpErrors() throws Exception {
        when(images.upload(7,file)).thenReturn(new AgentImageInputs.ImageRef("9007199254740993","https://media.example.test/x.png"));
        mvc.perform(multipart("/api/agent/images").file(file).param("userId","999")).andExpect(status().isOk()).andExpect(jsonPath("$.data.assetId").value("9007199254740993"));
        verify(images).upload(7,file);
        mvc.perform(multipart("/api/agent/images")).andExpect(status().isBadRequest());
        when(images.upload(7,file)).thenThrow(BusinessException.badRequest("非法图片"));
        mvc.perform(multipart("/api/agent/images").file(file)).andExpect(status().isBadRequest());
        doThrow(new BusinessException(503,"存储不可用")).when(images).upload(7,file);
        mvc.perform(multipart("/api/agent/images").file(file)).andExpect(status().isServiceUnavailable());
        UserContext.clear();mvc.perform(multipart("/api/agent/images").file(file)).andExpect(status().isUnauthorized());
    }
}
