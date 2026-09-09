package org.example.seedancegenarate.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationAttemptMapperRecoverySqlTest {

    @Test
    // 【测什么】恢复扫描只取缺作业/DEAD，并优先补新 UNKNOWN，人工待处理记录不能长期堵住扫描窗口。
    // 【怎么算红】删掉 async_job 状态过滤或缺作业优先排序后，这条必须变红。
    void recoveryScanExcludesCompletedManualCasesAndPrioritizesMissingJobs() throws Exception {
        Method method = GenerationAttemptMapper.class.getMethod("findSubmitUnknown", int.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Select.class).value()));

        assertTrue(sql.contains("LEFT JOIN ASYNC_JOB J"), sql);
        assertTrue(sql.contains("J.JOB_TYPE = 'GENERATION_RECOVERY'"), sql);
        assertTrue(sql.contains("J.ID IS NULL OR J.STATUS = 'DEAD'"), sql);
        assertTrue(sql.contains("ORDER BY (J.ID IS NULL) DESC"), sql);
        assertFalse(sql.contains("J.STATUS = 'SUCCEEDED'"), sql);
    }

    private String normalize(String sql) {
        return Arrays.stream(sql.trim().split("\\s+"))
                .filter(part -> !part.isBlank())
                .reduce((left, right) -> left + " " + right)
                .orElse("")
                .toUpperCase();
    }
}
