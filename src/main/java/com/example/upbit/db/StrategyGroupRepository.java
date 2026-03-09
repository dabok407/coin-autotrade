package com.example.upbit.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategyGroupRepository extends JpaRepository<StrategyGroupEntity, Long> {
    List<StrategyGroupEntity> findAllByOrderBySortOrderAsc();
}
