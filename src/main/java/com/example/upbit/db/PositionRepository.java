package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PositionRepository extends JpaRepository<PositionEntity, String> {

    @Query("SELECT COUNT(p) FROM PositionEntity p WHERE p.qty > 0 AND p.entryStrategy = ?1")
    int countActiveByEntryStrategy(String entryStrategy);
}
