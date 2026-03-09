package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {
    Optional<AppUserEntity> findByUsername(String username);
    boolean existsByUsername(String username);
}
