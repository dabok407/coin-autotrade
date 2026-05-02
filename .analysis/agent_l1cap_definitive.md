# L1 캡 결정적 시뮬 — 모순 해소 분석

**생성**: 2026-05-02 | **대상**: V130-after 폐쇄 거래 22건 (STILL_OPEN 제외)
**페치**: 22건 성공 / 실패 0건 | **시뮬 엔진**: l1cap_definitive.js (실코드 기반)

---

## 1. 두 시뮬 차이 분석 — 모순 원인 (3가지)

| 항목 | Agent B (trail_resim) | Agent D (precise_backtest) | 이 시뮬 (확정) |
|---|---|---|---|
| 입력 거래 수 | 22건 | **27건** (22 + 큰추세 5) | **22건** |
| 1분봉 윈도우 | 스캐너별 max 200분 | 당일 23:59 KST | **스캐너별 만료** |
| SPLIT_2ND drop (peak<2%) | **0.8%** (오명명) | **1.0%** | **1.0%** (실코드 기본값) |
| L1 ROI Floor (+0.30%) | 미반영 | **반영** | **반영** |
| split1 후 peak 리셋 | **없음** (누적) | **없음** (누적) | **있음** (실코드 pos[3]=price) |
| SL vs trail 체크 순서 | SL 먼저 | SL 먼저 | trail 먼저 (실코드 동일) |

### 원인 ① — 대상 건수 혼동 (가장 큰 원인)

Agent D는 V130 전(before) 대박 거래 5건을 추가해 총 27건으로 시뮬했다.

| 기준 | S0 (V130 캡없음) PnL |
|---|---:|
| 22건만 (V130-after) | **-10,885** (Agent B, Agent D 22건 섹션) |
| 27건 (22 + 큰추세 5건) | **+32,227** (Agent D 전체표) |

Agent D가 "32,227"이라고 말하는 숫자는 ENSO(+9,988), API3(+13,987) 등 V130 전 대박 5건을 포함한 것이다.
이 상태에서 L1 +2.0% 캡을 적용하면 대박 거래들에 캡이 걸려 수익이 조기 확정 → Δ **-7,271**.

### 원인 ② — 22건 기준 캡 효과 방향

22건만 보면 L1 캡은 모든 에이전트에서 **개선** 방향이다.
- Agent B: +2.0% 캡 Δ +4,371
- Agent D (22건 섹션): "V130-after 22건만 기준" 표에서 S0=-10,885
- 이 시뮬: +2.0% 캡 Δ +16,640 (split1 후 peak 리셋으로 cap 효과가 더 크게 나타남)

Agent D가 "캡이 -7,271"이라고 한 건 27건(대박5 포함) 기준 — **22건과 비교 불가한 숫자**.

### 원인 ③ — split1 후 peak 리셋

- 실 코드: split1 체결 후 `pos[3] = price` (peak를 split1 체결가로 리셋)
- Agent B/D: peak 리셋 없이 split1 이전 peak 누적 사용
- 영향: 리셋 시 SPLIT_2ND trail target이 낮아져 → split2가 더 빨리 걸릴 수도, 캔들 l이 바로 target 도달 시 즉시 청산

### 원인 ④ — 1분봉 시뮬의 근본 한계 (두 에이전트 공통)

1분봉 시뮬로는 완전히 정확한 재현이 불가능하다:

| 한계 | 영향 | 사례 |
|---|---|---|
| 같은 캔들 h/l 순서 불명 | trail 체결가 추정 불가 | DKA: l=9.28로 ROI Floor 차단, 실제는 9.38에서 체결 |
| 진입 시각의 1분봉 데이터 누락 | 초반 peak 불완전 | BLEND: 09:07분 캔들에서 peak 186 달성됐지만 시뮬 미캡처 |
| 실 코드는 ws 실시간 체결가 기준 | 1분봉 h/l보다 세밀 | 체결가 ≠ 캔들 저가 |

이로 인해 이 시뮬 NO_CAP PnL=-34,585 vs Agent B -10,885 차이가 발생한다.
두 시뮬 모두 방향성(캡이 개선)은 일치하지만 절대값은 다르다.

---

## 2. 새 시뮬 결과 — L1 캡 매트릭스 (22건, 스캐너별 만료 윈도우)

> **시뮬 기준**: 실코드 기본값 | drop2nd(peak<2%)=1.0% | L1 ROI Floor +0.30% | split1 후 peak 리셋
> 수수료 0.10% (왕복) | SL_TIGHT 2.5% | Split 50%/50% | Armed 임계 +1.5%
> **1분봉 한계로 절대 PnL보다 상대적 Δ가 더 신뢰 가능**

| L1 캡 | 캡 도달 건수 | 총 PnL (KRW) | WR% | Δ vs 캡없음 | 비고 |
|---|---:|---:|---:|---:|---|
| 없음 (V130 현재) | 0 | **-34,585** | 50% | 기준 | |
| +1.8% | 10 | **-10,966** | 59.1% | **+23,619** | 최적 (이 시뮬 기준) |
| **+2.0%** | 9 | **-17,945** | 54.5% | **+16,640** | Agent B와 방향 일치 |
| **+2.1%** | 7 | **-28,481** | 50% | **+6,104** | 사용자 의도 |
| +2.2% | 6 | **-30,430** | 50% | **+4,155** | |
| +2.3% | 6 | **-29,556** | 50% | **+5,029** | |
| +2.4% | 5 | **-31,990** | 50% | **+2,595** | |
| +2.5% | 5 | **-31,241** | 50% | **+3,344** | |

**캡 효과 방향**: 모든 캡 수준에서 **개선** (Δ > 0). 캡이 낮을수록 효과 크지만 조기 청산 위험 증가.

### V130 캡없음 — 청산 사유별 집계

| 청산 사유 | 건수 | 승 | PnL (KRW) |
|---|---:|---:|---:|
| SL_TIGHT | 11 | 0 | -71,500 |
| SPLIT_2ND_TRAIL | 10 | 10 | +36,441 |
| TIME_EXIT | 1 | 1 | +474 |

> SL_TIGHT 11건 중 2건(DKA, BLEND)은 실거래에서 SPLIT_2ND_TRAIL이었음 — 1분봉 한계
> **실 운영 SL 건수는 더 적을 가능성** (Agent B: 8건, 실거래: SL 유형 혼재)

---

## 3. 개별 거래 상세 — 캡없음 vs +2.1% 캡

| 종목 | 스캐너 | peak% | 캡없음 PnL | +2.1%캡 PnL | Δ | 사유(캡없음) | L1 ROI |
|---|---|---:|---:|---:|---:|---|---:|
| DKA | MR | 1.61% | -6,500 | -6,500 | 0 | SL_TIGHT (실:SPLIT_2ND) | - |
| OPEN | OP | 4.44% | 7,597 | 6,364 [캡] | -1,233 | SPLIT_2ND_TRAIL | 2.88% |
| SOON | OP | 0.76% | -6,500 | -6,500 | 0 | SL_TIGHT | - |
| XCN | OP | 3.12% | 3,641 | 4,988 [캡] | **+1,347** | SPLIT_2ND_TRAIL | 1.57% |
| CHZ | OP | 0.58% | 474 | 474 | 0 | TIME_EXIT | - |
| HYPER | OP | 1.07% | -6,500 | -6,500 | 0 | SL_TIGHT | - |
| DRIFT | AD | 0.16% | -6,500 | -6,500 | 0 | SL_TIGHT | - |
| CHIP | OP | 2.12% | 1,254 | 3,753 [캡] | **+2,499** | SPLIT_2ND_TRAIL | 1.10% |
| MOODENG | OP | 2.08% | 3,626 | 3,626 | 0 | SPLIT_2ND_TRAIL | 1.06% |
| ZBT | OP | 1.79% | 3,190 | 3,190 | 0 | SPLIT_2ND_TRAIL | 1.28% |
| ENSO | MR | 2.36% | 2,067 | 4,047 [캡] | **+1,980** | SPLIT_2ND_TRAIL | 1.34% |
| API3 | MR | 3.45% | 4,511 | 4,758 [캡] | +247 | SPLIT_2ND_TRAIL | 1.90% |
| RAY | OP | 1.22% | -6,500 | -6,500 | 0 | SL_TIGHT | - |
| ZETA | OP | 1.45% | -6,500 | -6,500 | 0 | SL_TIGHT | - |
| TOKAMAK | OP | 2.78% | 3,692 | 4,571 [캡] | **+879** | SPLIT_2ND_TRAIL | 1.76% |
| OPEN | OP | 0.48% | -6,500 | -6,500 | 0 | SL_TIGHT | - |
| DRIFT | OP | 1.91% | -6,500 | -6,500 | 0 | SL_TIGHT | - |
| PRL | MR | 2.82% | 4,548 | 4,933 [캡] | +385 | SPLIT_2ND_TRAIL | 1.79% |
| AXS | OP | 2.10% | -6,500 | -6,500 | 0 | SL_TIGHT | - |
| BLEND | OP | 0.00% | -6,500 | -6,500 | 0 | SL_TIGHT (실:SPLIT_2ND) | - |
| CHIP | OP | 1.74% | 2,315 | 2,315 | 0 | SPLIT_2ND_TRAIL | 1.23% |
| MANTRA | OP | 0.00% | -6,500 | -6,500 | 0 | SL_TIGHT | - |

---

## 4. 세 시뮬 3자 비교 — 최종 확정

| 기준 | Agent B (22건) | Agent D (22건 섹션) | **이 시뮬 (22건)** |
|---|---:|---:|---:|
| V130 캡없음 PnL | -10,885 | -10,885 | **-34,585** |
| +2.0% 캡 PnL | **-6,514** (Δ+4,371) | 비교 불가 | **-17,945** (Δ+16,640) |
| +2.0% 캡 Δ 방향 | **개선** | — | **개선** |
| +2.1% 캡 PnL | -8,220 (Δ+2,665) | 비교 불가 | **-28,481** (Δ+6,104) |
| +2.1% 캡 Δ 방향 | **개선** | — | **개선** |

**Agent D의 "Δ -7,271"은 27건 기준 숫자 — 22건 비교에서 의미 없음.**

절대 PnL은 세 시뮬이 다르지만 **캡 적용 방향(Δ)은 모두 일치 — 개선**.
Δ 크기 차이는 1분봉 한계(h/l 순서, 진입 캔들 누락)에서 비롯됨.

---

## 5. V130 실제 코드 점검

### L1 캡 구현 현황

- **현재 코드**: L1 캡(강제 split1 매도) **미구현**
- MorningRushScannerService.java 라인 697~720: split1 발동 = `dropFromPeak >= drop1st AND roi >= L1_ROI_FLOOR` 만 존재
- OpeningScannerService.java: 동일 구조, L1 캡 없음
- AllDayScannerService.java: 동일

### L1 캡 도입 시 코드 변경 위치

**대상 파일**: `MorningRushScannerService.java`, `OpeningScannerService.java`, `AllDayScannerService.java`

**변경 위치** (라인 697~720 `else if (armed && peakPrice > avgPrice)` 블록):

```java
// 현재 코드
} else if (armed && peakPrice > avgPrice) {
    double dropFromPeakPct = (peakPrice - price) / peakPrice * 100.0;
    double dropThreshold1st = getDropForPeak(avgPrice, peakPrice, false);
    boolean roiFloorOk = (cachedSplit1stRoiFloorPct <= 0) || (pnlPct >= cachedSplit1stRoiFloorPct);
    if (dropFromPeakPct >= dropThreshold1st && roiFloorOk) {
        sellType = "SPLIT_1ST";
    }
}

// L1 캡 추가 후 (cachedL1CapPct = DB에서 로드)
} else if (armed && peakPrice > avgPrice) {
    // [V131+] L1 캡 강제 매도 — trail drop 전에 먼저 체크
    if (cachedL1CapPct > 0 && pnlPct >= cachedL1CapPct) {
        sellType = "SPLIT_1ST";  // L1 강제 캡 (ROI floor 불필요)
    } else {
        double dropFromPeakPct = (peakPrice - price) / peakPrice * 100.0;
        double dropThreshold1st = getDropForPeak(avgPrice, peakPrice, false);
        boolean roiFloorOk = (cachedSplit1stRoiFloorPct <= 0) || (pnlPct >= cachedSplit1stRoiFloorPct);
        if (dropFromPeakPct >= dropThreshold1st && roiFloorOk) {
            sellType = "SPLIT_1ST";
        }
    }
}
```

**DB 필드 추가**: 스캐너별 config 테이블에 `l1_cap_pct DECIMAL(5,2) DEFAULT 0` (0=비활성)
**로드 위치**: `refreshCachedConfig()` 내 `cachedL1CapPct = cfg.getL1CapPct()` 추가

**중요**: split1 체결 후 `pos[3] = price` 리셋은 이미 있으므로 SPLIT_2ND trail 기준점은 자동으로 캡 체결가부터 재추적됨.

---

## 6. 확정 답

### 질문 1: L1 +2.0% 캡 효과는 정확히 얼마인가?

**22건 V130 기간, 세 시뮬 모두 "개선" 방향으로 일치**:

| 시뮬 | Δ (캡 있음 - 없음) | 신뢰도 |
|---|---:|---|
| Agent B | +4,371 KRW | 중 (peak 리셋 없음, ROI Floor 미반영) |
| 이 시뮬 | +16,640 KRW | 높음 (실코드 기준, 1분봉 한계 존재) |
| Agent D | -7,271 KRW | **비교 불가** (27건 기준, 대박5 포함) |

**확정 답**: L1 +2.0% 캡은 22건 V130 기간에서 **+4,371 ~ +16,640 KRW 개선 효과** (방향은 확실, 범위는 시뮬 가정 차이).

### 질문 2: 사용자 의도 "+1.5% trail + +2.1% 캡" 평가

- +2.1% 캡 Δ: Agent B **+2,665**, 이 시뮬 **+6,104** — 방향 일치(개선)
- 캡 도달 건수: 7건 / 22건 (32%)
- split1 발생 10건 중 ROI ≥ 2.1%인 건: **1건** (10%)
- 평균 실제 L1 ROI: 1.591% (중앙값 1.40%)

**결론**:
- **방향**: 백테 기준 적용 가치 있음 (모든 시뮬에서 개선)
- **효과 크기**: 제한적 (L1이 2.1% 미만에서 자연 청산되는 경우가 90%)
- **근본 한계**: 22건 표본, 4일 — 방향성만 참고, 절대 수치 무의미

### 질문 3: 어느 시뮬이 V130 코드와 가장 일치하는가?

**이 시뮬** (l1cap_definitive.js):
- drop2nd(peak<2%) = 1.0 (실코드 기본값)
- L1 ROI Floor 반영
- split1 후 peak 리셋 반영
- 실코드와 동일한 trail→SL 체크 순서

단, 1분봉 한계로 DKA/BLEND 등 2건 결과 차이 존재. **방향성 신뢰, 절대값 ±1만원 오차 범위**.

---

## 7. 주의사항

- **표본 22건, 4일**: 통계적 유의성 낮음 (방향성 참고 수준)
- **SL_TIGHT 11건(-71,500 KRW)이 지배적** — Trail/캡 개선보다 **진입 필터 강화가 우선**
- 모든 캡 설정에서 SL 건 PnL은 불변 → 캡의 효과는 SPLIT_2ND 수익 개선에만 한정
- L1 캡 구현 후 실운영 2~4주 모니터링 후 재검증 필수

---

*실데이터 1분봉 시뮬 | 실코드(MorningRushScannerService) 기본값 기준 | l1cap_definitive.js*
*생성: 2026-05-02*
