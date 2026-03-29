package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MorningRushConfigRepository extends JpaRepository<MorningRushConfigEntity, Integer> {

    /**
     * 설정은 항상 id=1 단일 행. 없으면 기본값으로 생성.
     */
    default MorningRushConfigEntity loadOrCreate() {
        return findById(1).orElseGet(() -> {
            MorningRushConfigEntity e = new MorningRushConfigEntity();
            return save(e);
        });
    }
}
