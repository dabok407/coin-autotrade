package com.example.upbit.market;

import com.example.upbit.db.CandleCacheEntity;
import com.example.upbit.db.CandleCacheRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 캔들 데이터 캐시 서비스.
 * Upbit API에서 캔들 데이터를 다운로드하여 H2 DB에 저장하고,
 * 백테스트/최적화 시 캐시된 데이터를 제공합니다.
 */
@Service
public class CandleCacheService {

    private static final Logger log = LoggerFactory.getLogger(CandleCacheService.class);

    private static final String[] DEFAULT_MARKETS = {
            "KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-ADA"
    };
    private static final int[] DEFAULT_INTERVALS = {5, 15, 30, 60, 240, 1440};
    private static final int LOOKBACK_DAYS = 365;
    private static final int API_DELAY_MS = 150; // API 호출 간격 (rate limit 방지)

    private final CandleService candleService;
    private final CandleCacheRepository cacheRepo;

    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final AtomicInteger totalJobs = new AtomicInteger(0);
    private final AtomicInteger completedJobs = new AtomicInteger(0);
    private volatile String currentTask = "";

    public CandleCacheService(CandleService candleService, CandleCacheRepository cacheRepo) {
        this.candleService = candleService;
        this.cacheRepo = cacheRepo;
    }

    // ===== 상태 조회 =====

    public boolean isDownloading() { return downloading.get(); }
    public int getTotalJobs() { return totalJobs.get(); }
    public int getCompletedJobs() { return completedJobs.get(); }
    public String getCurrentTask() { return currentTask; }

    /**
     * 캐시 현황: 마켓별, 인터벌별 캔들 수.
     */
    public Map<String, Map<Integer, Long>> getCacheStatus() {
        Map<String, Map<Integer, Long>> status = new LinkedHashMap<String, Map<Integer, Long>>();
        for (String market : DEFAULT_MARKETS) {
            Map<Integer, Long> byInterval = new LinkedHashMap<Integer, Long>();
            for (int interval : DEFAULT_INTERVALS) {
                byInterval.put(interval, cacheRepo.countByMarketAndIntervalMin(market, interval));
            }
            status.put(market, byInterval);
        }
        return status;
    }

    // ===== 다운로드 =====

    /**
     * 모든 마켓/인터벌의 캔들 데이터를 비동기 다운로드합니다.
     * 이미 다운로드 중이면 무시합니다.
     */
    public void downloadAllAsync() {
        if (!downloading.compareAndSet(false, true)) {
            log.info("이미 캔들 캐시 다운로드 중입니다.");
            return;
        }

        totalJobs.set(DEFAULT_MARKETS.length * DEFAULT_INTERVALS.length);
        completedJobs.set(0);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    downloadAllSync();
                } finally {
                    downloading.set(false);
                    currentTask = "완료";
                    log.info("캔들 캐시 다운로드 완료. 총 {}건 처리.", completedJobs.get());
                }
            }
        }, "candle-cache-download");
        t.setDaemon(true);
        t.start();
    }

    private void downloadAllSync() {
        for (String market : DEFAULT_MARKETS) {
            for (int interval : DEFAULT_INTERVALS) {
                currentTask = market + " / " + interval + "분";
                try {
                    downloadMarketInterval(market, interval);
                } catch (Exception e) {
                    log.error("캔들 캐시 다운로드 실패: {} {}분 - {}", market, interval, e.getMessage());
                }
                completedJobs.incrementAndGet();
            }
        }
    }

    /**
     * 특정 마켓/인터벌의 캔들 데이터를 다운로드하여 캐시합니다.
     * 이미 캐시된 데이터가 있으면 최신 데이터만 추가합니다.
     */
    @Transactional
    public void downloadMarketInterval(String market, int interval) {
        log.info("캔들 캐시 다운로드 시작: {} {}분", market, interval);

        // 기존 캐시된 최신 타임스탬프 확인
        CandleCacheEntity latest = cacheRepo.findTopByMarketAndIntervalMinOrderByCandleTsUtcDesc(market, interval);

        List<UpbitCandle> candles;
        if (latest != null) {
            // 증분 다운로드: 최신 캐시 이후 데이터만
            log.info("  증분 다운로드 (최신 캐시: {})", latest.getCandleTsUtc());
            candles = fetchSince(market, interval, latest.getCandleTsUtc());
        } else {
            // 전체 다운로드: LOOKBACK_DAYS일치
            log.info("  전체 다운로드 ({}일)", LOOKBACK_DAYS);
            candles = candleService.fetchLookback(market, interval, LOOKBACK_DAYS);
        }

        if (candles == null || candles.isEmpty()) {
            log.info("  다운로드할 캔들 없음.");
            return;
        }

        // 캐시에 저장 (중복 무시)
        int saved = 0;
        List<CandleCacheEntity> batch = new ArrayList<CandleCacheEntity>(200);
        Set<String> existingTs = new HashSet<String>();

        // 기존 캐시 타임스탬프 수집 (중복 방지)
        if (latest != null) {
            List<CandleCacheEntity> existing = cacheRepo.findByMarketAndIntervalMinOrderByCandleTsUtcAsc(market, interval);
            for (CandleCacheEntity e : existing) {
                existingTs.add(e.getCandleTsUtc());
            }
        }

        for (UpbitCandle c : candles) {
            if (c == null || c.candle_date_time_utc == null) continue;
            if (existingTs.contains(c.candle_date_time_utc)) continue;

            CandleCacheEntity entity = new CandleCacheEntity();
            entity.setMarket(market);
            entity.setIntervalMin(interval);
            entity.setCandleTsUtc(c.candle_date_time_utc);
            entity.setOpenPrice(c.opening_price);
            entity.setHighPrice(c.high_price);
            entity.setLowPrice(c.low_price);
            entity.setClosePrice(c.trade_price);
            entity.setVolume(c.candle_acc_trade_volume);
            batch.add(entity);
            saved++;

            if (batch.size() >= 200) {
                cacheRepo.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            cacheRepo.saveAll(batch);
        }

        log.info("  캔들 캐시 저장 완료: {} {}분 → {}건 추가 (총 {}건)",
                market, interval, saved,
                cacheRepo.countByMarketAndIntervalMin(market, interval));
    }

    /**
     * 특정 타임스탬프 이후의 캔들을 가져옵니다 (증분 다운로드용).
     */
    private List<UpbitCandle> fetchSince(String market, int interval, String sinceUtc) {
        // sinceUtc 이후 ~ 현재까지 데이터를 가져옴
        // Upbit API는 'to' 기준 역방향이므로, to=null(현재)부터 가져와서 sinceUtc까지 수집
        List<UpbitCandle> all = new ArrayList<UpbitCandle>();
        String to = null;

        for (int guard = 0; guard < 500; guard++) {
            List<UpbitCandle> chunk;
            if (interval >= 1440) {
                chunk = candleService.getDayCandles(market, 200, to);
            } else {
                chunk = candleService.getMinuteCandles(market, interval, 200, to);
            }
            if (chunk == null || chunk.isEmpty()) break;

            boolean reachedOld = false;
            for (UpbitCandle c : chunk) {
                if (c.candle_date_time_utc == null) continue;
                if (c.candle_date_time_utc.compareTo(sinceUtc) <= 0) {
                    reachedOld = true;
                    continue; // 이미 캐시된 데이터
                }
                all.add(c);
            }

            if (reachedOld) break;
            if (chunk.size() < 200) break;

            UpbitCandle oldest = chunk.get(chunk.size() - 1);
            to = oldest.candle_date_time_utc;

            sleep(API_DELAY_MS);
        }

        // 오래된 → 최신 순 정렬
        Collections.sort(all, new Comparator<UpbitCandle>() {
            @Override
            public int compare(UpbitCandle a, UpbitCandle b) {
                if (a.candle_date_time_utc == null) return -1;
                if (b.candle_date_time_utc == null) return 1;
                return a.candle_date_time_utc.compareTo(b.candle_date_time_utc);
            }
        });
        return all;
    }

    // ===== 캐시 조회 =====

    /**
     * 캐시된 캔들 데이터를 UpbitCandle 리스트로 반환합니다.
     */
    public List<UpbitCandle> getCached(String market, int intervalMin) {
        List<CandleCacheEntity> entities =
                cacheRepo.findByMarketAndIntervalMinOrderByCandleTsUtcAsc(market, intervalMin);
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>(entities.size());
        for (CandleCacheEntity e : entities) {
            candles.add(toUpbitCandle(e));
        }
        return candles;
    }

    /**
     * 전체 캐시 데이터를 벌크 로딩합니다 (최적화 엔진용).
     * 반환: Map<market, Map<intervalMin, List<UpbitCandle>>>
     */
    public Map<String, Map<Integer, List<UpbitCandle>>> getAllCached() {
        Map<String, Map<Integer, List<UpbitCandle>>> result =
                new LinkedHashMap<String, Map<Integer, List<UpbitCandle>>>();

        for (String market : DEFAULT_MARKETS) {
            Map<Integer, List<UpbitCandle>> byInterval =
                    new LinkedHashMap<Integer, List<UpbitCandle>>();
            for (int interval : DEFAULT_INTERVALS) {
                List<UpbitCandle> candles = getCached(market, interval);
                if (!candles.isEmpty()) {
                    byInterval.put(interval, candles);
                }
            }
            if (!byInterval.isEmpty()) {
                result.put(market, byInterval);
            }
        }
        return result;
    }

    /**
     * 특정 마켓/인터벌에 캐시가 있는지 확인합니다.
     */
    public boolean hasCachedData(String market, int intervalMin) {
        return cacheRepo.countByMarketAndIntervalMin(market, intervalMin) > 0;
    }

    // ===== 유틸 =====

    private UpbitCandle toUpbitCandle(CandleCacheEntity e) {
        UpbitCandle c = new UpbitCandle();
        c.market = e.getMarket();
        c.candle_date_time_utc = e.getCandleTsUtc();
        c.opening_price = e.getOpenPrice();
        c.high_price = e.getHighPrice();
        c.low_price = e.getLowPrice();
        c.trade_price = e.getClosePrice();
        c.candle_acc_trade_volume = e.getVolume();
        return c;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String[] getDefaultMarkets() { return DEFAULT_MARKETS; }
    public int[] getDefaultIntervals() { return DEFAULT_INTERVALS; }
}
