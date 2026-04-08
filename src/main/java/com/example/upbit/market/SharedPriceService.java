package com.example.upbit.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 공유 WebSocket 실시간 가격 서비스.
 *
 * 업비트 WebSocket으로 TOP-N KRW 마켓의 실시간 가격을 수신하고,
 * 등록된 리스너들에게 가격 업데이트를 전달한다.
 *
 * 모든 스캐너(모닝러쉬, 오프닝, 올데이)가 이 서비스를 공유하여
 * 중복 WebSocket 연결을 방지하고, 매수 직후 즉시 TP/SL 모니터링을 가능하게 한다.
 */
@Service
public class SharedPriceService {

    private static final Logger log = LoggerFactory.getLogger(SharedPriceService.class);
    private static final String WS_URL = "wss://api.upbit.com/websocket/v1";
    private static final int WS_MAX_CODES_PER_CONN = 15;
    private static final int WS_PING_INTERVAL_SEC = 25;
    private static final int MARKET_REFRESH_INTERVAL_MIN = 10;       // 전체 갱신 주기 (재연결 발생)
    private static final long FAST_REFRESH_INTERVAL_SEC = 60;        // 신규 종목 추가용 빠른 갱신 (재연결 없음)
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int DEFAULT_TOP_N = 50;
    private static final long MIN_RECONNECT_INTERVAL_MS = 30_000; // 전체 재연결 최소 간격

    private final UpbitMarketCatalogService catalogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── WebSocket 상태 ──
    private volatile OkHttpClient wsClient;
    private final List<WebSocket> activeWebSockets = new CopyOnWriteArrayList<WebSocket>();
    private volatile ScheduledExecutorService scheduler;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private final AtomicInteger msgCount = new AtomicInteger(0);
    private volatile long lastLogMs = 0;

    // ── 재연결 제어 ──
    private final AtomicBoolean intentionalDisconnect = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile long lastConnectMs = 0;
    private volatile ScheduledFuture<?> pingTask;
    private final Object connectLock = new Object();

    // ── 가격 캐시 ──
    private final ConcurrentHashMap<String, Double> latestPrices = new ConcurrentHashMap<String, Double>();
    // ── 거래대금 순위 캐시 (1분마다 갱신) ──
    private volatile Map<String, Integer> volumeRankMap = Collections.emptyMap(); // market → rank (1-based)

    // ── 구독 마켓 ──
    private volatile List<String> subscribedMarkets = Collections.emptyList();
    private final Set<String> extraMarkets = ConcurrentHashMap.newKeySet(); // 스캐너가 추가 요청한 마켓

    // ── 리스너 ──
    private final CopyOnWriteArrayList<PriceUpdateListener> globalListeners =
            new CopyOnWriteArrayList<PriceUpdateListener>();

    public SharedPriceService(UpbitMarketCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // ========== Lifecycle ==========

    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "shared-price-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

        // 초기 마켓 목록 수집 + WebSocket 연결
        scheduler.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshMarketList();
                } catch (Exception e) {
                    log.error("[SharedPrice] 초기 마켓 목록 수집 실패", e);
                }
            }
        });

        // 주기적 마켓 목록 갱신 (10분 — 전체 재연결)
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshMarketList();
                } catch (Exception e) {
                    log.error("[SharedPrice] 마켓 목록 갱신 실패", e);
                }
            }
        }, MARKET_REFRESH_INTERVAL_MIN, MARKET_REFRESH_INTERVAL_MIN, TimeUnit.MINUTES);

        // 빠른 갱신 (1분 — 신규 종목만 추가, 재연결 없음)
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    fastAddNewMarkets();
                } catch (Exception e) {
                    log.error("[SharedPrice] 신규 종목 추가 실패", e);
                }
            }
        }, FAST_REFRESH_INTERVAL_SEC, FAST_REFRESH_INTERVAL_SEC, TimeUnit.SECONDS);

        log.info("[SharedPrice] 서비스 초기화 완료");
    }

    @PreDestroy
    public void destroy() {
        disconnectWebSocket();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        log.info("[SharedPrice] 서비스 종료");
    }

    // ========== Public API ==========

    /** 마켓의 최신 가격 조회. 없으면 null. */
    public Double getPrice(String market) {
        return latestPrices.get(market);
    }

    /**
     * 마켓의 24시간 거래대금 순위 조회.
     * 1분마다 fastAddNewMarkets에서 갱신됨.
     * @return 1-based 순위 (1=1위), 캐시에 없으면 999
     */
    public int getVolumeRank(String market) {
        Integer rank = volumeRankMap.get(market);
        return rank != null ? rank : 999;
    }

    /** 모든 가격 캐시 조회 (읽기 전용 스냅샷). */
    public Map<String, Double> getAllPrices() {
        return new HashMap<String, Double>(latestPrices);
    }

    /** WebSocket 연결 상태. */
    public boolean isConnected() {
        return connected.get();
    }

    /** 현재 구독 중인 마켓 수. */
    public int getSubscribedMarketCount() {
        return subscribedMarkets.size();
    }

    /** 수신된 총 메시지 수. */
    public int getMessageCount() {
        return msgCount.get();
    }

    // ── 리스너 관리 ──

    /** 전역 리스너 등록 (모든 마켓의 가격 업데이트 수신). */
    public void addGlobalListener(PriceUpdateListener listener) {
        if (listener != null) {
            globalListeners.add(listener);
            log.info("[SharedPrice] 리스너 등록 (total={})", globalListeners.size());
        }
    }

    /** 전역 리스너 해제. */
    public void removeGlobalListener(PriceUpdateListener listener) {
        if (listener != null) {
            globalListeners.remove(listener);
            log.info("[SharedPrice] 리스너 해제 (total={})", globalListeners.size());
        }
    }

    /**
     * 특정 마켓들이 구독에 포함되도록 요청.
     * TOP-N에 없는 마켓도 구독할 수 있도록 추가.
     */
    public void ensureMarketsSubscribed(Collection<String> markets) {
        if (markets == null || markets.isEmpty()) return;
        boolean changed = false;
        for (String m : markets) {
            if (!subscribedMarkets.contains(m) && extraMarkets.add(m)) {
                changed = true;
            }
        }
        if (changed) {
            log.info("[SharedPrice] 추가 마켓 요청: {} (extraMarkets={})", markets, extraMarkets.size());
            // 비동기로 마켓 목록 갱신 + 재연결
            if (scheduler != null) {
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            refreshMarketList();
                        } catch (Exception e) {
                            log.error("[SharedPrice] 추가 마켓 반영 실패", e);
                        }
                    }
                });
            }
        }
    }

    // ========== Internal: 마켓 목록 관리 ==========

    /**
     * 빠른 신규 종목 추가 (1분 주기).
     * REST로 거래대금 TOP-N 조회 후, 현재 구독 중이 아닌 종목만 extraMarkets에 추가.
     * WebSocket 재연결을 발생시키지 않음 (ensureMarketsSubscribed 경유).
     */
    private void fastAddNewMarkets() {
        // 재연결 진행 중이면 스킵
        if (reconnecting.get()) return;

        try {
            Set<String> allKrwMarkets = catalogService.getAllMarketCodes();
            if (allKrwMarkets == null || allKrwMarkets.isEmpty()) return;

            List<String> krwMarkets = new ArrayList<String>();
            for (String m : allKrwMarkets) {
                if (m.startsWith("KRW-")) krwMarkets.add(m);
            }

            // 거래대금 기준 TOP-N
            List<UpbitMarketCatalogService.TickerItem> tickers = catalogService.fetchTickers(krwMarkets);
            final Map<String, Double> volumeMap = new HashMap<String, Double>();
            for (UpbitMarketCatalogService.TickerItem t : tickers) {
                volumeMap.put(t.market, t.acc_trade_price_24h);
            }
            Collections.sort(krwMarkets, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    double va = volumeMap.containsKey(a) ? volumeMap.get(a) : 0;
                    double vb = volumeMap.containsKey(b) ? volumeMap.get(b) : 0;
                    return Double.compare(vb, va);
                }
            });

            // 전체 거래대금 순위 캐시 갱신 (1-based)
            Map<String, Integer> newRankMap = new HashMap<String, Integer>();
            for (int i = 0; i < krwMarkets.size(); i++) {
                newRankMap.put(krwMarkets.get(i), i + 1);
            }
            volumeRankMap = newRankMap;

            // TOP-N 추출
            List<String> topN = new ArrayList<String>();
            int count = 0;
            for (String m : krwMarkets) {
                if (count >= DEFAULT_TOP_N) break;
                topN.add(m);
                count++;
            }

            // 현재 구독 중이 아닌 종목만 추가
            Set<String> currentSubscribed = new HashSet<String>(subscribedMarkets);
            List<String> newMarkets = new ArrayList<String>();
            for (String m : topN) {
                if (!currentSubscribed.contains(m)) {
                    newMarkets.add(m);
                }
            }

            if (!newMarkets.isEmpty()) {
                log.info("[SharedPrice] 신규 TOP-N 종목 발견: {}개 → 동적 추가: {}",
                        newMarkets.size(), newMarkets);
                addMarketsToActiveConnection(newMarkets);
            }
        } catch (Exception e) {
            log.debug("[SharedPrice] fastAddNewMarkets 실패: {}", e.getMessage());
        }
    }

    /**
     * 기존 활성 WebSocket connection에 새 종목을 추가 구독.
     * 재연결 없이 동일 connection에 추가 ticket 구독 메시지 전송.
     * Upbit WS는 동일 connection에 여러 ticket의 구독을 누적 가능.
     */
    private void addMarketsToActiveConnection(List<String> newMarkets) {
        if (activeWebSockets.isEmpty()) {
            // 활성 connection이 없으면 extraMarkets에만 추가하고 다음 refresh에 반영
            extraMarkets.addAll(newMarkets);
            log.info("[SharedPrice] 활성 connection 없음 → extraMarkets에만 추가");
            return;
        }

        // 첫 번째 활성 connection에 추가 구독 메시지 전송
        WebSocket ws = activeWebSockets.get(0);
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[{\"ticket\":\"shared-price-add-").append(System.currentTimeMillis()).append("\"},");
            sb.append("{\"type\":\"ticker\",\"codes\":[");
            for (int i = 0; i < newMarkets.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(newMarkets.get(i)).append("\"");
            }
            sb.append("]}]");

            boolean sent = ws.send(sb.toString());
            if (sent) {
                // subscribedMarkets에 누적
                List<String> updated = new ArrayList<String>(subscribedMarkets);
                updated.addAll(newMarkets);
                subscribedMarkets = updated;
                extraMarkets.addAll(newMarkets);
                log.info("[SharedPrice] 동적 추가 구독 성공: {}개 → 총 {}개",
                        newMarkets.size(), subscribedMarkets.size());
            } else {
                log.warn("[SharedPrice] 동적 추가 구독 메시지 전송 실패 → extraMarkets에만 추가");
                extraMarkets.addAll(newMarkets);
            }
        } catch (Exception e) {
            log.warn("[SharedPrice] 동적 추가 중 오류: {}", e.getMessage());
            extraMarkets.addAll(newMarkets);
        }
    }

    private void refreshMarketList() {
        // 재연결 진행 중이면 스킵
        if (reconnecting.get()) {
            log.debug("[SharedPrice] 재연결 진행 중, 마켓 갱신 스킵");
            return;
        }

        try {
            Set<String> allKrwMarkets = catalogService.getAllMarketCodes();
            if (allKrwMarkets == null || allKrwMarkets.isEmpty()) {
                log.warn("[SharedPrice] KRW 마켓 목록 비어있음");
                return;
            }

            // KRW 마켓만 필터
            List<String> krwMarkets = new ArrayList<String>();
            for (String m : allKrwMarkets) {
                if (m.startsWith("KRW-")) krwMarkets.add(m);
            }

            // 거래대금 기준 TOP-N 정렬
            List<UpbitMarketCatalogService.TickerItem> tickers = catalogService.fetchTickers(krwMarkets);
            final Map<String, Double> volumeMap = new HashMap<String, Double>();
            for (UpbitMarketCatalogService.TickerItem t : tickers) {
                volumeMap.put(t.market, t.acc_trade_price_24h);
            }
            Collections.sort(krwMarkets, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    double va = volumeMap.containsKey(a) ? volumeMap.get(a) : 0;
                    double vb = volumeMap.containsKey(b) ? volumeMap.get(b) : 0;
                    return Double.compare(vb, va);
                }
            });

            // TOP-N + 추가 요청 마켓 합산
            Set<String> newMarkets = new LinkedHashSet<String>();
            int count = 0;
            for (String m : krwMarkets) {
                if (count >= DEFAULT_TOP_N) break;
                newMarkets.add(m);
                count++;
            }
            newMarkets.addAll(extraMarkets);

            List<String> newList = new ArrayList<String>(newMarkets);

            // 변경 없으면 스킵
            if (newList.equals(subscribedMarkets) && connected.get()) {
                return;
            }

            subscribedMarkets = newList;
            log.info("[SharedPrice] 마켓 목록 갱신: {}개 (TOP{}+extra{})",
                    newList.size(), Math.min(DEFAULT_TOP_N, krwMarkets.size()), extraMarkets.size());

            // WebSocket 재연결
            connectWebSocket(newList);
            reconnectCount.set(0);

        } catch (Exception e) {
            log.error("[SharedPrice] 마켓 목록 갱신 실패", e);
        }
    }

    // ========== Internal: WebSocket ==========

    private void connectWebSocket(List<String> markets) {
        synchronized (connectLock) {
            // 최소 재연결 간격 보장 (429 방지)
            long elapsed = System.currentTimeMillis() - lastConnectMs;
            if (lastConnectMs > 0 && elapsed < MIN_RECONNECT_INTERVAL_MS) {
                long waitMs = MIN_RECONNECT_INTERVAL_MS - elapsed;
                log.info("[SharedPrice] 재연결 쿨다운 {}ms 대기", waitMs);
                try { Thread.sleep(waitMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            disconnectWebSocket();
            if (markets.isEmpty()) return;

            wsClient = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();

            // 15개씩 청크 분할
            List<List<String>> chunks = new ArrayList<List<String>>();
            for (int i = 0; i < markets.size(); i += WS_MAX_CODES_PER_CONN) {
                int end = Math.min(i + WS_MAX_CODES_PER_CONN, markets.size());
                chunks.add(new ArrayList<String>(markets.subList(i, end)));
            }

            // 커넥션 간 딜레이를 두어 429 방지
            for (int idx = 0; idx < chunks.size(); idx++) {
                if (idx > 0) {
                    try { Thread.sleep(500); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                openOneWebSocket(chunks.get(idx), idx);
            }

            // 기존 Ping 태스크 취소 후 새로 등록
            if (pingTask != null) {
                pingTask.cancel(false);
            }
            pingTask = scheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    for (WebSocket ws : activeWebSockets) {
                        try { ws.send("PING"); } catch (Exception e) { /* ignore */ }
                    }
                }
            }, WS_PING_INTERVAL_SEC, WS_PING_INTERVAL_SEC, TimeUnit.SECONDS);

            lastConnectMs = System.currentTimeMillis();

            log.info("[SharedPrice] WebSocket 연결: {}개 커넥션, {}개 마켓",
                    chunks.size(), markets.size());
        }
    }

    private void disconnectWebSocket() {
        synchronized (connectLock) {
            // 의도적 해제 플래그 → onClosed/onFailure에서 재연결 방지
            intentionalDisconnect.set(true);

            for (WebSocket ws : activeWebSockets) {
                try { ws.close(1000, "refresh"); } catch (Exception e) { /* ignore */ }
            }
            activeWebSockets.clear();
            connected.set(false);

            if (wsClient != null) {
                wsClient.dispatcher().executorService().shutdown();
                wsClient = null;
            }

            // 플래그 해제는 약간의 딜레이 후 (비동기 onClosed 콜백이 처리될 시간)
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        intentionalDisconnect.set(false);
                    }
                }, 3, TimeUnit.SECONDS);
            }
        }
    }

    private void openOneWebSocket(final List<String> codes, final int connIndex) {
        Request request = new Request.Builder().url(WS_URL).build();
        WebSocket ws = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                log.info("[SharedPrice] WS[{}] connected, subscribing {} codes", connIndex, codes.size());
                connected.set(true);

                StringBuilder sb = new StringBuilder();
                sb.append("[{\"ticket\":\"shared-price-").append(connIndex).append("\"},");
                sb.append("{\"type\":\"ticker\",\"codes\":[");
                for (int i = 0; i < codes.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(codes.get(i)).append("\"");
                }
                sb.append("]}]");
                webSocket.send(sb.toString());
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                handleMessage(bytes.utf8());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                activeWebSockets.remove(webSocket);
                // 의도적 해제 시 재연결하지 않음
                if (intentionalDisconnect.get()) return;
                if (activeWebSockets.isEmpty()) {
                    connected.set(false);
                    attemptReconnect();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log.warn("[SharedPrice] WS[{}] failure: {}", connIndex, t.getMessage());
                activeWebSockets.remove(webSocket);
                // 의도적 해제 시 재연결하지 않음
                if (intentionalDisconnect.get()) return;
                if (activeWebSockets.isEmpty()) {
                    connected.set(false);
                    attemptReconnect();
                }
            }
        });
        activeWebSockets.add(ws);
    }

    private void handleMessage(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String type = node.has("type") ? node.get("type").asText() : "";
            if (!"ticker".equals(type)) return;

            String code = node.has("code") ? node.get("code").asText() : null;
            double tradePrice = node.has("trade_price") ? node.get("trade_price").asDouble() : 0;
            if (code == null || tradePrice <= 0) return;

            latestPrices.put(code, tradePrice);
            int cnt = msgCount.incrementAndGet();

            // 30초마다 alive 로그
            long now = System.currentTimeMillis();
            if (now - lastLogMs > 30000) {
                lastLogMs = now;
                log.info("[SharedPrice] alive: {}msgs, {}markets, {}listeners, connected={}",
                        cnt, latestPrices.size(), globalListeners.size(), connected.get());
            }

            // 리스너 통지
            notifyListeners(code, tradePrice);

        } catch (Exception e) {
            log.debug("[SharedPrice] 메시지 파싱 오류: {}", e.getMessage());
        }
    }

    private void notifyListeners(String market, double price) {
        for (PriceUpdateListener listener : globalListeners) {
            try {
                listener.onPriceUpdate(market, price);
            } catch (Exception e) {
                log.error("[SharedPrice] 리스너 콜백 오류: {}", e.getMessage());
            }
        }
    }

    private void attemptReconnect() {
        // CAS로 중복 재연결 방지: 이미 reconnecting이면 스킵
        if (!reconnecting.compareAndSet(false, true)) {
            log.debug("[SharedPrice] 이미 재연결 진행 중, 스킵");
            return;
        }

        int attempt = reconnectCount.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("[SharedPrice] 최대 재연결 횟수({}) 초과, 60초 후 재시도", MAX_RECONNECT_ATTEMPTS);
            reconnectCount.set(0);
            reconnecting.set(false);
            if (scheduler != null) {
                scheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        refreshMarketList();
                    }
                }, 60, TimeUnit.SECONDS);
            }
            return;
        }

        // 지수 백오프: 2s, 4s, 8s, 16s, 30s (최대) — 최소 2초부터
        long delaySec = Math.min(30, (long) Math.pow(2, attempt));
        log.info("[SharedPrice] 재연결 시도 {}/{} ({}초 후)", attempt, MAX_RECONNECT_ATTEMPTS, delaySec);

        if (scheduler != null) {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        connectWebSocket(subscribedMarkets);
                    } catch (Exception e) {
                        log.error("[SharedPrice] 재연결 실패", e);
                        attemptReconnect();
                    } finally {
                        reconnecting.set(false);
                    }
                }
            }, delaySec, TimeUnit.SECONDS);
        } else {
            reconnecting.set(false);
        }
    }
}
