[Thymeleaf UI 리디자인 v2]

추가 반영:
- Select 가독성 개선 (드롭다운 option 배경/글자)
- Strategy: 멀티셀렉트(검색/전체선택/해제)로 변경
- Apply/Backtest 요청 바디를 strategies: string[] 로 변경

복사 경로:
- src/main/resources/templates/dashboard.html
- src/main/resources/templates/backtest.html
- src/main/resources/static/css/autotrade.css
- src/main/resources/static/js/autotrade-common.js
- src/main/resources/static/js/dashboard.js
- src/main/resources/static/js/backtest.js

백엔드 변경(필수):
- /api/bot/settings 가 { mode, strategies[], interval, capital } 을 받아 저장/적용해야 함
- /api/backtest/run 가 { strategies[], period, interval, market, capital } 을 받아 실행해야 함
