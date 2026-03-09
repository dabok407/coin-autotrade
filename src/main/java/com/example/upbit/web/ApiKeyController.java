package com.example.upbit.web;

import com.example.upbit.security.ApiKeyStoreService;
import com.example.upbit.upbit.UpbitPrivateClient;
import com.example.upbit.upbit.UpbitAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 키 저장은 서버에서만 접근 가능한 환경(로컬/내부망)에서 사용하세요.
 * 외부 공개 서비스라면 반드시 인증/인가를 붙이세요.
 */
@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyController.class);

    private final ApiKeyStoreService store;
    private final UpbitPrivateClient upbit;

    public ApiKeyController(ApiKeyStoreService store, UpbitPrivateClient upbit) {
        this.store = store;
        this.upbit = upbit;
    }

    @GetMapping("/status")
    public KeyStatus status() {
        KeyStatus s = new KeyStatus();
        s.keystoreReady = store.loadUpbitKeys() != null;
        return s;
    }

    @PostMapping("/upbit")
    public KeyStatus save(@RequestBody UpbitKeyRequest req) {
        if (req == null || isBlank(req.accessKey) || isBlank(req.secretKey)) {
            throw new IllegalArgumentException("accessKey/secretKey required");
        }
        store.saveUpbitKeys(req.accessKey.trim(), req.secretKey.trim());
        return status();
    }

    /**
     * API 키 종합 진단 테스트.
     * 1) 잔고 조회 (/v1/accounts) → 키 유효성 + IP 허용 확인
     * 2) 주문 테스트 (/v1/orders/test) → 주문 권한 확인
     * 결과를 FE에 상세히 반환한다.
     */
    @PostMapping("/test")
    public ApiKeyTestResult testApiKey(
            @RequestBody(required = false) OrderTestRequest req) {
        ApiKeyTestResult r = new ApiKeyTestResult();

        // 0. 키 설정 확인
        if (!upbit.isConfigured()) {
            r.keyConfigured = false;
            r.summary = "API 키가 설정되지 않았습니다. Settings에서 키를 등록하세요.";
            return r;
        }
        r.keyConfigured = true;

        // 1. 잔고 조회 (/v1/accounts)
        try {
            List<UpbitAccount> accounts = upbit.getAccounts();
            r.accountsOk = true;
            r.accountCount = accounts != null ? accounts.size() : 0;
            // KRW 잔고 표시
            if (accounts != null) {
                for (UpbitAccount a : accounts) {
                    if ("KRW".equals(a.currency)) {
                        r.krwBalance = a.balance;
                        r.krwLocked = a.locked;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            r.accountsOk = false;
            r.accountsError = extractErrorDetail(e);
            log.warn("[KEY-TEST] /v1/accounts 실패: {}", r.accountsError);
        }

        // 2. 주문 테스트 (/v1/orders/test)
        String testMarket = (req != null && req.market != null && !req.market.isEmpty())
                ? req.market : "KRW-BTC";
        double testAmount = (req != null && req.amount > 0) ? req.amount : 5001;
        try {
            upbit.orderTest(testMarket, "bid", "price", testAmount, null,
                    "test-" + UUID.randomUUID().toString().substring(0, 8));
            r.orderTestOk = true;
        } catch (Exception e) {
            r.orderTestOk = false;
            r.orderTestError = extractErrorDetail(e);
            log.warn("[KEY-TEST] /v1/orders/test 실패: {}", r.orderTestError);
        }

        // 진단 요약
        if (r.accountsOk && r.orderTestOk) {
            r.summary = "✅ 모든 테스트 통과! API 키가 정상 작동합니다.";
        } else if (!r.accountsOk) {
            String err = r.accountsError != null ? r.accountsError : "";
            if (err.contains("401") || err.contains("Unauthorized")) {
                r.summary = "❌ 잔고 조회 401 실패 — JWT 서명 또는 IP 허용 목록 문제입니다.\n"
                        + "• 업비트 > Open API 관리에서 허용 IP를 확인하세요\n"
                        + "• 네이버에서 '내 IP 주소' 검색 결과와 등록된 IP가 동일한지 확인하세요\n"
                        + "• ISP에서 공인 IP가 변경되었을 수 있습니다";
            } else {
                r.summary = "❌ 잔고 조회 실패: " + err;
            }
        } else {
            // accounts OK but orderTest failed
            r.summary = "⚠️ 잔고 조회 OK, 주문 테스트 실패: " + (r.orderTestError != null ? r.orderTestError : "unknown");
        }

        return r;
    }

    private String extractErrorDetail(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        // nested cause도 확인
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            String causeMsg = e.getCause().getMessage();
            if (causeMsg.length() > msg.length()) msg = causeMsg;
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static class UpbitKeyRequest {
        public String accessKey;
        public String secretKey;
    }

    public static class KeyStatus {
        public boolean keystoreReady;
    }

    public static class OrderTestRequest {
        public String market;
        public double amount;
    }

    public static class ApiKeyTestResult {
        public boolean keyConfigured;
        public boolean accountsOk;
        public int accountCount;
        public String krwBalance;
        public String krwLocked;
        public String accountsError;
        public boolean orderTestOk;
        public String orderTestError;
        public String summary;
    }
}
