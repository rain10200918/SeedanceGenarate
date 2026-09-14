package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.generation.AgentVideoPromptPreparation.Plan;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AgentVideoPromptProtocolCheckpointTest {
    AgentVideoPromptCheckpointStoreTest f;
    @BeforeEach void setup() throws Exception {f=new AgentVideoPromptCheckpointStoreTest();f.setup();}
    boolean legacy(Plan plan) {return f.tx.execute(t->f.store.usesLegacyBinding(f.session,f.turn,f.call,plan));}
    // 【测什么】新调用默认新协议；已经创建但未成功的旧调用仍保持旧协议，成功旧字节跨恢复原样保留。
    // 【怎么算红】只查非空prompt或一律新版将丢失旧协议和已成功首幕。
    @Test void legacyResumeIncludesEmptyAndCompletedCheckpoints() {
        assertFalse(legacy(f.plan()));f.load(f.plan());assertTrue(legacy(f.plan()));
        f.nextCall();assertTrue(legacy(f.plan()));assertTrue(f.load(f.plan()).isEmpty());
        f.save(f.plan(),0,"legacy-verbatim");f.nextCall();assertTrue(legacy(f.plan()));
        assertEquals(Map.of("scene-1","legacy-verbatim"),f.load(f.plan()));
    }
    // 【测什么】当前新版本调用优先于更早的旧候选，hash/step/审批/取消隔离仍有效。
    // 【怎么算红】忽略当前call或放宽复用身份，会降级当前新版、继承不相干旧结果。
    @Test void existingModernCallCannotDowngradeAndIneligibleLegacyCannotSelectVersion() {
        f.load(f.plan());f.save(f.plan(),0,"legacy");f.nextCall();
        var modern=f.plan("b".repeat(64),"plan-step");f.load(modern);
        assertFalse(legacy(f.plan()));assertThrows(BusinessException.class,()->f.load(f.plan()));
        f.nextCall();assertFalse(legacy(f.plan("c".repeat(64),"plan-step")));
        assertFalse(legacy(f.plan("a".repeat(64),"other-step")));
        f.db.update("INSERT INTO agent_approval VALUES('approved','call')");assertFalse(legacy(f.plan()));
        f.db.update("DELETE FROM agent_approval");f.db.update("UPDATE agent_video_prompt_checkpoint SET execution_epoch=2 WHERE call_id='call'");
        assertFalse(legacy(f.plan()));
    }
    // 【测什么】数据库写入使用每幕有效额度，而不是旧均分值；已保存检查点不可覆盖。
    // 【怎么算红】save仍检查perPrompt或移除校验，会拒合法复杂幕或收下超额简单幕。
    @Test void checkpointUsesPerSceneLimitAndPreservesWriteOnce() {
        var old=f.plan();var plan=new Plan("c".repeat(64),old.batchStepId(),old.scenes(),4000,true,Map.of("scene-1",2000,"scene-2",500));
        f.load(plan);f.save(plan,0,"x".repeat(2000));
        assertThrows(BusinessException.class,()->f.save(plan,1,"x".repeat(501)));
        f.save(plan,1,"x".repeat(500));assertThrows(BusinessException.class,()->f.save(plan,0,"overwrite"));
    }
}
