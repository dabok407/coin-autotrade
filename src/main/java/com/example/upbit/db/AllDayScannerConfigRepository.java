package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AllDayScannerConfigRepository extends JpaRepository<AllDayScannerConfigEntity, Integer> {

    /**
     * 설정은 항상 id=1 단일 행. 없으면 기본값으로 생성.
     */
    default AllDayScannerConfigEntity loadOrCreate() {
        return findById(1).orElseGet(() -> {
            AllDayScannerConfigEntity e = new AllDayScannerConfigEntity();
            return save(e);
        });
    }
}
