-- 오프닝 스캐너 마켓 제외 목록 (콤마 구분 CSV, 예: KRW-BTC,KRW-XRP,KRW-DOGE)
ALTER TABLE opening_scanner_config ADD COLUMN exclude_markets VARCHAR(1000) DEFAULT '';
