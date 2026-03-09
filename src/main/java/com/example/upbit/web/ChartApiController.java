package com.example.upbit.web;

import com.example.upbit.market.CandleService;
import com.example.upbit.market.UpbitCandle;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 거래 이벤트 시점의 캔들 데이터를 차트에 그리기 위한 API.
 * FE에서 trade row 클릭 시 호출하여 해당 시점 전후 캔들을 반환한다.
 */
@RestController
public class ChartApiController {

    private final CandleService candleService;
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    public ChartApiController(CandleService candleService) {
        this.candleService = candleService;
    }

    /**
     * 특정 시각 전후의 캔들을 반환한다.
     *
     * @param market    마켓 코드 (예: KRW-SOL)
     * @param unit      분봉 단위 (5, 15, 30, 60 등)
     * @param tsEpochMs 기준 시각 (밀리초 epoch) — 거래 이벤트 발생 시각
     * @param count     총 캔들 수 (기본 80). 기준 시각 전후로 분배됨.
     * @return 캔들 배열 (오래된 → 최신 순)
     */
    @GetMapping("/api/chart/candles")
    public List<Map<String, Object>> candlesAround(
            @RequestParam String market,
            @RequestParam(defaultValue = "5") int unit,
            @RequestParam long tsEpochMs,
            @RequestParam(defaultValue = "80") int count) {

        if (unit <= 0) unit = 5;
        if (count <= 0) count = 80;
        count = Math.min(count, 200);

        // 기준 시각 + 캔들 30개분 뒤까지를 'to'로 잡아서 기준 전후 캔들을 확보
        long afterCandles = 30;
        long toEpochMs = tsEpochMs + (afterCandles * unit * 60_000L);
        String toIso = ISO_FMT.format(Instant.ofEpochMilli(toEpochMs));

        List<UpbitCandle> raw = candleService.getMinuteCandles(market, unit, count, toIso);
        if (raw == null) raw = Collections.emptyList();

        // 200개 이내이므로 추가 페이지네이션 불필요

        // 오래된 → 최신 순 정렬
        List<UpbitCandle> sorted = new ArrayList<>(raw);
        sorted.sort((a, b) -> {
            if (a.candle_date_time_utc == null && b.candle_date_time_utc == null) return 0;
            if (a.candle_date_time_utc == null) return -1;
            if (b.candle_date_time_utc == null) return 1;
            return a.candle_date_time_utc.compareTo(b.candle_date_time_utc);
        });

        // lightweight-charts가 기대하는 형태로 변환
        List<Map<String, Object>> out = new ArrayList<>();
        for (UpbitCandle c : sorted) {
            if (c.candle_date_time_utc == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            // epoch seconds (lightweight-charts는 time을 unix timestamp 초 단위로 받음)
            try {
                Instant inst = Instant.parse(c.candle_date_time_utc + "Z");
                m.put("time", inst.getEpochSecond());
            } catch (Exception e) {
                // 파싱 실패 시 스킵
                continue;
            }
            m.put("open", c.opening_price);
            m.put("high", c.high_price);
            m.put("low", c.low_price);
            m.put("close", c.trade_price);
            m.put("volume", c.candle_acc_trade_volume);
            out.add(m);
        }

        return out;
    }
}
