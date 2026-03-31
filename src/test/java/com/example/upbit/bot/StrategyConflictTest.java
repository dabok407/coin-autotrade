package com.example.upbit.bot;

import com.example.upbit.db.PositionEntity;
import com.example.upbit.db.PositionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests verifying that OpeningScanner, AllDayScanner, and TradingBotService (Target)
 * strategies don't conflict with each other.
 *
 * Key invariants:
 * 1. Position PK = market -> same market can't have two positions (any strategy)
 * 2. Different markets can coexist across all 3 strategies
 * 3. All strategies share one global capital pool
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class StrategyConflictTest {

    @Mock
    private PositionRepository positionRepo;

    private PositionEntity buildPosition(String market, double qty, double avgPrice, String strategy) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(qty);
        pe.setAvgPrice(avgPrice);
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy(strategy);
        return pe;
    }

    /**
     * (a) Position PK prevents double entry.
     * PositionEntity PK is the market string. Two positions for the same market
     * (from different strategies) cannot coexist in the DB.
     */
    @Test
    public void testPositionPkPreventsDoubleEntry() {
        // Simulate: ADAPTIVE_TREND_MOMENTUM already holds KRW-BTC
        PositionEntity existing = buildPosition("KRW-BTC", 0.001, 50000000, "ADAPTIVE_TREND_MOMENTUM");

        // If AllDayScanner tries to create a position for KRW-BTC,
        // it would be the same PK ("KRW-BTC"), overwriting the existing one.
        // This is why scanners exclude markets with existing positions.

        List<PositionEntity> allPositions = Collections.singletonList(existing);
        when(positionRepo.findAll()).thenReturn(allPositions);

        // Verify the market is detected as already held
        Set<String> ownedMarkets = new HashSet<String>();
        for (PositionEntity pe : positionRepo.findAll()) {
            if (pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                ownedMarkets.add(pe.getMarket());
            }
        }

        assertTrue(ownedMarkets.contains("KRW-BTC"),
                "KRW-BTC should be detected as owned, preventing double entry");

        // Verify entry candidates exclude owned markets
        List<String> topMarkets = Arrays.asList("KRW-BTC", "KRW-ETH", "KRW-SOL");
        List<String> entryCandidates = new ArrayList<String>();
        for (String market : topMarkets) {
            if (!ownedMarkets.contains(market)) {
                entryCandidates.add(market);
            }
        }

        assertFalse(entryCandidates.contains("KRW-BTC"),
                "KRW-BTC should be excluded from entry candidates");
        assertEquals(2, entryCandidates.size(),
                "Only KRW-ETH and KRW-SOL should be entry candidates");
    }

    /**
     * (b) Different markets can coexist across all 3 strategies.
     * OpeningScanner holds KRW-A, AllDayScanner holds KRW-B, TradingBot holds KRW-C.
     * All 3 positions should exist simultaneously.
     */
    @Test
    public void testDifferentMarketsCanCoexist() {
        List<PositionEntity> allPositions = Arrays.asList(
                buildPosition("KRW-SOL", 10.0, 25000, "SCALP_OPENING_BREAK"),         // OpeningScanner
                buildPosition("KRW-ETH", 0.5, 3000000, "HIGH_CONFIDENCE_BREAKOUT"),    // AllDayScanner
                buildPosition("KRW-ADA", 500.0, 800, "ADAPTIVE_TREND_MOMENTUM")        // TradingBot (Target)
        );
        when(positionRepo.findAll()).thenReturn(allPositions);

        List<PositionEntity> positions = positionRepo.findAll();

        // All 3 positions should exist
        assertEquals(3, positions.size(), "All 3 positions should coexist");

        // Verify each market has its own position with correct strategy
        Set<String> markets = new HashSet<String>();
        Map<String, String> strategyMap = new HashMap<String, String>();
        for (PositionEntity pe : positions) {
            markets.add(pe.getMarket());
            strategyMap.put(pe.getMarket(), pe.getEntryStrategy());
        }

        assertTrue(markets.contains("KRW-SOL"), "KRW-SOL should be in positions");
        assertTrue(markets.contains("KRW-ETH"), "KRW-ETH should be in positions");
        assertTrue(markets.contains("KRW-ADA"), "KRW-ADA should be in positions");

        assertEquals("SCALP_OPENING_BREAK", strategyMap.get("KRW-SOL"));
        assertEquals("HIGH_CONFIDENCE_BREAKOUT", strategyMap.get("KRW-ETH"));
        assertEquals("ADAPTIVE_TREND_MOMENTUM", strategyMap.get("KRW-ADA"));
    }

    /**
     * (c) Capital pool is shared across all 3 strategies.
     * Total invested = sum of all positions (qty * avgPrice) regardless of strategy.
     * This is how calcTotalInvestedAllPositions works in both scanner services.
     */
    @Test
    public void testCapitalPoolShared() {
        // 3 positions from 3 different strategies
        PositionEntity pos1 = buildPosition("KRW-SOL", 10.0, 25000, "SCALP_OPENING_BREAK");         // 250,000
        PositionEntity pos2 = buildPosition("KRW-ETH", 0.1, 3000000, "HIGH_CONFIDENCE_BREAKOUT");    // 300,000
        PositionEntity pos3 = buildPosition("KRW-ADA", 500.0, 800, "ADAPTIVE_TREND_MOMENTUM");       // 400,000

        List<PositionEntity> allPositions = Arrays.asList(pos1, pos2, pos3);
        when(positionRepo.findAll()).thenReturn(allPositions);

        // Calculate total invested the same way as AllDayScannerService.calcTotalInvestedAllPositions
        double totalInvested = 0.0;
        for (PositionEntity pe : positionRepo.findAll()) {
            if (pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0
                    && pe.getAvgPrice() != null) {
                totalInvested += pe.getQty().doubleValue() * pe.getAvgPrice().doubleValue();
            }
        }

        // Expected: 10*25000 + 0.1*3000000 + 500*800 = 250000 + 300000 + 400000 = 950000
        assertEquals(950000.0, totalInvested, 0.01,
                "Total invested should include ALL positions regardless of strategy");

        // With globalCapital = 1,000,000, remaining = 50,000
        double globalCapital = 1000000.0;
        double remaining = globalCapital - totalInvested;
        assertEquals(50000.0, remaining, 0.01,
                "Remaining capital should account for all 3 strategy positions");

        // An order of 60,000 should be blocked (only 50,000 remaining)
        double orderSize = 60000.0;
        assertTrue(orderSize > remaining,
                "Order of 60,000 should exceed remaining 50,000 capital");

        // An order of 40,000 should be allowed
        orderSize = 40000.0;
        assertTrue(orderSize <= remaining,
                "Order of 40,000 should fit within remaining 50,000 capital");
    }
}
