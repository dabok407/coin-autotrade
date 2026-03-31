package com.example.upbit.trade;

import com.example.upbit.config.TradeProperties;
import com.example.upbit.db.OrderEntity;
import com.example.upbit.db.OrderRepository;
import com.example.upbit.security.ApiKeyStoreService;
import com.example.upbit.upbit.UpbitPrivateClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for retry/backoff logic in UpbitPrivateClient.executeWithRetry,
 * exercised through LiveOrderService's order methods.
 *
 * The retry logic handles:
 * - 429 (TooManyRequests): retry with longer backoff
 * - 5xx (server errors): retry with standard backoff
 * - 4xx (client errors except 429): no retry
 * - 404 in order polling: returns order_not_found state
 */
@ExtendWith(MockitoExtension.class)
public class LiveOrderServiceRetryTest {

    @Mock private RestTemplate restTemplate;
    @Mock private OrderRepository orderRepo;
    @Mock private ApiKeyStoreService keyStore;

    private UpbitPrivateClient upbitClient;
    private LiveOrderService liveOrderService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    public void setUp() throws Exception {
        upbitClient = new UpbitPrivateClient(restTemplate, orderRepo, keyStore);

        // Configure API keys so isConfigured() returns true
        ApiKeyStoreService.Keys testKeys = new ApiKeyStoreService.Keys("test-access-key", "test-secret-key");
        lenient().when(keyStore.loadUpbitKeys()).thenReturn(testKeys);

        // No existing orders (prevent idempotency check from short-circuiting)
        lenient().when(orderRepo.findByIdentifier(anyString())).thenReturn(Optional.<OrderEntity>empty());
        lenient().when(orderRepo.save(any(OrderEntity.class))).thenAnswer(new Answer<OrderEntity>() {
            @Override
            public OrderEntity answer(InvocationOnMock invocation) throws Throwable {
                return invocation.getArgument(0);
            }
        });

        // Setup TradeProperties
        TradeProperties tradeProps = new TradeProperties();
        TradeProperties.Live live = new TradeProperties.Live();
        live.setOrderPollIntervalMs(10); // short interval for tests
        live.setOrderPollTimeoutMs(100);
        live.setOrderTestBeforePlace(false); // skip test order
        live.setCancelOnTimeout(false);
        tradeProps.setLive(live);

        liveOrderService = new LiveOrderService(upbitClient, orderRepo, tradeProps);
    }

    /**
     * Helper: Build a JSON response for a successful order.
     */
    private String buildOrderResponse(String uuid, String state, double executedVolume, double avgPrice) {
        ObjectNode node = om.createObjectNode();
        node.put("uuid", uuid);
        node.put("state", state);
        node.put("executed_volume", executedVolume);
        node.put("avg_price", avgPrice);
        node.put("identifier", "test-id");
        return node.toString();
    }

    // ===== Tests =====

    /**
     * (a) Test 429 retry with backoff.
     * Mock Upbit API to return 429 twice then success. Verify 3 total calls.
     */
    @Test
    public void test429RetryWithBackoff() throws Exception {
        final AtomicInteger callCount = new AtomicInteger(0);

        when(restTemplate.exchange(
                contains("/v1/order"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenAnswer(new Answer<ResponseEntity<String>>() {
            @Override
            public ResponseEntity<String> answer(InvocationOnMock invocation) throws Throwable {
                int count = callCount.incrementAndGet();
                if (count <= 2) {
                    throw HttpClientErrorException.create(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "Too Many Requests",
                            HttpHeaders.EMPTY,
                            "rate limited".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8
                    );
                }
                String body = buildOrderResponse("uuid-123", "done", 1.0, 50000);
                return ResponseEntity.ok(body);
            }
        });

        JsonNode result = upbitClient.getOrderByUuidOrIdentifier("uuid-123", null);

        assertEquals(3, callCount.get());
        assertNotNull(result);
        assertEquals("done", result.get("state").asText());
    }

    /**
     * (b) Test 5xx retry with backoff.
     * Mock Upbit API to return 500 once then success. Verify 2 total calls.
     */
    @Test
    public void test5xxRetryWithBackoff() throws Exception {
        final AtomicInteger callCount = new AtomicInteger(0);

        when(restTemplate.exchange(
                contains("/v1/order"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenAnswer(new Answer<ResponseEntity<String>>() {
            @Override
            public ResponseEntity<String> answer(InvocationOnMock invocation) throws Throwable {
                int count = callCount.incrementAndGet();
                if (count <= 1) {
                    throw HttpServerErrorException.create(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Internal Server Error",
                            HttpHeaders.EMPTY,
                            "server error".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8
                    );
                }
                String body = buildOrderResponse("uuid-456", "done", 2.0, 60000);
                return ResponseEntity.ok(body);
            }
        });

        JsonNode result = upbitClient.getOrderByUuidOrIdentifier("uuid-456", null);

        assertEquals(2, callCount.get());
        assertNotNull(result);
        assertEquals("done", result.get("state").asText());
    }

    /**
     * (c) Test 4xx (non-429) no retry.
     * Mock Upbit API to return 400. Verify exactly 1 call.
     */
    @Test
    public void test4xxNoRetry() throws Exception {
        final AtomicInteger callCount = new AtomicInteger(0);

        when(restTemplate.exchange(
                contains("/v1/order"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenAnswer(new Answer<ResponseEntity<String>>() {
            @Override
            public ResponseEntity<String> answer(InvocationOnMock invocation) throws Throwable {
                callCount.incrementAndGet();
                throw HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST,
                        "Bad Request",
                        HttpHeaders.EMPTY,
                        "bad request".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                );
            }
        });

        try {
            upbitClient.getOrderByUuidOrIdentifier("uuid-789", null);
            fail("Should have thrown HttpClientErrorException");
        } catch (HttpClientErrorException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        }

        assertEquals(1, callCount.get());
    }

    /**
     * (d) Test 404 in polling returns OrderResult with state="order_not_found" and isFilled()=true.
     *
     * When LiveOrderService polls an order and gets 404 (NotFound), it should
     * return an OrderResult with state "order_not_found" representing instant fill.
     */
    @Test
    public void test404InPollingReturnsOrderNotFound() throws Exception {
        // Mock placeOrder POST call to succeed
        when(restTemplate.exchange(
                contains("/v1/orders"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok(buildOrderResponse("uuid-404", "wait", 0, 0)));

        // Mock getOrder GET call to throw 404
        when(restTemplate.exchange(
                contains("/v1/order?"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                "not found".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        ));

        LiveOrderService.OrderResult result = liveOrderService.placeBidPriceOrder("KRW-BTC", 10000);

        assertNotNull(result);
        assertEquals("order_not_found", result.state);
        assertTrue(result.isFilled(), "404 should be treated as filled (instant execution)");
    }

    /**
     * Additional: Verify OrderResult.isFilled() returns true for "done" state.
     */
    @Test
    public void testOrderResultIsFilledForDoneState() {
        LiveOrderService.OrderResult result =
                new LiveOrderService.OrderResult("id1", "uuid1", "done", 1.0, 50000, null);
        assertTrue(result.isFilled());
        assertTrue(result.isDone());
    }

    /**
     * Additional: Verify OrderResult.isFilled() returns true for "cancel" with executedVolume > 0.
     */
    @Test
    public void testOrderResultIsFilledForCancelWithVolume() {
        LiveOrderService.OrderResult result =
                new LiveOrderService.OrderResult("id2", "uuid2", "cancel", 0.5, 50000, null);
        assertTrue(result.isFilled(), "cancel with executedVolume > 0 should be considered filled");
        assertFalse(result.isDone());
    }

    /**
     * Additional: Verify OrderResult.isFilled() returns false for "cancel" with executedVolume = 0.
     */
    @Test
    public void testOrderResultNotFilledForCancelWithZeroVolume() {
        LiveOrderService.OrderResult result =
                new LiveOrderService.OrderResult("id3", "uuid3", "cancel", 0.0, 0, null);
        assertFalse(result.isFilled(), "cancel with executedVolume = 0 should not be filled");
    }

    /**
     * Additional: Verify OrderResult.isFilled() returns false for "wait" state.
     */
    @Test
    public void testOrderResultNotFilledForWaitState() {
        LiveOrderService.OrderResult result =
                new LiveOrderService.OrderResult("id4", "uuid4", "wait", 0.0, 0, null);
        assertFalse(result.isFilled(), "wait state should not be filled");
    }

    /**
     * Additional: Verify OrderResult.isFilled() returns true for "order_not_found" state.
     */
    @Test
    public void testOrderResultIsFilledForOrderNotFound() {
        LiveOrderService.OrderResult result =
                new LiveOrderService.OrderResult("id5", "uuid5", "order_not_found", 0.0, 0, null);
        assertTrue(result.isFilled(), "order_not_found should be treated as filled");
    }
}
