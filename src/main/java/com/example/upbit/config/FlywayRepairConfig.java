package com.example.upbit.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 마이그레이션 실행 전 자동 repair 수행.
 * 마이그레이션 파일의 체크섬이 변경된 경우 (V33 등)
 * 스키마 히스토리를 자동 갱신하여 checksum mismatch 오류를 방지합니다.
 */
@Configuration
public class FlywayRepairConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayRepairConfig.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return new FlywayMigrationStrategy() {
            @Override
            public void migrate(Flyway flyway) {
                log.info("[Flyway] Running repair before migrate to fix any checksum mismatches...");
                flyway.repair();
                flyway.migrate();
            }
        };
    }
}
