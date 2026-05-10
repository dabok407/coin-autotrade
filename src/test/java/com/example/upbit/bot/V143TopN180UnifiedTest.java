package com.example.upbit.bot;

import com.example.upbit.db.AllDayScannerConfigEntity;
import com.example.upbit.db.MorningRushConfigEntity;
import com.example.upbit.db.OpeningScannerConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V143: 3 스캐너 top_n 180 통일 테스트
 *
 * V142 (OP=100, AD=100, MR=80) → V143 (모두 180)
 * 통합 추적 ~200+ 마켓 (거의 모든 KRW 마켓)
 */
public class V143TopN180UnifiedTest {

    @Test
    @DisplayName("[V143] OpeningScanner top_n 180 통과 (코드 한도 200 내)")
    public void op_180_pass() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setTopN(180);
        assertEquals(180, cfg.getTopN());
    }

    @Test
    @DisplayName("[V143] AllDayScanner top_n 180 통과")
    public void ad_180_pass() {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setTopN(180);
        assertEquals(180, cfg.getTopN());
    }

    @Test
    @DisplayName("[V143] MorningRush top_n 180 통과 (이전 80 → 180)")
    public void mr_180_pass() {
        MorningRushConfigEntity cfg = new MorningRushConfigEntity();
        cfg.setTopN(180);
        assertEquals(180, cfg.getTopN());
    }

    @Test
    @DisplayName("[V143 통합] 3 스캐너 모두 180 동시 설정 → 통합 추적 ~200+ 추정")
    public void integration_all_three_180() {
        OpeningScannerConfigEntity op = new OpeningScannerConfigEntity();
        AllDayScannerConfigEntity ad = new AllDayScannerConfigEntity();
        MorningRushConfigEntity mr = new MorningRushConfigEntity();
        op.setTopN(180);
        ad.setTopN(180);
        mr.setTopN(180);

        assertEquals(180, op.getTopN());
        assertEquals(180, ad.getTopN());
        assertEquals(180, mr.getTopN());

        // 통합 추적 추정: 같은 거래대금 정렬 사용 시 중복 95%+
        // 유니크 ~180, 그러나 진입 phase 다양성으로 ~200+ 도달 가능
        int sumInputs = op.getTopN() + ad.getTopN() + mr.getTopN();
        assertEquals(540, sumInputs, "3 스캐너 입력 합계 540");
    }

    @Test
    @DisplayName("[V143] V142 호환 — 50/100 등 이전 값으로도 동작")
    public void backward_compat() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setTopN(50);
        assertEquals(50, cfg.getTopN());
        cfg.setTopN(100);
        assertEquals(100, cfg.getTopN());
    }

    @Test
    @DisplayName("[V143 한계] 220 입력 → 200 clamp (V142 max 200 정책)")
    public void clamp_at_200() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setTopN(220);
        assertEquals(200, cfg.getTopN(), "180 권고이지만 사용자가 200 초과 시 200 clamp");
    }
}
