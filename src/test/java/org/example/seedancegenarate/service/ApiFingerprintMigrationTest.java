package org.example.seedancegenarate.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class ApiFingerprintMigrationTest {
    // 【测什么】实际V57保留旧行null、支持新指纹；原64宽及唯一键仍挡超长/重复键。
    // 【怎么算红】迁移不加列/填造旧指纹或更改唯一性，SQL/身份断言失败。
    @Test void additiveMigrationKeepsLegacyRowsAndKeyConstraints() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:api-fingerprint-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        try {
            jdbc.execute("CREATE TABLE api_call_log(request_id VARCHAR(64) NOT NULL UNIQUE)");
            jdbc.update("INSERT INTO api_call_log(request_id) VALUES (?)", "old");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V57__api_request_fingerprint.sql")).execute(ds);
            assertNull(jdbc.queryForObject("SELECT request_fingerprint FROM api_call_log WHERE request_id='old'", String.class));
            jdbc.update("INSERT INTO api_call_log(request_id,request_fingerprint) VALUES (?,?)", "a".repeat(64), "b".repeat(64));
            assertEquals("b".repeat(64), jdbc.queryForObject("SELECT request_fingerprint FROM api_call_log WHERE request_id=?", String.class, "a".repeat(64)));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.update("INSERT INTO api_call_log(request_id) VALUES (?)", "old"));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.update("INSERT INTO api_call_log(request_id) VALUES (?)", "a".repeat(65)));
        } finally { jdbc.execute("DROP ALL OBJECTS"); }
    }
}
