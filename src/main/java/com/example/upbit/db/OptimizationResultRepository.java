package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OptimizationResultRepository extends JpaRepository<OptimizationResultEntity, Long> {

    List<OptimizationResultEntity> findByRunIdOrderByRoiDesc(String runId);

    List<OptimizationResultEntity> findByRunIdAndMarketOrderByRoiDesc(String runId, String market);

    long countByRunId(String runId);

    void deleteByRunId(String runId);

    // Phase별 조회
    List<OptimizationResultEntity> findByRunIdAndPhaseOrderByRoiDesc(String runId, int phase);

    List<OptimizationResultEntity> findByRunIdAndPhaseAndMarketOrderByRoiDesc(String runId, int phase, String market);
}
