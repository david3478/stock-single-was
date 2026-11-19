package com.example.stocksinglewas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.stocksinglewas.support.MySqlIntegrationTest;

class StockSingleWasApplicationTests extends MySqlIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsAgainstMySql() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);

        assertThat(version).startsWith("8.4");
    }

    /** ADR 0003은 AUTO_INCREMENT가 커밋이 아니라 INSERT 시점에 발급되는 운영 기본값을 전제로 검증한다. */
    @Test
    void usesInterleavedAutoIncrementLockMode() {
        Integer mode = jdbc.queryForObject("SELECT @@innodb_autoinc_lock_mode", Integer.class);

        assertThat(mode).isEqualTo(2);
    }
}
