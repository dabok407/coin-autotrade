package com.example.upbit.market;

/**
 * 실시간 가격 업데이트 콜백.
 * SharedPriceService의 WebSocket 스레드에서 호출되므로 반드시 non-blocking이어야 함.
 * DB 접근이나 무거운 작업은 별도 executor에서 실행할 것.
 */
public interface PriceUpdateListener {
    void onPriceUpdate(String market, double price);
}
