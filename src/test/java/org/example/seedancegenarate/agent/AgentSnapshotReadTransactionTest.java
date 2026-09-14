package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSnapshotReadTransactionTest {
    final AgentWorkspaceIntegrationTest f=new AgentWorkspaceIntegrationTest();
    AgentStore store; AgentApplication app;
    @BeforeEach void setup() throws Exception {
        f.setup(); store=spy(f.store);
        app=new AgentApplication(store,f.tx,f.jobs,f.models,f.json,mock(AgentApprovalApplication.class),f.approvals,mock(AgentImageInputs.class));
    }
    // 【测什么】完整快照投影使用显式可重复读只读事务，不持有会话写锁。
    // 【怎么算红】恢复旧snapshotLocked事务，readOnly/isolation/owned(true)断言失败。
    @Test void projectionUsesReadOnlyRepeatableReadWithoutSessionLock() {
        doAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return call.callRealMethod();
        }).when(store).messages(any());
        assertNotNull(app.snapshot(1,f.id));
        verify(store,never()).owned(f.id,1,true);
    }
    // 【测什么】组装消息暂停时，另一连接仍能锁住并更新同会话；返回重读后的版本。
    // 【怎么算红】完整投影持会话锁则writer超时；省略末尾版本复验则返回旧revision。
    @Test void writerCanCommitDuringProjectionAndSnapshotRetriesFresh() throws Exception {
        var writer=Executors.newSingleThreadExecutor(); var count=new AtomicInteger();
        doAnswer(call->{
            if(count.getAndIncrement()==0) writer.submit(()->f.tx.executeWithoutResult(t->{
                var s=f.store.owned(f.id,1,true);f.store.touch(s);
            })).get(3,TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(store).messages(any());
        try {
            var result=app.snapshot(1,f.id);
            assertEquals(f.session().revision(),result.revision()); assertEquals(2,count.get());
        } finally {writer.shutdownNow();}
    }
    // 【测什么】快照期间删除会话不返回已撤销会话内容。
    // 【怎么算红】省略事务外owned复验，会返回删除前的快照。
    @Test void archiveDuringReadFailsClosed() throws Exception {
        var writer=Executors.newSingleThreadExecutor();
        doAnswer(call->{
            writer.submit(()->f.tx.executeWithoutResult(t->f.store.archive(f.store.owned(f.id,1,true))))
                    .get(3,TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(store).messages(any());
        try {assertEquals(404,assertThrows(BusinessException.class,()->app.snapshot(1,f.id)).getCode());}
        finally {writer.shutdownNow();}
    }
    // 【测什么】连续修改只允许有界重读，避免在热会话上无限工作或返回混合结果。
    // 【怎么算红】删除重试上限会继续读取；删revision校验会返回成功而非409。
    @Test void continuousRevisionChangesFailWithBoundedConflict() {
        var n=new AtomicInteger();
        doAnswer(call->{
            var result=call.callRealMethod();
            assertTrue(n.incrementAndGet()<=2);
            return result;
        }).when(store).messages(any());
        doAnswer(call->{
            var s=(org.example.seedancegenarate.agent.persistence.AgentRows.Session)call.callRealMethod();
            if(!TransactionSynchronizationManager.isActualTransactionActive() && n.get()>0) {
                f.tx.executeWithoutResult(t->f.store.touch(f.store.owned(f.id,1,true)));
                return f.session();
            }
            return s;
        }).when(store).owned(f.id,1,false);
        assertEquals(409,assertThrows(BusinessException.class,()->app.snapshot(1,f.id)).getCode());
        assertEquals(2,n.get());
    }
    // 【测什么】SQL失败直接传播，不返回历史缓存或无限重试。
    // 【怎么算红】吞掉DB异常返回快照/自动反复查库，异常与次数断言失败。
    @Test void databaseFailureIsNotRetriedAsRevisionConflict() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("local fixture failure")).when(store).messages(any());
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->app.snapshot(1,f.id));
        verify(store).messages(any());
    }
    // 【测什么】已有外层事务的内部调用保持原锁语义，不挂起后等待自己的锁。
    // 【怎么算红】一律新建事务或仅只读投影，锁入口/结果断言失败。
    @Test void outerTransactionRetainsLegacyLockingPath() {
        var result=f.tx.execute(t->app.snapshot(1,f.id));
        assertNotNull(result);verify(store).owned(f.id,1,true);
    }
}
