-- 인증 사용자 테이블
CREATE TABLE IF NOT EXISTS app_users (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(100)  NOT NULL UNIQUE,
    password   VARCHAR(255)  NOT NULL,
    created_at TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);
