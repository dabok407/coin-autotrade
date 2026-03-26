-- V45: 저가 코인 필터 추가 (오프닝 + 올데이 스캐너)
-- Top N 선별 전에 최소 가격 미만 코인을 제외하여 호가 단위 리스크 방지
ALTER TABLE opening_scanner_config ADD COLUMN min_price_krw INT NOT NULL DEFAULT 20;
ALTER TABLE allday_scanner_config ADD COLUMN min_price_krw INT NOT NULL DEFAULT 20;
