package com.example.upbit.security;

import com.example.upbit.db.AppUserEntity;
import com.example.upbit.db.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * CLI 사용자 등록 유틸리티.
 *
 * 사용법:
 *   java -jar app.jar --add-user=admin:myPassword123
 *
 * 또는 환경변수:
 *   ADD_USER=admin:myPassword123 java -jar app.jar
 *
 * 사용자가 추가된 후 서버가 정상 기동됩니다 (종료되지 않음).
 * 기존 사용자명이 있으면 비밀번호를 갱신합니다.
 */
@Component
@Order(1)
public class UserRegistrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationRunner.class);

    private final AppUserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    public UserRegistrationRunner(AppUserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        String addUser = null;

        // 1. CLI 인자에서 --add-user=xxx:yyy 찾기
        for (String arg : args) {
            if (arg.startsWith("--add-user=")) {
                addUser = arg.substring("--add-user=".length());
                break;
            }
        }

        // 2. 환경변수에서 찾기
        if (addUser == null) {
            addUser = System.getenv("ADD_USER");
        }

        // 3. 시스템 프로퍼티에서 찾기
        if (addUser == null) {
            addUser = System.getProperty("add.user");
        }

        if (addUser == null || addUser.trim().isEmpty()) {
            return; // 사용자 등록 요청 없음 → 정상 기동
        }

        String[] parts = addUser.split(":", 2);
        if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            log.error("잘못된 형식입니다. 사용법: --add-user=아이디:비밀번호");
            return;
        }

        String username = parts[0].trim();
        String rawPassword = parts[1].trim();

        if (rawPassword.length() < 8) {
            log.error("비밀번호는 8자 이상이어야 합니다.");
            return;
        }

        String hashed = passwordEncoder.encode(rawPassword);

        if (userRepo.existsByUsername(username)) {
            // 기존 사용자 → 비밀번호 갱신
            AppUserEntity existing = userRepo.findByUsername(username).get();
            existing.setPassword(hashed);
            userRepo.save(existing);
            log.info("★ 사용자 비밀번호 갱신 완료: {}", username);
        } else {
            // 새 사용자 생성
            AppUserEntity user = new AppUserEntity();
            user.setUsername(username);
            user.setPassword(hashed);
            userRepo.save(user);
            log.info("★ 사용자 생성 완료: {}", username);
        }

        log.info("서버가 정상 기동됩니다. 사용자 등록이 완료되었습니다.");
    }
}
