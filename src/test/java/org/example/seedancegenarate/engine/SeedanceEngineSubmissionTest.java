package org.example.seedancegenarate.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.SeedanceConfig;
import org.example.seedancegenarate.engine.Impl.SeedanceEngine;
import org.example.seedancegenarate.service.SeedanceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SeedanceEngineSubmissionTest {

    @Test
    void unsupportedLocalModelIsProvenNotAccepted() {
        // 【测什么】本地模型映射不支持时用 typed 拒绝，且尚未触发任何供应商 HTTP。
        // 【怎么算红】抛普通 RuntimeException 会被 attempt 错分成 UNKNOWN 并冻结占槽。
        SeedanceService seedance = mock(SeedanceService.class);
        SeedanceConfig config = new SeedanceConfig();
        SeedanceConfig.SeedanceModel model = new SeedanceConfig.SeedanceModel();
        model.setId("known");
        model.setName("ark-known");
        config.setModels(List.of(model));
        SeedanceEngine engine = new SeedanceEngine(seedance, config, new ObjectMapper());
        GenerateCommand command = GenerateCommand.builder()
                .model("missing")
                .prompt("prompt")
                .duration(5)
                .ratio("16:9")
                .build();

        assertThrows(SubmissionNotAcceptedException.class, () -> engine.submit(command));
        verifyNoInteractions(seedance);
    }
}
