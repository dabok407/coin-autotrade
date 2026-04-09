package com.example.upbit.bot;

import org.springframework.stereotype.Service;

/**
 * 3개 스캐너(MorningRush, Opening, AllDay)가 공유하는 단일 매수 throttle.
 *
 * 같은 코인에 대해 두 가지 제한을 동시에 적용:
 *  - 1시간 내 최대 2회 매수
 *  - 가장 최근 매수로부터 20분 쿨다운
 *
 * 두 제한을 모두 만족해야 매수 가능. 모든 스캐너가 단일 인스턴스를 사용하므로
 * "모닝러쉬가 매수한 코인을 오프닝이 또 매수"하는 사고가 차단된다.
 *
 * 운영 사고:
 *  - 2026-04-08 KRW-TREE: 모닝러쉬와 오프닝이 같은 코인을 동시에 매수.
 *    → SharedTradeThrottle을 단일 Spring Bean으로 통합 (다른 스캐너 간 race 차단).
 *  - 2026-04-09 KRW-CBK: 같은 모닝러쉬 안의 두 thread (morning-rush-0, -1)가
 *    동시에 같은 코인 매수. canBuy() + recordBuy() 사이에 race window 존재.
 *    → tryClaim() synchronized atomic 메서드 추가 (이 클래스).
 */
@Service
public class SharedTradeThrottle {

    private final HourlyTradeThrottle delegate;

    public SharedTradeThrottle() {
        // 기본: 1시간 2회 + 20분 쿨다운
        this.delegate = new HourlyTradeThrottle(2, 20);
    }

    /** 매수 가능 여부 — 두 조건 모두 만족해야 true */
    public boolean canBuy(String market) {
        return delegate.canBuy(market);
    }

    /** 매수 기록 — 매수 성공 직후 호출 */
    public void recordBuy(String market) {
        delegate.recordBuy(market);
    }

    /**
     * 원자적 매수 권한 확보 (canBuy + recordBuy를 한 번에).
     *
     * KRW-CBK 사고(2026-04-09) 재발 방지: 두 thread가 동시에
     * canBuy() 통과 후 각자 recordBuy()를 호출하는 race window 차단.
     *
     * synchronized 보장:
     *  1) 한 thread만 진입 → canBuy 체크
     *  2) 통과 시 즉시 recordBuy 기록 → return true
     *  3) 다음 thread는 대기 후 진입 → 이미 기록됨 → canBuy false → return false
     *
     * 매수 코드는 반드시 이 메서드를 사용해야 한다 (canBuy + recordBuy 분리 호출 금지).
     *
     * @return true = 권한 획득 + 기록 완료, false = 차단됨 (매수 안 함)
     */
    public synchronized boolean tryClaim(String market) {
        if (!delegate.canBuy(market)) return false;
        delegate.recordBuy(market);
        return true;
    }

    /**
     * 매수 실패 시 권한 반환 (catch에서 호출).
     * tryClaim()으로 기록된 가장 최근 매수 항목을 제거.
     *
     * 주의: deque.removeLast()로 마지막 기록 1건 제거. 동시 다중 thread 환경에서
     * 정확히 자기 기록이 제거된다는 보장은 없으나, 같은 market에 대해 짧은 시간
     * 내 다중 매수가 막혀있으므로 사실상 안전.
     */
    public synchronized void releaseClaim(String market) {
        delegate.removeLastBuy(market);
    }

    /** 남은 대기 시간 (ms) — 0이면 즉시 매수 가능 */
    public long remainingWaitMs(String market) {
        return delegate.remainingWaitMs(market);
    }

    /** 테스트용 직접 접근 (시나리오 테스트의 reflection 호환) */
    HourlyTradeThrottle getDelegate() {
        return delegate;
    }
}
