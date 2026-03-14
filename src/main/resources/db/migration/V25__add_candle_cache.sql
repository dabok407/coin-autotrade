-- 캔들 데이터 캐시 테이블: 백테스트 최적화 시 API 호출 없이 캐시 데이터 사용
CREATE TABLE IF NOT EXISTS candle_cache (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    market     VARCHAR(20)  NOT NULL,
    interval_min INT        NOT NULL,
    candle_ts_utc VARCHAR(30) NOT NULL,
    open_price  DOUBLE      NOT NULL,
    high_price  DOUBLE      NOT NULL,
    low_price   DOUBLE      NOT NULL,
    close_price DOUBLE      NOT NULL,
    volume      DOUBLE      NOT NULL DEFAULT 0,
    CONSTRAINT uq_candle_cache UNIQUE (market, interval_min, candle_ts_utc)
);

CREATE INDEX IF NOT EXISTS idx_candle_cache_lookup
    ON candle_cache (market, interval_min, candle_ts_utc);
