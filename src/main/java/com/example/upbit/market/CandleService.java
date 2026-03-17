package com.example.upbit.market;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class CandleService {

    /**
     * 업비트 분봉 API가 허용하는 unit 값.
     * 이 외의 값을 보내면 400 "specified unit is not valid." 에러 발생.
     */
    private static final int[] VALID_UNITS = {1, 3, 5, 10, 15, 30, 60, 240};

    /**
     * 글로벌 API 호출 속도 제한: 동시 요청 수를 제한하여 429 방지.
     * 업비트 Quotation API: 초당 ~10회 제한 → 동시 4개로 안전하게 운용.
     * (병렬 스레드가 동시에 API를 호출하더라도 이 Semaphore를 거쳐야 함)
     */
    private static final Semaphore API_THROTTLE = new Semaphore(4);
    private static final long MIN_API_INTERVAL_MS = 120; // 최소 호출 간격

    private final RestTemplate restTemplate;

    @Autowired
    public CandleService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 주어진 unit이 업비트 API가 허용하는 값인지 확인하고,
     * 허용하지 않으면 가장 가까운 유효한 값으로 보정합니다.
     */
    static int normalizeUnit(int unit) {
        for (int v : VALID_UNITS) {
            if (v == unit) return unit;
        }
        // 가장 가까운 유효값 찾기
        int best = VALID_UNITS[0];
        int bestDist = Math.abs(unit - best);
        for (int i = 1; i < VALID_UNITS.length; i++) {
            int dist = Math.abs(unit - VALID_UNITS[i]);
            if (dist < bestDist) {
                bestDist = dist;
                best = VALID_UNITS[i];
            }
        }
        return best;
    }


    private UpbitCandle[] fetchWithRetry(String url, Class<UpbitCandle[]> type) {
    Exception last = null;

    // Conservative retry/backoff to avoid 429 storms.
    // Backoff: 250ms, 500ms, 1000ms, 2000ms, 4000ms (capped)
    final int maxRetries = 5;
    for (int attempt = 0; attempt < maxRetries; attempt++) {
        try {
            API_THROTTLE.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("API throttle interrupted", ie);
        }
        try {
            UpbitCandle[] result = restTemplate.getForObject(url, type);
            return result;
        } catch (HttpClientErrorException e) {
            last = e;
            HttpStatus sc = e.getStatusCode();
            if (sc == HttpStatus.TOO_MANY_REQUESTS || sc.is5xxServerError()) {
                sleepBackoff(attempt);
                continue;
            }
            throw e;
        } catch (ResourceAccessException e) {
            // network IO timeout, connection reset, etc.
            last = e;
            sleepBackoff(attempt);
        } finally {
            // 최소 호출 간격 보장 후 permit 반환 (다음 호출 간 딜레이)
            schedulePermitRelease();
        }
    }
    if (last instanceof RuntimeException) throw (RuntimeException) last;
    throw new RuntimeException(last);
}

/**
 * Semaphore permit을 MIN_API_INTERVAL_MS 이후에 반환하여 호출 간격을 보장한다.
 * 별도 스레드로 반환하므로 현재 스레드는 블로킹되지 않는다.
 */
private void schedulePermitRelease() {
    final long delay = MIN_API_INTERVAL_MS;
    new Thread(new Runnable() {
        public void run() {
            try { Thread.sleep(delay); } catch (InterruptedException ignore) {}
            API_THROTTLE.release();
        }
    }, "api-throttle-release").start();
}

private void sleepBackoff(int attempt) {
    long base = 250L;
    long ms = base * (1L << Math.min(attempt, 4));
    try {
        Thread.sleep(ms);
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
    }
}

    public List<UpbitCandle> getMinuteCandles(String market, int unit, int count, String toIsoUtc) {
        unit = normalizeUnit(unit);
        UriComponentsBuilder b = UriComponentsBuilder
                .fromHttpUrl("https://api.upbit.com/v1/candles/minutes/" + unit)
                .queryParam("market", market)
                .queryParam("count", count);

        if (toIsoUtc != null && !toIsoUtc.isEmpty()) {
            b.queryParam("to", toIsoUtc);
        }

        UpbitCandle[] arr = fetchWithRetry(b.toUriString(), UpbitCandle[].class);
        if (arr == null) return Collections.emptyList();
        return Arrays.asList(arr);
    }

    /**
     * 200개 이상 캔들이 필요할 때 페이지네이션으로 가져온다.
     * 업비트 API는 1회 200개 제한이므로, 필요 시 2회 이상 호출한다.
     * 결과: 오래된 → 최신 순 정렬, 중복 제거.
     */
    public List<UpbitCandle> getMinuteCandlesPaged(String market, int unit, int totalCount) {
        unit = normalizeUnit(unit);
        List<UpbitCandle> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String to = null;
        int remaining = totalCount;

        for (int page = 0; page < 5 && remaining > 0; page++) {
            int fetch = Math.min(remaining, 200);
            List<UpbitCandle> chunk = getMinuteCandles(market, unit, fetch, to);
            if (chunk.isEmpty()) break;

            for (UpbitCandle c : chunk) {
                String key = c.candle_date_time_utc;
                if (key != null && seen.add(key)) {
                    all.add(c);
                    remaining--;
                }
            }
            if (chunk.size() < fetch) break;

            // 업비트는 최신→오래된 순 반환. 마지막(가장 오래된) 캔들의 시각을 다음 페이지 'to'로 사용
            UpbitCandle oldest = chunk.get(chunk.size() - 1);
            to = oldest.candle_date_time_utc;
        }

        // 오래된 → 최신 순 정렬
        all.sort((a, b) -> {
            if (a.candle_date_time_utc == null && b.candle_date_time_utc == null) return 0;
            if (a.candle_date_time_utc == null) return -1;
            if (b.candle_date_time_utc == null) return 1;
            return a.candle_date_time_utc.compareTo(b.candle_date_time_utc);
        });

        return all;
    }

    public List<UpbitCandle> getDayCandles(String market, int count, String toIsoUtc) {
        UriComponentsBuilder b = UriComponentsBuilder
                .fromHttpUrl("https://api.upbit.com/v1/candles/days")
                .queryParam("market", market)
                .queryParam("count", count);

        if (toIsoUtc != null && !toIsoUtc.isEmpty()) {
            b.queryParam("to", toIsoUtc);
        }

        UpbitCandle[] arr = fetchWithRetry(b.toUriString(), UpbitCandle[].class);
        if (arr == null) return Collections.emptyList();
        return Arrays.asList(arr);
    }

    /**
     * Fetch lookback candles using pagination.
     * Upbit typically returns candles in reverse chronological order (latest -> older).
     */
    public List<UpbitCandle> fetchLookback(String market, int unit, int lookbackDays) {
        // 1일(일봉) 지원: unit=1440
        if (unit >= 1440) {
            int need = Math.max(1, lookbackDays);
            List<UpbitCandle> all = new ArrayList<UpbitCandle>(need);

            String to = null;
            while (all.size() < need) {
                List<UpbitCandle> chunk = getDayCandles(market, 200, to);
                if (chunk.isEmpty()) break;

                all.addAll(chunk);

                UpbitCandle last = chunk.get(chunk.size() - 1);
                to = last.candle_date_time_utc;

                if (chunk.size() < 200) break;
            }

            Collections.reverse(all);

            if (all.size() > need) {
                return all.subList(all.size() - need, all.size());
            }
            return all;
        }

        int need = (int) ((lookbackDays * 24L * 60L) / unit);
        List<UpbitCandle> all = new ArrayList<UpbitCandle>(need);

        String to = null;
        while (all.size() < need) {
            List<UpbitCandle> chunk = getMinuteCandles(market, unit, 200, to);
            if (chunk.isEmpty()) break;

            all.addAll(chunk);

            UpbitCandle last = chunk.get(chunk.size() - 1);
            to = last.candle_date_time_utc;

            if (chunk.size() < 200) break;
        }

        Collections.reverse(all);

        if (all.size() > need) {
            return all.subList(all.size() - need, all.size());
        }
        return all;
    }

    /**
     * 특정 기간(from~to, KST 날짜 기준) 캔들을 가져옵니다.
     * - fromIsoUtcInclusive / toIsoUtcExclusive : Upbit API가 요구하는 UTC ISO(LocalDateTime) 문자열
     * - 내부적으로는 to 파라미터를 이동시키며 역방향 페이징
     */
    public List<UpbitCandle> fetchBetweenUtc(String market, int unit, String fromIsoUtcInclusive, String toIsoUtcExclusive) {
        if (market == null || market.trim().isEmpty()) return Collections.emptyList();

        // 일봉
        if (unit >= 1440) {
            List<UpbitCandle> all = new ArrayList<UpbitCandle>();
            String to = toIsoUtcExclusive;
            for (int guard = 0; guard < 200; guard++) {
                List<UpbitCandle> chunk = getDayCandles(market, 200, to);
                if (chunk.isEmpty()) break;
                all.addAll(chunk);
                UpbitCandle last = chunk.get(chunk.size() - 1);
                to = last.candle_date_time_utc;
                // 역방향으로 내려가다가 from 이하로 내려오면 더 가져올 필요 없음
                if (fromIsoUtcInclusive != null && !fromIsoUtcInclusive.isEmpty()
                        && last.candle_date_time_utc != null
                        && last.candle_date_time_utc.compareTo(fromIsoUtcInclusive) <= 0) {
                    break;
                }
                if (chunk.size() < 200) break;
            }
            Collections.reverse(all);
            return filterBetween(all, fromIsoUtcInclusive, toIsoUtcExclusive);
        }

        List<UpbitCandle> all = new ArrayList<UpbitCandle>();
        String to = toIsoUtcExclusive;

        // 요청량 상한을 합리적으로 줄여 429 + backoff로 인한 지연을 방지한다.
        // (업비트는 1회 200개 제한이므로 예상 캔들 수 / 200 + 여유분)
        int guardMax = 500;
        try {
            if (fromIsoUtcInclusive != null && !fromIsoUtcInclusive.isEmpty()
                    && toIsoUtcExclusive != null && !toIsoUtcExclusive.isEmpty()
                    && unit > 0) {
                java.time.OffsetDateTime from = java.time.OffsetDateTime.parse(fromIsoUtcInclusive + "+00:00");
                java.time.OffsetDateTime toDt = java.time.OffsetDateTime.parse(toIsoUtcExclusive + "+00:00");
                long minutes = java.time.Duration.between(from, toDt).toMinutes();
                if (minutes < 0) minutes = 0;
                long expected = (minutes / unit) + 2; // inclusive 여유
                long pages = (expected + 199) / 200;
                guardMax = (int) Math.min(500, Math.max(3, pages + 3));
            }
        } catch (Exception ignore) {
            // fallback to default
        }

        for (int guard = 0; guard < guardMax; guard++) {
            List<UpbitCandle> chunk = getMinuteCandles(market, unit, 200, to);
            if (chunk.isEmpty()) break;
            all.addAll(chunk);
            UpbitCandle last = chunk.get(chunk.size() - 1);
            to = last.candle_date_time_utc;
            // 역방향으로 내려가다가 from 이하로 내려오면 더 가져올 필요 없음
            if (fromIsoUtcInclusive != null && !fromIsoUtcInclusive.isEmpty()
                    && last.candle_date_time_utc != null
                    && last.candle_date_time_utc.compareTo(fromIsoUtcInclusive) <= 0) {
                break;
            }
            if (chunk.size() < 200) break;
        }
        Collections.reverse(all);
        return filterBetween(all, fromIsoUtcInclusive, toIsoUtcExclusive);
    }

    private List<UpbitCandle> filterBetween(List<UpbitCandle> asc, String fromInclusive, String toExclusive) {
        if (asc == null || asc.isEmpty()) return Collections.emptyList();
        if ((fromInclusive == null || fromInclusive.isEmpty()) && (toExclusive == null || toExclusive.isEmpty())) return asc;
        List<UpbitCandle> out = new ArrayList<UpbitCandle>();
        for (UpbitCandle c : asc) {
            if (c == null || c.candle_date_time_utc == null) continue;
            String t = c.candle_date_time_utc;
            if (fromInclusive != null && !fromInclusive.isEmpty() && t.compareTo(fromInclusive) < 0) continue;
            if (toExclusive != null && !toExclusive.isEmpty() && t.compareTo(toExclusive) >= 0) continue;
            out.add(c);
        }
        return out;
    }
}
