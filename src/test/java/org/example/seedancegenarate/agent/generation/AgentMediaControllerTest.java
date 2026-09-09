package org.example.seedancegenarate.agent.generation;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentMediaControllerTest {
    final AgentGenerationGateway gateway=mock(AgentGenerationGateway.class);
    final ArtifactStorage storage=mock(ArtifactStorage.class);
    final AgentMediaController controller=new AgentMediaController(gateway,storage);
    @BeforeEach void login() { var user=new AppUser(); user.setId(42L); UserContext.setUser(user); }
    @AfterEach void cleanup() { UserContext.clear(); }

    // 【测什么】预览只签名经过当前用户权限检查的任务对象，凭证短期有效且响应不缓存。
    // 【怎么算红】跳过当前用户检查、延长签名或缺少no-store时verify/assertEquals失败。
    @Test void signsOnlyAuthorizedObjectForOneMinute() throws Exception {
        when(gateway.mediaKey(42L,"tsk_owned")).thenReturn("outputs/owned.png");
        when(storage.createSignedGetUrl("outputs/owned.png",Duration.ofSeconds(60))).thenReturn("https://storage.invalid/signed");
        var response=new MockHttpServletResponse(); controller.media("tsk_owned",response);
        verify(gateway).mediaKey(42L,"tsk_owned");
        verify(storage).createSignedGetUrl("outputs/owned.png",Duration.ofSeconds(60));
        assertEquals("no-store",response.getHeader("Cache-Control"));
        assertEquals("https://storage.invalid/signed",response.getRedirectedUrl());
    }
    // 【测什么】无权限/屏蔽后不得调用签名服务；未登录不能进入任务查询。
    // 【怎么算红】吞权限异常继续签名，verifyNoInteractions或assertThrows失败。
    @Test void denialCannotProduceSignedUrl() {
        when(gateway.mediaKey(42L,"tsk_other")).thenThrow(BusinessException.forbidden("不可查看"));
        assertThrows(BusinessException.class,()->controller.media("tsk_other",new MockHttpServletResponse()));
        verifyNoInteractions(storage); clearInvocations(gateway); UserContext.clear();
        assertThrows(RuntimeException.class,()->controller.media("tsk_other",new MockHttpServletResponse()));
        verifyNoInteractions(gateway,storage);
    }

    // 【测什么】音频复用同一受保护签名入口，不把MP3当作永久公开地址。
    // 【怎么算红】仅图片可签名或省略60秒/no-store时断言失败。
    @Test void audioObjectUsesProtectedShortLivedRedirect() throws Exception {
        when(gateway.mediaKey(42L,"tsk_audio")).thenReturn("outputs/song.mp3");
        when(storage.createSignedGetUrl("outputs/song.mp3",Duration.ofSeconds(60))).thenReturn("https://storage.invalid/audio-signed");
        var response=new MockHttpServletResponse();controller.media("tsk_audio",response);
        verify(gateway).mediaKey(42L,"tsk_audio");verify(storage).createSignedGetUrl("outputs/song.mp3",Duration.ofSeconds(60));
        assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("https://storage.invalid/audio-signed",response.getRedirectedUrl());
    }
}
