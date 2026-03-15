package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OpeningScannerConfigRepository extends JpaRepository<OpeningScannerConfigEntity, Integer> {

    /**
     * 설정은 항상 id=1 단일 행. 없으면 기본값으로 생성.
     */
    default OpeningScannerConfigEntity loadOrCreate() {
        return findById(1).orElseGet(() -> {
            OpeningScannerConfigEntity e = new OpeningScannerConfigEntity();
            return save(e);
        });
    }
}
