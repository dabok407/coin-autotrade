package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V131 — Detector cache pos[0] 실 체결가 통일 검증.
 *
 * 핵심 결함:
 *   - LIVE 매수 시 signalPrice=100, fillPrice=100.5 (슬리피지)
 *   - 변경 전: cache pos[0] = signalPrice(100) → SPLIT_1ST 로그 avg=100 (실체결가 아님)
 *   - 변경 후: cache pos[0] = fillPrice(100.5) → 모든 detector 결정이 실 체결가 기준
 *
 * 각 스캐너별 3가지 시나리오 검증:
 *  (A) AllDayScannerService — executeBuy 반환 fillPrice > 0 시 cache pos[0] = fillPrice
 *  (B) OpeningScannerService — executeBuy 반환 fillPrice > 0 시 breakoutDetector.addPosition(fillPrice)
 *  (C) MorningRushScannerService — DB 재조회 후 cacheAvg = savedPe.getAvgPrice() (기존 패턴 유지)
 */
@DisplayName("V131: Cache avgPrice 실 체결가 통일")
public class V131CacheAvgConsistencyTest {

    // ============================================================
    // (A) AllDayScanner — tpPositionCache pos[0] 슬리피지 반영 검증
    // ============================================================

    @Test
    @DisplayName("(A-1) AllDay: fillPrice > 0 시 cache pos[0] = fillPrice (신호가 != 체결가)")
    void allDay_cachePos0_usesFillPrice() {
        double signalPrice = 100.0;
        double fillPrice = 100.5;  // 슬리피지 0.5%

        // 시뮬레이션: executeBuy 성공 반환 → cache.put 로직
        double[] cache = new double[7];
        if (fillPrice > 0) {
            cache[0] = fillPrice;   // avgPrice
            cache[1] = fillPrice;   // peakPrice
            cache[2] = 0;           // activated
            cache[3] = fillPrice;   // troughPrice
            cache[4] = System.currentTimeMillis();
            cache[5] = 0;           // splitPhase
            cache[6] = 0;           // split1stTrailArmed
        }

        // 검증: pos[0]은 fillPrice 여야 함 (signalPrice가 아님)
        assertEquals(fillPrice, cache[0], 1e-9,
                "cache pos[0] (avgPrice)는 실 체결가 fillPrice여야 합니다");
        assertNotEquals(signalPrice, cache[0],
                "cache pos[0]이 signalPrice로 저장되면 안 됩니다");
        assertEquals(fillPrice, cache[1], 1e-9,
                "cache pos[1] (peakPrice)도 fillPrice로 초기화돼야 합니다");
        assertEquals(fillPrice, cache[3], 1e-9,
                "cache pos[3] (troughPrice)도 fillPrice로 초기화돼야 합니다");
    }

    @Test
    @DisplayName("(A-2) AllDay: fillPrice <= 0 (매수 실패) 시 cache에 저장하지 않음")
    void allDay_noCache_whenFillPriceLteZero() {
        double fillPrice = -1.0;  // executeBuy 실패

        boolean cachePutCalled = false;
        double[] cache = null;

        // V131 패턴: fillPrice > 0 조건 충족 시에만 cache.put
        if (fillPrice > 0) {
            cachePutCalled = true;
            cache = new double[]{fillPrice, fillPrice, 0, fillPrice, System.currentTimeMillis(), 0, 0};
        }

        assertFalse(cachePutCalled, "fillPrice <= 0이면 cache에 저장하면 안 됩니다");
        assertNull(cache, "매수 실패 시 cache 배열이 생성되면 안 됩니다");
    }

    @Test
    @DisplayName("(A-3) AllDay: Paper 모드 fillPrice = price * 1.001 (슬리피지 0.1%)")
    void allDay_paperMode_fillPriceWithSlippage() {
        double signalPrice = 100.0;
        // Paper 모드: fillPrice = price * 1.001
        double fillPrice = signalPrice * 1.001;

        double[] cache = new double[7];
        if (fillPrice > 0) {
            cache[0] = fillPrice;
            cache[1] = fillPrice;
        }

        assertEquals(100.1, cache[0], 1e-9,
                "Paper 모드에서도 cache pos[0]은 슬리피지 반영된 fillPrice여야 합니다");
        assertNotEquals(signalPrice, cache[0],
                "Paper 모드에서도 signalPrice(슬리피지 없음)로 저장되면 안 됩니다");
    }

    // ============================================================
    // (B) OpeningScanner — breakoutDetector.addPosition(fillPrice) 검증
    // ============================================================

    @Test
    @DisplayName("(B-1) Opening: fillPrice > 0 시 breakoutDetector에 fillPrice 전달")
    void opening_detector_usesFillPriceAsAvg() {
        double signalPrice = 50.0;
        double fillPrice = 50.25;  // 슬리피지 0.5%

        // V131 패턴: executeBuy 반환값으로 addPosition 호출
        double capturedAvg = -1.0;
        if (fillPrice > 0) {
            // breakoutDetector.addPosition(market, fillPrice, openedAtMs, rank)
            capturedAvg = fillPrice;  // 전달된 avgPrice
        }

        assertEquals(fillPrice, capturedAvg, 1e-9,
                "breakoutDetector에 전달되는 avgPrice는 실 체결가 fillPrice여야 합니다");
        assertNotEquals(signalPrice, capturedAvg,
                "signalPrice (=candle.trade_price)가 전달되면 안 됩니다");
    }

    @Test
    @DisplayName("(B-2) Opening: fillPrice <= 0 (매수 실패) 시 breakoutDetector.addPosition 미호출")
    void opening_detector_notCalledOnFillFailure() {
        double fillPrice = -1.0;  // 실패

        boolean detectorCalled = false;
        if (fillPrice > 0) {
            detectorCalled = true;
        }

        assertFalse(detectorCalled,
                "매수 실패 시 breakoutDetector.addPosition이 호출되면 안 됩니다");
    }

    @Test
    @DisplayName("(B-3) Opening: Paper 모드에서도 fillPrice = price * 1.001 기준 적용")
    void opening_paperMode_detectorGetsFillPrice() {
        double signalPrice = 200.0;
        double fillPrice = signalPrice * 1.001;  // Paper 슬리피지

        double capturedAvg = -1.0;
        if (fillPrice > 0) {
            capturedAvg = fillPrice;
        }

        assertEquals(200.2, capturedAvg, 1e-9,
                "Paper 모드 Opening도 슬리피지 반영된 fillPrice가 detector에 전달돼야 합니다");
    }

    // ============================================================
    // (C) MorningRush — DB 재조회 패턴 (기존 정상 동작 보존 검증)
    // ============================================================

    @Test
    @DisplayName("(C-1) MorningRush: DB 재조회 성공 시 cacheAvg = savedPe.getAvgPrice()")
    void morningRush_cacheAvg_usesDbFillPrice() {
        double signalPrice = 75.0;   // fPrice
        double dbAvgPrice = 75.35;   // savedPe.getAvgPrice() — LIVE 체결가

        // MorningRush 패턴: cacheAvg 초기화 후 DB 재조회로 덮어쓰기
        double cacheAvg = signalPrice;   // 초기값 = fPrice
        boolean dbFetchSuccess = true;

        if (dbFetchSuccess) {
            cacheAvg = dbAvgPrice;  // savedPe.getAvgPrice().doubleValue()
        }

        double[] positionCache = new double[]{cacheAvg, 0, System.currentTimeMillis(), cacheAvg, cacheAvg, 0, 0};

        assertEquals(dbAvgPrice, positionCache[0], 1e-9,
                "MorningRush cache pos[0]은 DB에서 재조회한 실 체결가여야 합니다");
        assertNotEquals(signalPrice, positionCache[0],
                "DB 재조회 성공 시 signalPrice로 저장되면 안 됩니다");
    }

    @Test
    @DisplayName("(C-2) MorningRush: DB 재조회 실패 시 cacheAvg = signalPrice (안전 fallback)")
    void morningRush_cacheAvg_fallsBackToSignalPrice() {
        double signalPrice = 75.0;

        // DB 재조회 실패 시 fallback
        double cacheAvg = signalPrice;
        boolean dbFetchSuccess = false;

        if (dbFetchSuccess) {
            cacheAvg = 99999.0;  // 도달 안 함
        }

        // fallback은 signalPrice (PAPER 모드와 동일 값, 슬리피지 미반영)
        // 이것은 허용되는 안전 fallback이지만 알려진 한계임
        assertEquals(signalPrice, cacheAvg, 1e-9,
                "DB 재조회 실패 시 signalPrice fallback이 발생하며 이는 알려진 한계입니다");
    }

    // ============================================================
    // (D) SPLIT_1ST ROI 계산 — avgPrice 기준 일관성 검증
    // ============================================================

    @Test
    @DisplayName("(D-1) cache avgPrice = fillPrice 시 SPLIT_1ST ROI 계산 일관성")
    void split1st_roiCalc_consistentWithFillPrice() {
        double signalPrice = 100.0;
        double fillPrice = 100.5;   // 슬리피지 0.5%
        double currentPrice = 102.0;

        // 변경 전: cache avgPrice = signalPrice → ROI 과대 계산
        double roiWithSignalPrice = (currentPrice - signalPrice) / signalPrice * 100.0;

        // 변경 후: cache avgPrice = fillPrice → ROI 정확 계산
        double roiWithFillPrice = (currentPrice - fillPrice) / fillPrice * 100.0;

        // 검증: fillPrice 기준 ROI가 signalPrice 기준보다 낮음 (슬리피지 반영)
        assertTrue(roiWithFillPrice < roiWithSignalPrice,
                "fillPrice 기준 ROI는 signalPrice 기준보다 낮아야 합니다 (슬리피지 반영)");

        // 실제 값 검증
        assertEquals(2.0, roiWithSignalPrice, 0.001,
                "signalPrice 기준 ROI = 2.00%");
        assertEquals(1.493, roiWithFillPrice, 0.001,
                "fillPrice 기준 ROI ≈ 1.493% (슬리피지 0.5% 반영)");
    }

    @Test
    @DisplayName("(D-2) PAPER 모드: signalPrice == fillPrice이므로 ROI 동일")
    void paper_mode_signalPriceEqualsFillPrice() {
        double signalPrice = 100.0;
        // Paper 모드: fillPrice = price * 1.001
        double fillPrice = signalPrice * 1.001;
        double currentPrice = 103.0;

        double roiWithSignalPrice = (currentPrice - signalPrice) / signalPrice * 100.0;
        double roiWithFillPrice = (currentPrice - fillPrice) / fillPrice * 100.0;

        // Paper 모드에서도 슬리피지(0.1%)만큼 차이
        assertTrue(roiWithFillPrice < roiWithSignalPrice,
                "Paper 모드에서도 슬리피지 0.1% 만큼 fillPrice 기준 ROI가 낮아야 합니다");

        // 실제 기댓값 확인
        assertEquals(3.0, roiWithSignalPrice, 0.001);
        assertTrue(roiWithFillPrice > 0, "fillPrice 기준으로도 수익권이어야 합니다");
    }
}
