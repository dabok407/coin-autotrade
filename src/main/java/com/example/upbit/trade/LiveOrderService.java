package com.example.upbit.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.upbit.config.TradeProperties;
import com.example.upbit.db.OrderEntity;
import com.example.upbit.db.OrderRepository;
import com.example.upbit.upbit.UpbitPrivateClient;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * LIVE 모드 주문 실행(주문 생성 + 체결/상태 폴링 + DB 동기화).
 *
 * 안전장치:
 * 1) identifier(멱등키)로 중복주문 방지
 * 2) 체결(done) 전에는 "pending"으로 간주하고 전략이 추가 주문/청산을 내지 않도록 함
 * 3) 타임아웃 정책: cancelOnTimeout 옵션으로 취소 요청 가능(기본 false)
 */
@Service
public class LiveOrderService {

    private final UpbitPrivateClient upbit;
    private final OrderRepository orderRepo;
    private final TradeProperties tradeProps;

    public LiveOrderService(UpbitPrivateClient upbit, OrderRepository orderRepo, TradeProperties tradeProps) {
        this.upbit = upbit;
        this.orderRepo = orderRepo;
        this.tradeProps = tradeProps;
    }

    public boolean isConfigured() {
        return upbit.isConfigured();
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LiveOrderService.class);

    /**
     * 원화 지정 주문(bid, ord_type=price): "원화 금액"을 지정하고 수량은 업비트가 계산합니다.
     */
    public OrderResult placeBidPriceOrder(String market, double krwAmount) {
        String identifier = newIdentifier();
        if (tradeProps.getLive().isOrderTestBeforePlace()) {
            try {
                upbit.orderTest(market, "bid", "price", krwAmount, null, identifier);
            } catch (Exception e) {
                // orderTest 실패 시 실제 주문은 시도하되, 심각한 인증 오류면 차단
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("401") || msg.contains("Unauthorized")) {
                    log.error("[LIVE] orderTest 401 인증 실패 — API 키를 확인하세요. market={}", market, e);
                    throw new LiveOrderException("API 키 인증 실패 (401 Unauthorized). 업비트 대시보드에서 API 키 상태를 확인하세요.", e);
                }
                log.warn("[LIVE] orderTest 실패(비인증 오류) — 실제 주문으로 진행합니다. market={}, error={}", market, msg);
            }
        }
        JsonNode created = upbit.placeOrder(market, "bid", "price", krwAmount, null, identifier);
        String uuid = created.has("uuid") ? created.get("uuid").asText() : null;
        return pollUntilFinal(identifier, uuid);
    }

    /** 시장가 매도(ask, ord_type=market): 수량(volume) 지정 */
    public OrderResult placeAskMarketOrder(String market, double volume) {
        String identifier = newIdentifier();
        if (tradeProps.getLive().isOrderTestBeforePlace()) {
            try {
                upbit.orderTest(market, "ask", "market", null, volume, identifier);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("401") || msg.contains("Unauthorized")) {
                    log.error("[LIVE] orderTest 401 인증 실패 — API 키를 확인하세요. market={}", market, e);
                    throw new LiveOrderException("API 키 인증 실패 (401 Unauthorized). 업비트 대시보드에서 API 키 상태를 확인하세요.", e);
                }
                log.warn("[LIVE] orderTest 실패(비인증 오류) — 실제 주문으로 진행합니다. market={}, error={}", market, msg);
            }
        }
        JsonNode created = upbit.placeOrder(market, "ask", "market", null, volume, identifier);
        String uuid = created.has("uuid") ? created.get("uuid").asText() : null;
        return pollUntilFinal(identifier, uuid);
    }

    /**
     * 간단 안전 정책: order_log에서 state가 wait인 것이 있으면 pending으로 간주.
     * (더 엄밀하게는 market별 최신 wait만 보도록 쿼리를 최적화하면 됩니다.)
     */
    public boolean hasPendingOrder(String market) {
        // 최근 1시간(3600초) 이내 주문만 pending 검사 (오래된 wait 상태는 실제 체결됐을 가능성이 높음)
        long cutoffMs = System.currentTimeMillis() - 3_600_000L;
        for (OrderEntity o : orderRepo.findAll()) {
            if (!market.equals(o.getMarket())) continue;
            if (!"wait".equalsIgnoreCase(o.getState())) continue;
            if (o.getTsEpochMs() < cutoffMs) continue; // 너무 오래된 wait는 무시
            return true;
        }
        return false;
    }

    /** 재시작 시 주문 상태 동기화: wait 상태 주문을 1회 조회해 최신 상태로 업데이트 */
    public void syncPendingOrders() {
        for (OrderEntity o : orderRepo.findAll()) {
            if (o.getUuid() == null) continue;
            if (!"wait".equalsIgnoreCase(o.getState())) continue;
            pollUntilFinal(o.getIdentifier(), o.getUuid(), 1);
        }
    }

    private OrderResult pollUntilFinal(String identifier, String uuid) {
        return pollUntilFinal(identifier, uuid, -1);
    }

    private OrderResult pollUntilFinal(String identifier, String uuid, int forceOnce) {
        long start = System.currentTimeMillis();
        long timeout = tradeProps.getLive().getOrderPollTimeoutMs();
        long interval = tradeProps.getLive().getOrderPollIntervalMs();

        while (true) {
            JsonNode cur;
            try {
                cur = upbit.getOrderByUuidOrIdentifier(uuid, identifier);
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e404) {
                // 주문이 즉시 체결되어 업비트 API에서 조회 불가 → 체결 완료로 간주
                log.warn("[LIVE-POLL] 404 주문 조회 불가 (즉시 체결 추정) uuid={} identifier={}", uuid, identifier);
                updateOrderRow(identifier, uuid, "order_not_found", 0.0, 0.0);
                return new OrderResult(identifier, uuid, "order_not_found", 0.0, 0.0, null);
            }
            String state = cur.has("state") ? cur.get("state").asText() : null;
            double executedVolume = cur.has("executed_volume") ? cur.get("executed_volume").asDouble(0.0) : 0.0;
            double avgPrice = computeAvgPrice(cur);
            updateOrderRow(identifier, uuid, state, executedVolume, avgPrice);

            if ("done".equalsIgnoreCase(state) || "cancel".equalsIgnoreCase(state)) {
                return new OrderResult(identifier, uuid, state, executedVolume, avgPrice, cur);
            }
            if (forceOnce == 1) {
                return new OrderResult(identifier, uuid, state, executedVolume, avgPrice, cur);
            }

            if (System.currentTimeMillis() - start >= timeout) {
                if (tradeProps.getLive().isCancelOnTimeout() && uuid != null) {
                    try { upbit.cancelOrder(uuid); } catch (Exception ignore) {}
                }
                return new OrderResult(identifier, uuid, "timeout", executedVolume, avgPrice, cur);
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new OrderResult(identifier, uuid, "interrupted", executedVolume, avgPrice, cur);
            }
        }
    }

    private void updateOrderRow(String identifier, String uuid, String state, double executedVolume, double avgPrice) {
        Optional<OrderEntity> opt = orderRepo.findByIdentifier(identifier);
        if (!opt.isPresent()) return;
        OrderEntity o = opt.get();
        if (uuid != null && o.getUuid() == null) o.setUuid(uuid);
        o.setState(state);
        // DB 엔티티는 금액/수량을 BigDecimal로 관리
        o.setExecutedVolume(java.math.BigDecimal.valueOf(executedVolume));
        o.setAvgPrice(java.math.BigDecimal.valueOf(avgPrice));
        o.setTsEpochMs(System.currentTimeMillis());
        orderRepo.save(o);
    }

    private double computeAvgPrice(JsonNode order) {
        if (order == null) return 0.0;
        if (order.has("avg_price")) {
            // 우선 avg_price가 있으면 그 값을 사용
            double ap = order.get("avg_price").asDouble(0.0);
            if (ap > 0) return ap;
        }
        if (!order.has("trades")) return 0.0;
        JsonNode trades = order.get("trades");
        if (trades == null || !trades.isArray() || trades.size() == 0) return 0.0;
        double notional = 0.0;
        double qty = 0.0;
        for (JsonNode t : trades) {
            double p = t.has("price") ? t.get("price").asDouble(0.0) : 0.0;
            double v = t.has("volume") ? t.get("volume").asDouble(0.0) : 0.0;
            notional += p * v;
            qty += v;
        }
        if (qty <= 0) return 0.0;
        return notional / qty;
    }

    private String newIdentifier() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static class OrderResult {
        public final String identifier;
        public final String uuid;
        public final String state;
        public final double executedVolume;
        public final double avgPrice;
        public final JsonNode raw;

        public OrderResult(String identifier, String uuid, String state, double executedVolume, double avgPrice, JsonNode raw) {
            this.identifier = identifier;
            this.uuid = uuid;
            this.state = state;
            this.executedVolume = executedVolume;
            this.avgPrice = avgPrice;
            this.raw = raw;
        }

        public boolean isDone() { return "done".equalsIgnoreCase(state); }

        /**
         * 시장가 매수(ord_type=price)는 KRW를 다 소진하면 state=cancel로 돌아옵니다.
         * (잔여 원화가 최소 주문단위 미만이라 자동 취소됨)
         * executedVolume > 0 이면 실제로 체결된 것이므로 "성공"으로 간주합니다.
         */
        public boolean isFilled() {
            if ("done".equalsIgnoreCase(state)) return true;
            if ("cancel".equalsIgnoreCase(state) && executedVolume > 0) return true;
            // 주문 전송 후 404 → 즉시 체결되어 조회 불가 (placeOrder 성공 후에만 도달)
            if ("order_not_found".equalsIgnoreCase(state)) return true;
            return false;
        }
    }
}
