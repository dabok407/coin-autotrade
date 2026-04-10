package com.example.upbit.market;

import java.util.List;

/**
 * 신규 TOP-N 마켓 추가 콜백.
 * SharedPriceService.fastAddNewMarkets()에서 새 종목이 TOP-N에 진입하면 호출된다.
 * 모닝러쉬/오프닝 스캐너가 entry phase 중 동적으로 감시 대상을 추가하는 용도.
 *
 * SharedPriceService 스레드에서 호출되므로 반드시 non-blocking이어야 함.
 */
public interface NewMarketListener {
    void onNewMarketsAdded(List<String> newMarkets);
}
