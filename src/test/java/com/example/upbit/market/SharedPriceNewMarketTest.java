package com.example.upbit.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SharedPriceService 신규 마켓 콜백 + 동적 갱신 주기 테스트.
 */
class SharedPriceNewMarketTest {

    // ── 테스트용 단순 리스너 구현 ──
    private List<List<String>> receivedCallbacks;
    private NewMarketListener testListener;

    @BeforeEach
    void setUp() {
        receivedCallbacks = new CopyOnWriteArrayList<List<String>>();
        testListener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                receivedCallbacks.add(new ArrayList<String>(newMarkets));
            }
        };
    }

    @Test
    @DisplayName("NewMarketListener 인터페이스 계약: onNewMarketsAdded 호출 시 마켓 리스트 전달")
    void testListenerReceivesNewMarkets() {
        List<String> markets = Arrays.asList("KRW-MINA", "KRW-EDGE");
        testListener.onNewMarketsAdded(markets);

        assertEquals(1, receivedCallbacks.size());
        assertEquals(2, receivedCallbacks.get(0).size());
        assertTrue(receivedCallbacks.get(0).contains("KRW-MINA"));
        assertTrue(receivedCallbacks.get(0).contains("KRW-EDGE"));
    }

    @Test
    @DisplayName("빈 마켓 리스트 전달 시 콜백은 호출되지만 내용은 비어있음")
    void testEmptyMarketList() {
        List<String> empty = new ArrayList<String>();
        testListener.onNewMarketsAdded(empty);

        assertEquals(1, receivedCallbacks.size());
        assertTrue(receivedCallbacks.get(0).isEmpty());
    }

    @Test
    @DisplayName("여러 번 콜백 호출 시 순서대로 누적됨")
    void testMultipleCallbacks() {
        testListener.onNewMarketsAdded(Arrays.asList("KRW-MINA"));
        testListener.onNewMarketsAdded(Arrays.asList("KRW-EDGE", "KRW-PYTH"));
        testListener.onNewMarketsAdded(Arrays.asList("KRW-ALT"));

        assertEquals(3, receivedCallbacks.size());
        assertEquals(1, receivedCallbacks.get(0).size());
        assertEquals(2, receivedCallbacks.get(1).size());
        assertEquals(1, receivedCallbacks.get(2).size());
    }
}
