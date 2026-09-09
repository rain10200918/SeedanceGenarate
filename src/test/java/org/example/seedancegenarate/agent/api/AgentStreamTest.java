package org.example.seedancegenarate.agent.api;

import org.example.seedancegenarate.agent.application.AgentApplication;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.UserTokenService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentStreamTest {
    final AgentApplication app=mock(AgentApplication.class);
    final UserTokenService tokens=mock(UserTokenService.class);
    final SseEmitter emitter=mock(SseEmitter.class);
    SseEmitter nextEmitter=emitter;
    final AtomicReference<Throwable> uncaught=new AtomicReference<>();
    final List<Thread> workers=new CopyOnWriteArrayList<>();
    AgentStream stream;
    ThreadPoolExecutor senders;
    Runnable completion,timeout;
    Consumer<Throwable> error;

    @BeforeEach void setup() {
        stream=new AgentStream(app,tokens) {
            @Override SseEmitter createEmitter() { return nextEmitter; }
        };
        senders=(ThreadPoolExecutor)ReflectionTestUtils.getField(stream,"senders");
        senders.setThreadFactory(r->{
            Thread t=new Thread(r,"sse-lifecycle-test");
            t.setUncaughtExceptionHandler((thread,e)->uncaught.set(e));
            workers.add(t); return t;
        });
        doAnswer(i->{completion=i.getArgument(0);return null;}).when(emitter).onCompletion(any());
        doAnswer(i->{timeout=i.getArgument(0);return null;}).when(emitter).onTimeout(any());
        doAnswer(i->{error=i.getArgument(0);return null;}).when(emitter).onError(any());
        AppUser user=new AppUser();user.setId(1L);
        when(tokens.getUserByToken("test-token")).thenReturn(user);
        when(app.revision(1,2)).thenReturn(1L);
        AgentViews.Snapshot snapshot=mock(AgentViews.Snapshot.class);
        when(snapshot.revision()).thenReturn(1L);
        when(app.snapshot(1,2)).thenReturn(snapshot);
    }
    @AfterEach void cleanup() { stream.close(); }
    void drain() throws InterruptedException {
        senders.shutdown();
        assertTrue(senders.awaitTermination(3,TimeUnit.SECONDS),"发送线程未退出");
        for(Thread t:workers) t.join(1000);
        assertNull(uncaught.get(),"不能从发送线程泄漏清理异常");
    }
    void noClients() {
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(stream,"clients")).isEmpty());
    }

    // 【测什么】IOException与失效AsyncContext都撤销连接，不再调用complete。
    // 【怎么算红】恢复catch里的complete，never断言失败；不撤销连接则map非空。
    @ParameterizedTest @ValueSource(booleans={false,true})
    void failedWriteIsOwnedByContainer(boolean invalidContext) throws Exception {
        Exception failure=invalidContext?new IllegalStateException("AsyncContext already errored"):new IOException("broken pipe");
        doThrow(failure).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        stream.open(1,2,"test-token");drain();
        noClients();verify(emitter,never()).complete();verify(app,never()).snapshot(anyLong(),anyLong());
        error.accept(failure);completion.run();timeout.run();stream.close();
        verify(emitter,never()).complete();
    }

    // 【测什么】容器结束连接时，已在两线程忙队列中的任务也不查token/发消息。
    // 【怎么算红】只删clients而不检查closed，释放阻塞后会出现token查询和send。
    @ParameterizedTest @ValueSource(strings={"error","timeout","completion"})
    void queuedSendExitsAfterContainerClosure(String callback) throws Exception {
        CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);
        for(int i=0;i<2;i++)senders.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try {
            assertTrue(started.await(2,TimeUnit.SECONDS));
            stream.open(1,2,"test-token");
            switch(callback) { case "error"->error.accept(new IOException("closed"));case "timeout"->timeout.run();default->completion.run(); }
        } finally { release.countDown(); }
        drain();noClients();
        verifyNoInteractions(tokens);verify(emitter,never()).send(any(SseEmitter.SseEventBuilder.class));verify(emitter,never()).complete();
    }

    // 【测什么】快照查询期间发生onError，不再发送已读取的快照或重复完成请求。
    // 【怎么算红】删除write的closed检查，第二次send断言失败。
    @Test void disconnectDuringSnapshotDoesNotWriteLateResult() throws Exception {
        AgentViews.Snapshot snapshot=mock(AgentViews.Snapshot.class);
        when(app.snapshot(1,2)).thenAnswer(i->{error.accept(new IOException("closed during query"));return snapshot;});
        stream.open(1,2,"test-token");drain();noClients();
        verify(emitter,times(1)).send(any(SseEmitter.SseEventBuilder.class));verify(emitter,never()).complete();
    }

    // 【测什么】业务查询错误主动完成一次，容器在complete时已失效也不炸发送线程。
    // 【怎么算红】不主动complete或未兜住完成竞态，次数/uncaught断言失败。
    @Test void queryFailureClosesOnceEvenWhenContainerWinsCompletionRace() throws Exception {
        when(app.snapshot(1,2)).thenThrow(new IllegalStateException("database unavailable"));
        doThrow(new IllegalStateException("AsyncContext already errored")).when(emitter).complete();
        stream.open(1,2,"test-token");drain();noClients();
        verify(emitter,times(1)).complete();
        assertDoesNotThrow(stream::close);verify(emitter,times(1)).complete();
    }

    // 【测什么】token失效仍关闭订阅，不能发送心跳或快照。
    // 【怎么算红】移除token闸门，complete次数或send断言失败。
    @Test void revokedTokenStillClosesWithoutSending() throws Exception {
        when(tokens.getUserByToken("test-token")).thenReturn(null);
        stream.open(1,2,"test-token");drain();noClients();
        verify(emitter,times(1)).complete();verify(emitter,never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    // 【测什么】正常连接发送心跳和快照，停机一条complete失败不阻止其他连接清理。
    // 【怎么算红】吞掉所有发送/停机未逐条兜底/停止后还能open，下面断言失败。
    @Test void shutdownIsolatesCompletionFailuresAndRejectsNewConnections() throws Exception {
        stream.open(1,2,"test-token");drain();
        verify(emitter,times(2)).send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IllegalStateException("container stopped")).when(emitter).complete();
        SseEmitter second=streamForSecondEmitter();
        assertDoesNotThrow(stream::close);noClients();
        verify(second).complete();verify(emitter).complete();
        assertThrows(BusinessException.class,()->stream.open(1,2,"test-token"));
    }
    private SseEmitter streamForSecondEmitter() {
        // A second real open, sharing the existing registry; its rejected dispatch needs no worker.
        SseEmitter second=mock(SseEmitter.class);
        nextEmitter=second;
        stream.open(1,3,"test-token");
        return second;
    }
}
