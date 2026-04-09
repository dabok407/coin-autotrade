package com.example.upbit.bot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 동일 마켓의 시간당 매수 횟수 제한 + 짧은 간격 쿨타임.
 * 같은 코인을 1시간 내 최대 N회 + 추가로 N분 내 1회만 매수 가능.
 * 모든 스캐너(모닝러쉬, 오프닝, 올데이)에서 공통 사용.
 *
 * 두 조건을 모두 만족해야 매수 가능:
 *  - 1시간 내 최대 maxTradesPerHour 회
 *  - cooldown_min 내 최대 1회 (기본 20분)
 */
public class HourlyTradeThrottle {

    private final int maxTradesPerHour;
    private final long cooldownMs;
    private final ConcurrentHashMap<String, Deque<Long>> tradeHistory =
            new ConcurrentHashMap<String, Deque<Long>>();

    public HourlyTradeThrottle(int maxTradesPerHour) {
        this(maxTradesPerHour, 20); // 기본 20분 쿨다운
    }

    public HourlyTradeThrottle(int maxTradesPerHour, int cooldownMin) {
        this.maxTradesPerHour = maxTradesPerHour;
        this.cooldownMs = cooldownMin * 60_000L;
    }

    /**
     * 매수 가능 여부 확인. 두 조건 모두 만족해야 true.
     * @param market 마켓 코드 (e.g. KRW-MASK)
     * @return true = 매수 가능, false = 시간 제한 또는 쿨다운 중
     */
    public boolean canBuy(String market) {
        Deque<Long> history = tradeHistory.get(market);
        if (history == null) return true;

        long now = System.currentTimeMillis();
        long hourCutoff = now - 3600_000L;
        long cooldownCutoff = now - cooldownMs;

        synchronized (history) {
            // 1시간 지난 기록 제거
            while (!history.isEmpty() && history.peekFirst() < hourCutoff) {
                history.pollFirst();
            }
            // 1시간 N회 제한
            if (history.size() >= maxTradesPerHour) return false;
            // 쿨다운: 가장 최근 거래가 cooldown 이내면 차단
            if (!history.isEmpty() && history.peekLast() > cooldownCutoff) return false;
            return true;
        }
    }

    /**
     * 매수 기록 추가. 매수 실행 직후 호출.
     * @param market 마켓 코드
     */
    public void recordBuy(String market) {
        Deque<Long> history = tradeHistory.computeIfAbsent(market,
                k -> new ArrayDeque<Long>());
        synchronized (history) {
            history.addLast(System.currentTimeMillis());
        }
    }

    /**
     * 가장 최근 매수 기록 1건 제거 (매수 실패 시 권한 반환용).
     * SharedTradeThrottle.releaseClaim() 에서 호출.
     */
    public void removeLastBuy(String market) {
        Deque<Long> history = tradeHistory.get(market);
        if (history == null) return;
        synchronized (history) {
            if (!history.isEmpty()) history.pollLast();
        }
    }

    /**
     * 남은 대기 시간 (ms). 0이면 즉시 매수 가능.
     * 두 제한 중 더 긴 시간 반환.
     */
    public long remainingWaitMs(String market) {
        Deque<Long> history = tradeHistory.get(market);
        if (history == null) return 0;

        long now = System.currentTimeMillis();
        long hourCutoff = now - 3600_000L;
        long cooldownCutoff = now - cooldownMs;

        synchronized (history) {
            while (!history.isEmpty() && history.peekFirst() < hourCutoff) {
                history.pollFirst();
            }
            long hourWait = 0;
            long cooldownWait = 0;

            if (history.size() >= maxTradesPerHour) {
                // 가장 오래된 기록이 1시간 지나면 매수 가능
                hourWait = history.peekFirst() + 3600_000L - now;
            }
            if (!history.isEmpty() && history.peekLast() > cooldownCutoff) {
                // 가장 최근 거래 + cooldown 까지
                cooldownWait = history.peekLast() + cooldownMs - now;
            }
            return Math.max(hourWait, cooldownWait);
        }
    }
}
