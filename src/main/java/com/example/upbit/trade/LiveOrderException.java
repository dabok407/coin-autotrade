package com.example.upbit.trade;

/**
 * LIVE 주문 실행 중 복구 불가능한 오류.
 * (예: 401 인증 실패, 키 만료 등)
 *
 * 이 예외는 per-market catch에서 잡혀서 해당 마켓만 BUY_FAILED로 기록되고,
 * 다른 마켓의 처리를 방해하지 않습니다.
 */
public class LiveOrderException extends RuntimeException {
    public LiveOrderException(String message) {
        super(message);
    }
    public LiveOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
