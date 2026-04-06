package com.example.upbit.bot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 동일 마켓의 시간당 매수 횟수 제한.
 * 같은 코인을 1시간 내 최대 N회만 매수 가능.
 * 모든 스캐너(모닝러쉬, 오프닝, 올데이)에서 공통 사용.
 */
public class HourlyTradeThrottle {

    private final int maxTradesPerHour;
    private final ConcurrentHashMap<String, Deque<Long>> tradeHistory =
            new ConcurrentHashMap<String, Deque<Long>>();

    public HourlyTradeThrottle(int maxTradesPerHour) {
        this.maxTradesPerHour = maxTradesPerHour;
    }

    /**
     * 매수 가능 여부 확인.
     * @param market 마켓 코드 (e.g. KRW-MASK)
     * @return true = 매수 가능, false = 1시간 내 최대 횟수 도달
     */
    public boolean canBuy(String market) {
        Deque<Long> history = tradeHistory.get(market);
        if (history == null) return true;

        long cutoff = System.currentTimeMillis() - 3600_000L; // 1시간 전
        synchronized (history) {
            // 1시간 지난 기록 제거
            while (!history.isEmpty() && history.peekFirst() < cutoff) {
                history.pollFirst();
            }
            return history.size() < maxTradesPerHour;
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
     * 남은 대기 시간 (ms). 0이면 즉시 매수 가능.
     */
    public long remainingWaitMs(String market) {
        Deque<Long> history = tradeHistory.get(market);
        if (history == null) return 0;

        long cutoff = System.currentTimeMillis() - 3600_000L;
        synchronized (history) {
            while (!history.isEmpty() && history.peekFirst() < cutoff) {
                history.pollFirst();
            }
            if (history.size() < maxTradesPerHour) return 0;
            // 가장 오래된 기록이 1시간 지나면 매수 가능
            return history.peekFirst() + 3600_000L - System.currentTimeMillis();
        }
    }
}
