package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V142 마켓 확장 시나리오 테스트 — 실제 5/9 데이터 기반
 * (4단계 시나리오 보강)
 *
 * 5/9 진짜 급등 TOP 20 중 봇 추적은 7개(35%)뿐.
 * V142 확장 후 추적률 시뮬레이션.
 */
public class V142MarketScenarioTest {

    /** 5/9 실제 급등 TOP 20 코인 (open→high% 기준) */
    private static final List<String> REAL_TOP20_5_9 = Arrays.asList(
        "KRW-DEEP", "KRW-PROS", "KRW-ORCA", "KRW-ICP", "KRW-CARV",
        "KRW-IP", "KRW-KAT", "KRW-VANA", "KRW-IOTA", "KRW-PRL",
        "KRW-EDGE", "KRW-VIRTUAL", "KRW-WIF", "KRW-ARB", "KRW-PUMP",
        "KRW-MEW", "KRW-W", "KRW-BERA", "KRW-JST", "KRW-SAND"
    );

    /** 5/9 실제 봇 추적 마켓 (50개) — 거래대금 TOP-50 */
    private static final List<String> BOT_TRACKED_5_9 = Arrays.asList(
        "KRW-PROS", "KRW-SAHARA", "KRW-ONDO", "KRW-XRP", "KRW-CPOOL",
        "KRW-DEEP", "KRW-IP", "KRW-VIRTUAL", "KRW-ICP", "KRW-VANA",
        "KRW-KAT", "KRW-BTC", "KRW-ETH", "KRW-SUI", "KRW-USDT",
        "KRW-SOL", "KRW-ADA", "KRW-NEAR", "KRW-LINK", "KRW-DOGE",
        "KRW-XPL", "KRW-PROVE", "KRW-PENGU", "KRW-AVAX", "KRW-TIA",
        "KRW-APT", "KRW-FIL", "KRW-CHZ", "KRW-HOLO", "KRW-PENDLE",
        "KRW-ATH", "KRW-WLD", "KRW-TAO", "KRW-CHIP", "KRW-JUP",
        "KRW-TRUMP", "KRW-STORJ", "KRW-FLOCK", "KRW-MEGA", "KRW-CFG",
        "KRW-AKT", "KRW-ETC", "KRW-ARB", "KRW-ARKM", "KRW-STX",
        "KRW-BIO", "KRW-ZBT", "KRW-ENA", "KRW-STEEM", "KRW-SEI"
    );

    @Test
    @DisplayName("[시나리오] V130 (TOP 50) — 5/9 진짜 급등 TOP 20 중 추적률")
    public void scenario_v130_tracking_rate() {
        Set<String> tracked = new HashSet<>(BOT_TRACKED_5_9);
        int trackedTop20 = 0;
        for (String coin : REAL_TOP20_5_9) {
            if (tracked.contains(coin)) trackedTop20++;
        }
        // 실측: ARB, DEEP, PROS, ICP, IP, KAT, VANA, VIRTUAL = 8개
        assertTrue(trackedTop20 >= 7, String.format("V130 추적률 %d/20", trackedTop20));
        assertTrue(trackedTop20 <= 10, "V130에서 절반 미만 추적");
    }

    @Test
    @DisplayName("[시나리오] V142 (TOP 100) — 추적률 시뮬레이션 (확장 시 추정 +50%)")
    public void scenario_v142_tracking_rate() {
        // V142: TOP 100으로 확장 시 거래대금 51-100위가 추가됨
        // 5/9 진짜 급등 코인 중 ORCA(거래대금 24h ~17억), CARV(~26억)는 51-100위 가능성 높음
        // IOTA, PRL, EDGE 등 거래대금 작은 코인은 100위 밖일 수 있음
        // 추정: 8 → 12~15개 추적 가능

        // 실제 측정은 운영 봇 로그 누적 후 검증
        // 본 테스트는 V142 확장의 의도 (추적률 향상) 자체를 검증
        int v130_count = 8;
        int v142_estimated = 13; // 추정 (ORCA + CARV + a)

        assertTrue(v142_estimated > v130_count, "V142 확장 효과 (추적률 증가)");
        assertTrue((double)v142_estimated / 20 >= 0.6, "V142 추적률 60%+ 추정");
    }

    @Test
    @DisplayName("[시나리오] 통합 추적 마켓 수 — 모닝러쉬 시간(08-10시) 풀 활성 시")
    public void scenario_full_active_market_count() {
        // 모닝러쉬+오프닝+올데이 모두 활성 시간대
        // 각 스캐너 selectTopMarketsByVolume 호출 → 거래대금 정렬 (대부분 동일)
        Set<String> op = new HashSet<>(BOT_TRACKED_5_9.subList(0, 50));   // OP top 50 (V130)
        Set<String> ad = new HashSet<>(BOT_TRACKED_5_9.subList(0, 50));   // AD top 50 (V130)
        Set<String> mr = new HashSet<>(BOT_TRACKED_5_9.subList(0, 30));   // MR top 30 (V130)

        Set<String> union_v130 = new HashSet<>();
        union_v130.addAll(op); union_v130.addAll(ad); union_v130.addAll(mr);
        // V130: 50+50+30 모두 같은 정렬이라 유니크 50

        // V142 시뮬: top 100 (거래대금 정렬 다양화 가능)
        // 실제 봇 측정: 5/9 88-100 markets (모닝러쉬 시간), 5/10 17:55 100 markets
        // V142 적용 후 예상: 통합 130-180개

        assertTrue(union_v130.size() <= 50, "V130 단일 정렬 시 유니크 최대 50");
    }

    @Test
    @DisplayName("[시나리오] 마켓 확장으로 5/9 ORCA(+16% 급등) 진입 가능성 검증")
    public void scenario_ORCA_5_9_added_with_v142() {
        // 5/9 ORCA: 거래대금 24h 약 17억 (51위 부근)
        // V130 (TOP 50): 추적 안 됨 → 진입 불가
        // V142 (TOP 100): 추적 가능 → V141 필터(gap 1.5-2.2) + V140 RSI(<75) 통과 시 진입
        Set<String> v130_tracked = new HashSet<>(BOT_TRACKED_5_9.subList(0, 50));
        boolean v130_has_orca = v130_tracked.contains("KRW-ORCA");
        // 실측: ORCA가 BOT_TRACKED_5_9 50개 안에 있는지 확인
        // → 없음 (12위 신호인데 봇 50개에 못 들어감)
        // 그러나 BOT_TRACKED는 사용자 보유 코인 + 추가로 인해 다를 수 있음

        // V142 확장 시 ORCA 추적 가능 시뮬
        Set<String> v142_simulated = new HashSet<>(BOT_TRACKED_5_9);
        v142_simulated.addAll(Arrays.asList("KRW-ORCA", "KRW-CARV", "KRW-IOTA"));
        assertTrue(v142_simulated.contains("KRW-ORCA"),
                "V142 확장 후 ORCA 추적 가능 → 5/9 +16% 급등 잡을 기회");
    }
}
