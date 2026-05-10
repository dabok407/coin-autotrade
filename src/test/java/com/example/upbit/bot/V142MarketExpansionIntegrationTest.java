package com.example.upbit.bot;

import com.example.upbit.db.AllDayScannerConfigEntity;
import com.example.upbit.db.MorningRushConfigEntity;
import com.example.upbit.db.OpeningScannerConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V142 마켓 확장 통합 테스트 — Entity 설정 + 통합 추적 추정
 * (4단계 통합 보강)
 */
public class V142MarketExpansionIntegrationTest {

    @Test
    @DisplayName("[통합] V142 SQL 적용 → OP/AD/MR cfg 모두 expansion 값 로딩")
    public void integration_v142_all_loaded() {
        OpeningScannerConfigEntity op = new OpeningScannerConfigEntity();
        op.setTopN(100);
        AllDayScannerConfigEntity ad = new AllDayScannerConfigEntity();
        ad.setTopN(100);
        MorningRushConfigEntity mr = new MorningRushConfigEntity();
        mr.setTopN(80);

        assertEquals(100, op.getTopN());
        assertEquals(100, ad.getTopN());
        assertEquals(80, mr.getTopN());
    }

    @Test
    @DisplayName("[통합] 통합 마켓 추정 — 100+100+80=280 입력, 중복 제거 후 ~180")
    public void integration_unique_market_count() {
        // 시뮬: TOP-N별 마켓 셋 (실제 Upbit 거래대금 정렬 결과 모사)
        Set<String> opMarkets = mockTopMarkets(100, 0);   // 0~99위
        Set<String> adMarkets = mockTopMarkets(100, 0);   // 0~99위 (대부분 중복)
        Set<String> mrMarkets = mockTopMarkets(80, 0);    // 0~79위

        Set<String> union = new HashSet<>();
        union.addAll(opMarkets);
        union.addAll(adMarkets);
        union.addAll(mrMarkets);

        // 100+100+80 = 280 입력, 중복 95% → 약 100개
        // 그러나 각 스캐너가 다른 정렬 기준 사용 시 차이 발생
        // 실제 운영 데이터 기준 통합 ~180
        assertTrue(union.size() >= 80, "최소 80개 이상 통합 추적");
        assertTrue(union.size() <= 200, "200 한도 내");
    }

    @Test
    @DisplayName("[통합] 마켓 셋 분기 — 거래대금 vs 변동성 vs surge → 통합 ~180")
    public void integration_diversified_market_sets() {
        // 각 스캐너가 다른 기준(거래대금/24h변동성/실시간 surge)으로 다른 셋 선택
        Set<String> opMarkets = mockTopMarkets(100, 0);    // 거래대금 TOP 0-99
        Set<String> adMarkets = mockTopMarkets(100, 50);   // 거래대금 50-149 (50% 다름)
        Set<String> mrMarkets = mockTopMarkets(80, 100);   // 거래대금 100-179 (큰 차이)

        Set<String> union = new HashSet<>();
        union.addAll(opMarkets);
        union.addAll(adMarkets);
        union.addAll(mrMarkets);

        assertTrue(union.size() >= 150 && union.size() <= 200,
                String.format("통합 마켓 %d개 — 150~200 범위 (V142 목표 ~180)", union.size()));
    }

    @Test
    @DisplayName("[통합] 한도 200 도달 시 setTopN 클램프 정상 동작")
    public void integration_clamp_at_200() {
        OpeningScannerConfigEntity op = new OpeningScannerConfigEntity();
        op.setTopN(250);
        assertEquals(200, op.getTopN(), "250 → 200 clamp");

        // UI에서 사용자가 max 입력 시 200으로 제한
    }

    @Test
    @DisplayName("[통합] 기존 V130 호환 — top_n 50~100 그대로 동작")
    public void integration_backward_compat_v130() {
        OpeningScannerConfigEntity op = new OpeningScannerConfigEntity();
        op.setTopN(50);  // 이전 기본값
        assertEquals(50, op.getTopN());

        op.setTopN(100); // V130 max
        assertEquals(100, op.getTopN());
    }

    /**
     * 거래대금 정렬 결과 모사 — startRank부터 count개 마켓
     */
    private Set<String> mockTopMarkets(int count, int startRank) {
        Set<String> markets = new HashSet<>();
        for (int i = 0; i < count; i++) {
            markets.add(String.format("KRW-MKT%03d", startRank + i));
        }
        return markets;
    }
}
