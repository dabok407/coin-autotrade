# Upbit AutoTrade Bot

> 업비트(Upbit) 암호화폐 자동매매 봇 | Java 8 · Spring Boot 2.7 · Thymeleaf

Paper(모의) 및 Live(실거래) 모드를 지원하며, 14개 캔들스틱 패턴 기반 전략과 다중 그룹 설정, 실시간 대시보드, 백테스트 시스템을 갖춘 자동 트레이딩 플랫폼입니다.

> **주의**: 교육/연구 목적 프로젝트입니다. 실거래 시 반드시 소액으로 테스트하고, 손실에 대한 책임은 사용자에게 있습니다.

---

## 주요 기능

### 이중 모드 트레이딩
| 모드 | 설명 |
|------|------|
| **PAPER** | 가상 자본으로 시뮬레이션 (슬리피지 0.1%, 수수료 0.05%) |
| **LIVE** | 업비트 실제 주문 (API 키 필요, 멱등키 기반 중복 방지) |

### 다중 전략 그룹
- **그룹 독립 운영**: 코인별로 서로 다른 전략/리스크 설정
- **프리셋 시스템**: 시장 상황별(상승/횡보/하락) × 운용 스타일(공격/안정) 6종 프리셋 원클릭 적용
- **프리셋 기억**: 적용한 프리셋이 DB에 저장되어 페이지 재방문 시 자동 복원

### 14개 트레이딩 전략

| 전략 | 유형 | 설명 |
|------|------|------|
| EMA RSI Trend | 매수 | EMA 트렌드 + RSI 과매도 반등 |
| Scalp Momentum | 매수 | FVG 기반 단기 모멘텀 스캘핑 |
| Consecutive Down Rebound | 매수 | N연속 하락 후 반등 캔들 포착 |
| Bullish Engulfing Confirm | 매수 | 상승 장악형 + 확인봉 |
| Momentum FVG Pullback | 매수 | Fair Value Gap 풀백 진입 |
| Bullish Pinbar Orderblock | 매수 | 핀바 + 오더블록 지지 |
| Inside Bar Breakout | 매수 | 인사이드바 상방 돌파 |
| Three Methods Bullish | 매수 | 상승 삼법 패턴 |
| Morning Star | 매수 | 모닝스타 반전 |
| Three White Soldiers | 매수 | 적삼병 연속 양봉 |
| Regime Filtered Pullback | 매수 | ADX 트렌드 필터 + EMA + ATR |
| Adaptive Trend Momentum | 매수 | 5캔들 트렌드 + 샹들리에 엑시트 |
| Bearish Engulfing | 매도 | 하락 장악형 패턴 감지 |
| Evening Star Sell | 매도 | 이브닝스타 반전 |
| Three Black Crows Sell | 매도 | 흑삼병 연속 음봉 |
| Three Methods Bearish | 매도 | 하락 삼법 패턴 |

### 리스크 관리
- 익절(TP) / 손절(SL) 비율 설정
- 최대 추가매수(Add Buy) 횟수 제한
- 타임스탑: 장기 보유 포지션 자동 청산
- 전략 락: 진입 전략과 동일한 전략만 청산 허용
- 최소 신뢰도 점수 필터링 (0~10)
- Decision Guard: 주문 차단 사유 실시간 로깅

### 백테스트
- 과거 데이터 기반 전략 성과 검증
- 날짜 범위 지정 (From/To)
- 그룹별 독립 설정으로 멀티 전략 동시 테스트
- 결과: ROI, 승률, 거래 횟수, TP/SL/패턴 매도 비율

---

## 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Java 8 (1.8) |
| Framework | Spring Boot 2.7.18 |
| Build | Maven |
| ORM | Spring Data JPA / Hibernate 5.6 |
| DB | H2 (기본, 파일 기반) / MySQL 8 (프로덕션) |
| Migration | Flyway 8.5 (22개 버전) |
| Security | Spring Security + BCrypt + RSA-2048 (비대칭 로그인) |
| Template | Thymeleaf |
| Frontend | Vanilla JavaScript + CSS (프레임워크 없음) |
| Chart | Lightweight Charts (TradingView) |
| Auth | JJWT 0.11.5 (Upbit JWT) + AES-GCM (API 키 암호화) |
| HTTP | RestTemplate + 지수 백오프 재시도 |

---

## 프로젝트 구조

```
src/main/java/com/example/upbit/
├── UpbitAutotradeApplication.java     # Spring Boot 진입점
├── api/                               # DTO (Backtest Request/Response)
├── backtest/                          # 백테스트 엔진
├── bot/                               # 트레이딩 봇 코어
│   ├── TradingBotService.java         #   스케줄링, 틱 처리, 캐치업
│   └── BotStatus.java                 #   봇 상태 관리
├── config/                            # 설정 (BotProperties, TradeProperties)
├── dashboard/                         # 대시보드 서비스 (자산 요약)
├── db/                                # JPA 엔티티 + Repository
│   ├── BotConfigEntity.java           #   봇 설정 (1행)
│   ├── StrategyGroupEntity.java       #   전략 그룹 (다중)
│   ├── PositionEntity.java            #   보유 포지션
│   ├── OrderLogEntity.java            #   주문 이력 (멱등키)
│   ├── TradeLogEntity.java            #   체결 이력 (PnL)
│   ├── StrategyStateEntity.java       #   전략별 상태 추적
│   ├── AppUserEntity.java             #   사용자 인증
│   └── ApiKeyStoreEntity.java         #   암호화된 API 키
├── market/                            # 캔들/마켓 서비스
│   ├── CandleService.java             #   Upbit API 캔들 조회
│   └── UpbitMarketCatalogService.java #   마켓 목록 캐시
├── security/                          # RSA 로그인, API 키 암호화
├── strategy/                          # 14개 전략 구현
│   ├── StrategyType.java              #   전략 enum
│   ├── StrategyFactory.java           #   전략 팩토리
│   ├── Indicators.java                #   기술적 지표 (EMA/RSI/ATR/ADX)
│   └── impl/                          #   개별 전략 클래스
├── trade/                             # 주문 실행 (LiveOrderService)
├── upbit/                             # Upbit API 클라이언트
│   ├── UpbitPrivateClient.java        #   Private API (주문/잔고)
│   └── UpbitAuth.java                 #   JWT 인증 (HS512 + SHA512)
└── web/                               # 컨트롤러
    ├── DashboardController.java       #   대시보드 페이지
    ├── BotApiController.java          #   봇 설정/제어 API
    ├── BacktestApiController.java     #   백테스트 API
    ├── AuthController.java            #   RSA 로그인 API
    └── ChartApiController.java        #   차트 데이터 API

src/main/resources/
├── application.yml                    # 메인 설정
├── application-h2.yml                 # H2 프로필
├── application-mysql.yml              # MySQL 프로필
├── db/migration/                      # Flyway 마이그레이션 (V1~V22)
├── templates/                         # Thymeleaf HTML
│   ├── dashboard.html                 #   대시보드 (메인 화면)
│   ├── settings.html                  #   설정 페이지
│   ├── backtest.html                  #   백테스트 페이지
│   └── login.html                     #   로그인 페이지
└── static/
    ├── css/autotrade.css              # 전체 스타일 (다크/라이트 테마)
    └── js/
        ├── autotrade-common.js        #   공통 유틸 + 프리셋
        ├── dashboard.js               #   대시보드 로직
        ├── settings.js                #   설정 관리
        └── backtest.js                #   백테스트 UI
```

---

## 화면 소개

### 1. 로그인

- RSA-2048 공개키 암호화로 비밀번호 전송
- 사용자당 단일 세션 정책
- 다크 테마 기반 글래스모피즘 UI

### 2. 대시보드 (메인)

| 영역 | 내용 |
|------|------|
| **봇 상태** | START/STOP 토글, 현재 전략/마켓 표시 |
| **잔고** | 가용 KRW, 잠금 KRW, 최종 갱신 시간 |
| **총 손익** | 실현 P&L (KRW), 미실현 손익 |
| **ROI / 승률** | 수익률(%), 승리 비율(%) |
| **보유 포지션** | 마켓, 수량, 평균가, 현재가, 미실현PnL, 추가매수 횟수 |
| **Decision Guard** | 주문 차단/경고 사유 실시간 로그 |
| **거래 이력** | 시간, 마켓, 액션, 유형, 신뢰도, 가격, 수량, PnL (필터/정렬/페이지네이션) |

### 3. 설정

| 영역 | 내용 |
|------|------|
| **글로벌** | 운영 모드(PAPER/LIVE), 자본금, API 키 테스트 |
| **전략 그룹** | 그룹 추가/삭제, 마켓 할당, 전략 선택 |
| **프리셋 바** | 상승장/횡보장/하락장 + 공격형/안정형 원클릭 적용 |
| **리스크** | TP%, SL%, 추가매수, 최소 신뢰도, 타임스탑, 전략 락 |
| **전략 상세** | 전략별 개별 인터벌, EMA 필터 설정 |

### 4. 백테스트

| 영역 | 내용 |
|------|------|
| **조건 설정** | 자본금, 기간(프리셋 또는 직접 입력), 전략 그룹 구성 |
| **결과 KPI** | 총 수익(KRW), ROI(%), 거래 수, 승률(%) |
| **거래 기록** | 개별 매매 내역 (필터/정렬/페이지네이션) |
| **차트 뷰** | TradingView 기반 캔들스틱 차트 |

### 디자인

- **다크/라이트 테마** 토글 지원 (설정 유지)
- 반응형 레이아웃 (모바일/태블릿/데스크톱)
- Vanilla CSS (프레임워크 의존 없음)

---

## 아키텍처

### 봇 엔진 동작 흐름

```
┌─────────────────────────────────────────────────────┐
│                   TradingBotService                  │
│                                                      │
│  1. 경계 인식 스케줄링 (캔들 종가 시점 동기화)        │
│  2. Upbit API → 최신 캔들 fetch                      │
│  3. 캐치업 로직 (누락 틱 보정)                       │
│  4. 전략 그룹별 독립 평가                            │
│     ├─ 매도 전략 → 우선 실행 (SELL)                  │
│     ├─ 추가매수 전략 → 포지션 존재 시 (ADD_BUY)      │
│     └─ 매수 전략 → 신규 진입 (BUY)                   │
│  5. Stale Entry TTL: 60초 초과 지연 시 매수 차단     │
│  6. Decision Guard: 조건 미충족 시 사유 로깅         │
│  7. 주문 실행 (PAPER: 시뮬레이션 / LIVE: Upbit API)  │
│  8. 포지션/손익 업데이트                             │
└─────────────────────────────────────────────────────┘
```

### Upbit API 연동

| API | 용도 | 인증 |
|-----|------|------|
| `GET /v1/candles/minutes/{unit}` | 캔들 조회 (1~240분) | 불필요 |
| `GET /v1/market/all` | 마켓 목록 | 불필요 |
| `POST /v1/orders` | 주문 생성 | JWT (HS512) |
| `POST /v1/orders/test` | 주문 테스트 | JWT |
| `GET /v1/order` | 주문 상태 조회 | JWT |
| `GET /v1/accounts` | 계좌 잔고 | JWT |

- JWT: access_key + SHA512 query hash + nonce
- 재시도: 지수 백오프 (250ms → 4s, 최대 5회)
- Rate Limit: 429 응답 시 자동 대기

---

## 빠른 시작

### 요구사항
- Java 8 (JDK 1.8+)
- Maven 3.6+

### 빌드 & 실행

```bash
# 빌드
mvn clean package -DskipTests

# 실행 (H2 기본 - 설치 필요 없음)
java -jar target/upbit-autotrade-*.jar

# 초기 관리자 계정 생성 (최초 실행 시)
java -jar target/upbit-autotrade-*.jar --add-user=admin:yourpassword

# 접속
# http://localhost:8080
```

### MySQL 사용 시

```bash
# 1. MySQL DB 준비
mysql -u root -p -e "
  CREATE DATABASE upbitbot CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE USER 'upbitbot'@'localhost' IDENTIFIED BY 'upbitbot';
  GRANT ALL PRIVILEGES ON upbitbot.* TO 'upbitbot'@'localhost';
  FLUSH PRIVILEGES;
"

# 2. MySQL 프로필로 실행
java -jar target/upbit-autotrade-*.jar --spring.profiles.active=mysql
```

### LIVE 모드 (실거래)

```bash
# application-local.yml 생성 (gitignore에 포함됨)
cat > src/main/resources/application-local.yml << 'EOF'
upbit:
  accessKey: YOUR_UPBIT_ACCESS_KEY
  secretKey: YOUR_UPBIT_SECRET_KEY
EOF

# 또는 환경변수로 주입
export UPBIT_ACCESS_KEY=your_access_key
export UPBIT_SECRET_KEY=your_secret_key
```

> LIVE 모드는 Settings 페이지에서 Mode를 LIVE로 변경하고 API Key 연결 테스트 후 사용하세요.

---

## 설정 (application.yml)

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `server.port` | 8080 | 서버 포트 |
| `bot.pollSeconds` | 5 | 봇 폴링 간격(초) |
| `bot.defaultMarkets` | KRW-SOL, KRW-ADA | 초기 마켓 |
| `bot.boundarySchedulerEnabled` | true | 캔들 경계 스케줄링 |
| `bot.staleEntryTtlSeconds` | 60 | Stale Entry 차단 기준(초) |
| `strategy.consecutiveDown` | 5 | 연속 하락 감지 봉 수 |
| `strategy.takeProfitRate` | 0.01 | 기본 익절 비율 |
| `trade.globalBaseOrderKrw` | 10000 | 기본 주문 금액(KRW) |
| `trade.maxAddBuys` | 5 | 최대 추가매수 횟수 |

---

## API 엔드포인트

### 봇 제어
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/bot/status` | 봇 상태 조회 |
| POST | `/api/bot/start` | 봇 시작 |
| POST | `/api/bot/stop` | 봇 중지 |
| POST | `/api/bot/settings` | 글로벌 설정 저장 |
| GET | `/api/bot/groups` | 전략 그룹 조회 |
| POST | `/api/bot/groups` | 전략 그룹 저장 |

### 대시보드
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/dashboard/summary` | KPI 요약 |
| GET | `/api/dashboard/positions` | 보유 포지션 |
| GET | `/api/dashboard/trades` | 거래 이력 |
| GET | `/api/dashboard/guard-logs` | Decision Guard 로그 |

### 백테스트
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/backtest/run` | 백테스트 실행 |

### 인증
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/auth/pubkey` | RSA 공개키 조회 |
| POST | `/api/auth/login` | 로그인 (RSA 암호화) |
| GET | `/api/auth/check` | 세션 확인 |

---

## 새 전략 추가하기

```java
// 1. StrategyType enum에 추가
MY_NEW_STRATEGY("내 새 전략", "설명")

// 2. TradingStrategy 인터페이스 구현
public class MyNewStrategy implements TradingStrategy {
    @Override
    public StrategyType type() { return StrategyType.MY_NEW_STRATEGY; }

    @Override
    public TradeSignal evaluate(List<UpbitCandle> candles, ...) {
        // 매수/매도 로직 구현
    }
}

// 3. StrategyFactory에 등록
case MY_NEW_STRATEGY: return new MyNewStrategy();
```

---

## 라이선스

이 프로젝트는 교육 및 개인 연구 목적으로 제작되었습니다.
암호화폐 자동매매는 높은 위험을 수반하며, 투자 손실에 대한 책임은 전적으로 사용자에게 있습니다.
