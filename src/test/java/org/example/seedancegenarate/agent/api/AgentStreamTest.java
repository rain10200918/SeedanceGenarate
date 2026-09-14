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
    void awaitIdle() throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!idle() && System.nanoTime()<deadline)
            Thread.sleep(1);
        assertTrue(idle(),"连接发送与队列必须完成后再验证调用数");
        assertNull(uncaught.get());
    }
    boolean idle() {
        // ThreadPoolExecutor task counters are approximate and can transiently omit a worker.
        // Connection.sending is set before enqueue and cleared only after publish returns.
        boolean sending=((Map<?,?>)ReflectionTestUtils.getField(stream,"clients")).values().stream()
                .anyMatch(c->((java.util.concurrent.atomic.AtomicBoolean)ReflectionTestUtils.getField(c,"sending")).get());
        return !sending && senders.getQueue().isEmpty() && senders.getActiveCount()==0;
    }
    SseEmitter openAdditional(long user,long conversation,String token) throws InterruptedException {
        nextEmitter=mock(SseEmitter.class);
        SseEmitter result=stream.open(user,conversation,token);
        awaitIdle();return result;
    }
    void ageConnections(String field) {
        ((Map<?,?>)ReflectionTestUtils.getField(stream,"clients")).values()
                .forEach(c->ReflectionTestUtils.setField(c,field,0L));
    }
    AgentViews.Snapshot nextSnapshot(long revision) {
        AgentViews.Snapshot snapshot=mock(AgentViews.Snapshot.class);
        when(snapshot.revision()).thenReturn(revision);
        when(app.revision(1,2)).thenReturn(revision);
        when(app.snapshot(1,2)).thenReturn(snapshot);
        return snapshot;
    }
    void received(SseEmitter target,AgentViews.Snapshot snapshot) throws IOException {
        verify(target).send(argThat((SseEmitter.SseEventBuilder event)->
                event.build().stream().anyMatch(data->data.getData()==snapshot)));
    }

    // 【测什么】同用户同会话两个连接同轮只读一次revision/snapshot，下一轮必须重新读。
    // 【怎么算红】保持原逐连接查询时times(1)失败；跨轮保留结果时第二轮新快照断言失败。
    @RepeatedTest(20) void refreshCoalescesReadsOnlyWithinOneRound() throws Exception {
        stream.open(1,2,"test-token");awaitIdle();
        SseEmitter second=openAdditional(1,2,"test-token");
        for(long revision=2;revision<=3;revision++) {
            AgentViews.Snapshot snapshot=nextSnapshot(revision);
            clearInvocations(app,tokens,emitter,second);
            stream.refresh();awaitIdle();
            verify(app,times(1)).revision(1,2);verify(app,times(1)).snapshot(1,2);
            received(emitter,snapshot);received(second,snapshot);
            verifyNoInteractions(tokens);
        }
    }

    // 【测什么】媒体同revision到期仍合并重新投影，每连接心跳与token检查保持独立。
    // 【怎么算红】逐连接hasMedia/snapshot会超次数；缓存媒体或合并token核验会少调用/少发送。
    @Test void mediaRefreshAndHeartbeatRemainPerConnectionAtOriginalCadence() throws Exception {
        stream.open(1,2,"test-token");awaitIdle();
        SseEmitter second=openAdditional(1,2,"test-token");
        when(app.hasMedia(1,2)).thenReturn(true);
        for(int round=0;round<2;round++) {
            AgentViews.Snapshot snapshot=nextSnapshot(1);
            ageConnections("lastSnapshot");ageConnections("lastHeartbeat");
            clearInvocations(app,tokens,emitter,second);
            stream.refresh();awaitIdle();
            verify(app,times(1)).revision(1,2);verify(app,times(1)).hasMedia(1,2);
            verify(app,times(1)).snapshot(1,2);verify(tokens,times(2)).getUserByToken("test-token");
            verify(emitter,times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(second,times(2)).send(any(SseEmitter.SseEventBuilder.class));
            received(emitter,snapshot);received(second,snapshot);
        }
    }

    // 【测什么】同会话的另一token撤销只关闭该连接，不能借有效连接的共享结果发送。
    // 【怎么算红】共享token授权或先发共享快照再校验，撤销连接never(send)断言失败。
    @Test void revokedSiblingCannotConsumeSharedSnapshot() throws Exception {
        AppUser user=new AppUser();user.setId(1L);
        when(tokens.getUserByToken("revoked-later")).thenReturn(user);
        stream.open(1,2,"test-token");awaitIdle();
        SseEmitter second=openAdditional(1,2,"revoked-later");
        when(tokens.getUserByToken("revoked-later")).thenReturn(null);
        AgentViews.Snapshot snapshot=nextSnapshot(2);ageConnections("lastHeartbeat");
        clearInvocations(app,tokens,emitter,second);
        stream.refresh();awaitIdle();
        verify(tokens).getUserByToken("test-token");verify(tokens).getUserByToken("revoked-later");
        verify(second).complete();verify(second,never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(app,times(1)).revision(1,2);verify(app,times(1)).snapshot(1,2);received(emitter,snapshot);
    }

    // 【测什么】共享revision或snapshot失败本轮只查询一次，但每个连接均安全关闭。
    // 【怎么算红】失败未纳入同轮合并则查询两次；吞错/只关闭首连接则complete或空表断言失败。
    @ParameterizedTest @ValueSource(booleans={false,true})
    void sharedReadFailureClosesEveryConnectionWithoutRetryingThisRound(boolean snapshotFailure) throws Exception {
        stream.open(1,2,"test-token");awaitIdle();
        SseEmitter second=openAdditional(1,2,"test-token");
        nextSnapshot(2);
        if(snapshotFailure)when(app.snapshot(1,2)).thenThrow(new IllegalStateException("snapshot unavailable"));
        else when(app.revision(1,2)).thenThrow(new BusinessException(404,"conversation deleted"));
        clearInvocations(app,emitter,second);
        stream.refresh();awaitIdle();
        verify(app,times(1)).revision(1,2);
        verify(app,times(snapshotFailure?1:0)).snapshot(1,2);
        verify(emitter).complete();verify(second).complete();noClients();
        verify(emitter,never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(second,never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    // 【测什么】不同用户或不同会话不能共享快照；mock允许相同会话ID以直接验证分组键。
    // 【怎么算红】分组键只含user或conversation时，会漏掉一组查询或给连接发送别组快照。
    @Test void refreshSeparatesBothUserAndConversation() throws Exception {
        AppUser otherUser=new AppUser();otherUser.setId(9L);
        when(tokens.getUserByToken("other-token")).thenReturn(otherUser);
        AgentViews.Snapshot otherSession=mock(AgentViews.Snapshot.class);
        AgentViews.Snapshot otherOwner=mock(AgentViews.Snapshot.class);
        when(otherSession.revision()).thenReturn(1L);when(otherOwner.revision()).thenReturn(1L);
        when(app.revision(1,3)).thenReturn(1L);when(app.snapshot(1,3)).thenReturn(otherSession);
        when(app.revision(9,2)).thenReturn(1L);when(app.snapshot(9,2)).thenReturn(otherOwner);
        stream.open(1,2,"test-token");awaitIdle();
        SseEmitter differentSession=openAdditional(1,3,"test-token");
        SseEmitter differentOwner=openAdditional(9,2,"other-token");
        AgentViews.Snapshot own=nextSnapshot(2);
        when(otherSession.revision()).thenReturn(2L);when(otherOwner.revision()).thenReturn(2L);
        when(app.revision(1,3)).thenReturn(2L);when(app.revision(9,2)).thenReturn(2L);
        clearInvocations(app,emitter,differentSession,differentOwner);
        stream.refresh();awaitIdle();
        verify(app).revision(1,2);verify(app).revision(1,3);verify(app).revision(9,2);
        verify(app).snapshot(1,2);verify(app).snapshot(1,3);verify(app).snapshot(9,2);
        received(emitter,own);received(differentSession,otherSession);received(differentOwner,otherOwner);
        verify(emitter,times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(differentSession,times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(differentOwner,times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // 【测什么】刷新后重连同revision也独立查询并发送新完整快照，不借上一轮缓存。
    // 【怎么算红】open复用刷新快照或跳过初始snapshot，新快照对象及查询次数断言失败。
    @Test void reconnectReadsIndependentFullSnapshotEvenAtSameRevision() throws Exception {
        stream.open(1,2,"test-token");awaitIdle();
        nextSnapshot(2);stream.refresh();awaitIdle();
        AgentViews.Snapshot fresh=nextSnapshot(2);clearInvocations(app,tokens);
        SseEmitter reconnect=openAdditional(1,2,"test-token");
        verify(app,times(2)).revision(1,2);verify(app,times(1)).snapshot(1,2);
        verify(tokens).getUserByToken("test-token");received(reconnect,fresh);
        verify(reconnect,times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // 【测什么】同组一条连接阻塞send时，另一条仍可发送；只验证一个慢连接与现有两线程池。
    // 【怎么算红】把send放进共享读锁，另一连接无法在释放慢连接前完成发送。
    @Test void slowSendDoesNotHoldSharedReadLock() throws Exception {
        stream.open(1,2,"test-token");awaitIdle();
        SseEmitter second=openAdditional(1,2,"test-token");
        AgentViews.Snapshot snapshot=nextSnapshot(2);
        CountDownLatch slowEntered=new CountDownLatch(1),releaseSlow=new CountDownLatch(1),fastSent=new CountDownLatch(1);
        doAnswer(i->{
            slowEntered.countDown();
            if(!releaseSlow.await(3,TimeUnit.SECONDS))throw new IOException("test send timed out");
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        doAnswer(i->{
            SseEmitter.SseEventBuilder event=i.getArgument(0);
            if(event.build().stream().anyMatch(data->data.getData()==snapshot))fastSent.countDown();
            return null;
        }).when(second).send(any(SseEmitter.SseEventBuilder.class));
        // Force the fast connection to reach its read after the slow connection has entered send.
        ageConnections("lastHeartbeat");
        AppUser user=new AppUser();user.setId(1L);
        when(tokens.getUserByToken("test-token")).thenAnswer(i->{
            // Only the fast connection is due for token verification in this round.
            assertTrue(slowEntered.await(2,TimeUnit.SECONDS));return user;
        });
        ((Map<?,?>)ReflectionTestUtils.getField(stream,"clients")).forEach((key,c)->{
            if(key==emitter)ReflectionTestUtils.setField(c,"lastHeartbeat",System.currentTimeMillis());
        });
        try {
            stream.refresh();
            assertTrue(slowEntered.await(2,TimeUnit.SECONDS),"慢连接未进入send");
            assertTrue(fastSent.await(1,TimeUnit.SECONDS),"另一连接被共享锁阻塞");
            assertEquals(2,senders.getMaximumPoolSize());
        } finally { releaseSlow.countDown(); }
        awaitIdle();
        verify(second,times(4)).send(any(SseEmitter.SseEventBuilder.class));
    }

    private SseEmitter streamForSecondEmitter() {
        // A second real open, sharing the existing registry; its rejected dispatch needs no worker.
        SseEmitter second=mock(SseEmitter.class);
        nextEmitter=second;
        stream.open(1,3,"test-token");
        return second;
    }
}
