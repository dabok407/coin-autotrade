package com.example.upbit.upbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.upbit.db.OrderEntity;
import com.example.upbit.db.OrderRepository;
import com.example.upbit.security.ApiKeyStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.Callable;
import java.math.BigDecimal;

/**
 * 업비트 인증이 필요한 API 전용 클라이언트.
 * - 주문 생성 / 주문 조회 / 주문 취소 / 주문 테스트 포함
 *
 * 중복 주문 방지:
 * - identifier(멱등키)로 주문을 구분하고, DB(order_log)에 UNIQUE로 저장합니다.
 * - 동일 identifier로 placeOrder를 재호출하면, DB에 저장된 uuid를 반환해서 중복 전송을 막습니다.
 */
@Component
public class UpbitPrivateClient {

    private static final Logger log = LoggerFactory.getLogger(UpbitPrivateClient.class);

    private static BigDecimal bd(Double v) { return v == null ? null : BigDecimal.valueOf(v); }

    private final RestTemplate restTemplate;
    private final ObjectMapper om = new ObjectMapper();
    private final OrderRepository orderRepo;
    private final ApiKeyStoreService keyStore;

    @Value("${upbit.accessKey:}")
    private String accessKey;

    @Value("${upbit.secretKey:}")
    private String secretKey;

    public UpbitPrivateClient(RestTemplate restTemplate, OrderRepository orderRepo, ApiKeyStoreService keyStore) {
        this.restTemplate = restTemplate;
        this.orderRepo = orderRepo;
        this.keyStore = keyStore;
    }

    public boolean isConfigured() {
        ApiKeyStoreService.Keys k = keyStore != null ? keyStore.loadUpbitKeys() : null;
        if (k != null) return true;
        return accessKey != null && !accessKey.isEmpty() && secretKey != null && !secretKey.isEmpty();
    }


private ApiKeyStoreService.Keys resolveKeys() {
    ApiKeyStoreService.Keys k = (keyStore != null ? keyStore.loadUpbitKeys() : null);
    if (k != null) return k;
    if (accessKey == null || accessKey.isEmpty() || secretKey == null || secretKey.isEmpty()) return null;
    return new ApiKeyStoreService.Keys(accessKey, secretKey);
}

    public JsonNode orderTest(String market, String side, String ordType, Double price, Double volume, String identifier) {
        return post("/v1/orders/test", market, side, ordType, price, volume, identifier, true);
    }

    public JsonNode placeOrder(String market, String side, String ordType, Double price, Double volume, String identifier) {
        return executeWithRetry("placeOrder(" + market + "," + side + ")", new Callable<JsonNode>() {
            @Override
            public JsonNode call() {
                return post("/v1/orders", market, side, ordType, price, volume, identifier, false);
            }
        });
    }

    private JsonNode post(String path, String market, String side, String ordType, Double price, Double volume, String identifier, boolean isTest) {
        if (!isConfigured()) throw new IllegalStateException("Upbit keys not configured.");

        Optional<OrderEntity> existing = orderRepo.findByIdentifier(identifier);
        if (existing.isPresent() && existing.get().getUuid() != null && !isTest) {
            // 이미 전송된 주문(멱등) -> uuid 반환
            return om.createObjectNode()
                    .put("uuid", existing.get().getUuid())
                    .put("state", existing.get().getState() == null ? "unknown" : existing.get().getState())
                    .put("identifier", identifier);
        }

        Map<String, String> params = new HashMap<String, String>();
        params.put("market", market);
        params.put("side", side);
        params.put("ord_type", ordType);
        params.put("identifier", identifier);
        if (price != null) params.put("price", strip(price));
        if (volume != null) params.put("volume", strip(volume));

        ApiKeyStoreService.Keys k = resolveKeys();
        if (k == null) throw new IllegalStateException("Upbit keys not configured.");
        String jwt = UpbitAuth.createJwt(k.accessKey, k.secretKey, params);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // ★ 중요: JSON body의 키 순서가 query_hash 계산 시 사용한 쿼리 문자열 순서와 일치해야 함
        //   (업비트 v1.6.1 인증 문서: "실제 요청 내용과 인증 토큰에 포함된 내용 및 문자열 구성 순서가
        //    다른 경우 토큰 검증 오류가 발생할 수 있습니다")
        //   UpbitAuth.buildQueryString은 키를 알파벳 순으로 정렬하므로, body도 동일하게 정렬
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        List<String> sortedKeys = new ArrayList<String>(params.keySet());
        Collections.sort(sortedKeys);
        for (String key : sortedKeys) body.put(key, params.get(key));

        ResponseEntity<String> res;
        try {
            // 디버그: 실제 전송되는 JSON body 확인
            try {
                String debugBody = om.writeValueAsString(body);
                org.slf4j.LoggerFactory.getLogger(UpbitPrivateClient.class)
                    .info("[UPBIT-REQ] POST {} | body={}", path, debugBody);
            } catch (Exception ignore) {}

            res = restTemplate.exchange(
                    "https://api.upbit.com" + path,
                    HttpMethod.POST,
                    new HttpEntity<Map<String, Object>>(body, headers),
                    String.class
            );
        } catch (org.springframework.web.client.HttpClientErrorException hce) {
            // 4xx 에러: 상세 바디를 포함한 메시지로 re-throw
            String errBody = hce.getResponseBodyAsString();
            String detail = (errBody != null && !errBody.isEmpty()) ? errBody : "[no body]";
            throw new RuntimeException(
                    String.format("[UPBIT %s] %d %s | path=%s market=%s side=%s | body=%s",
                            isTest ? "TEST" : "ORDER",
                            hce.getStatusCode().value(), hce.getStatusText(),
                            path, market, side, detail),
                    hce);
        }

        JsonNode node;
        try {
            node = om.readTree(res.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (!isTest) {
            OrderEntity oe = existing.orElseGet(OrderEntity::new);
            oe.setIdentifier(identifier);
            oe.setMarket(market);
            oe.setSide(side);
            oe.setOrdType(ordType);
            oe.setPrice(bd(price));
            oe.setVolume(bd(volume));
            oe.setTsEpochMs(System.currentTimeMillis());
            if (node.has("uuid")) oe.setUuid(node.get("uuid").asText());
            if (node.has("state")) oe.setState(node.get("state").asText());
            orderRepo.save(oe);
        }

        return node;
    }

    public JsonNode getOrderByUuidOrIdentifier(final String uuid, final String identifier) {
        return executeWithRetry("getOrder(" + (uuid != null ? uuid : identifier) + ")", new Callable<JsonNode>() {
            @Override
            public JsonNode call() {
                return doGetOrderByUuidOrIdentifier(uuid, identifier);
            }
        });
    }

    private JsonNode doGetOrderByUuidOrIdentifier(String uuid, String identifier) {
        if (!isConfigured()) throw new IllegalStateException("Upbit keys not configured.");

        Map<String, String> params = new HashMap<String, String>();
        if (uuid != null && !uuid.isEmpty()) params.put("uuid", uuid);
        else params.put("identifier", identifier);

        ApiKeyStoreService.Keys k = resolveKeys();
        if (k == null) throw new IllegalStateException("Upbit keys not configured.");
        String jwt = UpbitAuth.createJwt(k.accessKey, k.secretKey, params);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);

        String qs = buildQuery(params);
        ResponseEntity<String> res = restTemplate.exchange(
                "https://api.upbit.com/v1/order" + qs,
                HttpMethod.GET,
                new HttpEntity<Void>(headers),
                String.class
        );

        try {
            return om.readTree(res.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public JsonNode cancelOrder(final String uuid) {
        return executeWithRetry("cancelOrder(" + uuid + ")", new Callable<JsonNode>() {
            @Override
            public JsonNode call() {
                return doCancelOrder(uuid);
            }
        });
    }

    private JsonNode doCancelOrder(String uuid) {
        if (!isConfigured()) throw new IllegalStateException("Upbit keys not configured.");

        Map<String, String> params = new HashMap<String, String>();
        params.put("uuid", uuid);
        ApiKeyStoreService.Keys k = resolveKeys();
        if (k == null) throw new IllegalStateException("Upbit keys not configured.");
        String jwt = UpbitAuth.createJwt(k.accessKey, k.secretKey, params);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("uuid", uuid);

        ResponseEntity<String> res = restTemplate.exchange(
                "https://api.upbit.com/v1/order",
                HttpMethod.DELETE,
                new HttpEntity<Map<String, Object>>(body, headers),
                String.class
        );

        try {
            return om.readTree(res.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 계정 잔고 조회 (/v1/accounts)
     * - Dashboard에서 KRW 잔고 표시 및 Capital max 제어에 사용
     */
    public List<UpbitAccount> getAccounts() {
        return executeWithRetry("getAccounts", new Callable<List<UpbitAccount>>() {
            @Override
            public List<UpbitAccount> call() {
                return doGetAccounts();
            }
        });
    }

    private List<UpbitAccount> doGetAccounts() {
        if (!isConfigured()) throw new IllegalStateException("Upbit keys not configured.");

        ApiKeyStoreService.Keys k = resolveKeys();
        if (k == null) throw new IllegalStateException("Upbit keys not configured.");

        // /v1/accounts 는 query param이 없음
        String jwt = UpbitAuth.createJwt(k.accessKey, k.secretKey, Collections.<String, String>emptyMap());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);

        ResponseEntity<String> res = restTemplate.exchange(
                "https://api.upbit.com/v1/accounts",
                HttpMethod.GET,
                new HttpEntity<Void>(headers),
                String.class
        );

        try {
            UpbitAccount[] arr = om.readValue(res.getBody(), UpbitAccount[].class);
            if (arr == null) return Collections.emptyList();
            return Arrays.asList(arr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private <T> T executeWithRetry(String label, Callable<T> action) {
        int maxRetries = 3;
        long baseDelayMs = 300;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.call();
            } catch (HttpServerErrorException e) {
                // 5xx - retry
                if (attempt == maxRetries) throw e;
                sleepBackoff(attempt, baseDelayMs, label);
            } catch (HttpClientErrorException.TooManyRequests e) {
                // 429 - retry with longer backoff
                if (attempt == maxRetries) throw e;
                sleepBackoff(attempt, baseDelayMs * 2, label);
            } catch (Exception e) {
                // All other errors - don't retry
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException(e);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private void sleepBackoff(int attempt, long baseMs, String label) {
        long ms = baseMs * (1L << Math.min(attempt, 4));
        log.warn("[UPBIT-RETRY] {} attempt {} failed, retrying in {}ms", label, attempt + 1, ms);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildQuery(Map<String, String> params) {
        List<String> keys = new ArrayList<String>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            String v = params.get(k);
            sb.append(i == 0 ? "?" : "&");
            sb.append(k).append("=").append(v);
        }
        return sb.toString();
    }

    private String strip(Double d) {
        String s = Double.toString(d);
        if (s.contains("E") || s.contains("e")) return s;
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}
