# Expert-recommended backtest runner
$ErrorActionPreference = "Stop"

# Login - Step 1: Get CSRF token
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginPage = Invoke-WebRequest -Uri "http://localhost:8080/login" -UseBasicParsing -WebSession $session
$csrf = ($loginPage.InputFields | Where-Object {$_.name -eq "_csrf"}).value
Write-Host "CSRF: $csrf"

# Login - Step 2: POST login (handle redirect manually)
$body = "_csrf=$csrf&username=admin&password=admin123"
try {
  $resp = Invoke-WebRequest -Uri "http://localhost:8080/login" -Method POST -Body $body -ContentType "application/x-www-form-urlencoded" -WebSession $session -UseBasicParsing -MaximumRedirection 5
  Write-Host "Login response: $($resp.StatusCode)"
} catch {
  # 302 redirect may throw, that's OK
  Write-Host "Login redirect (expected)"
}

# Login - Step 3: Verify by hitting dashboard
$dash = Invoke-WebRequest -Uri "http://localhost:8080/" -UseBasicParsing -WebSession $session
Write-Host "Dashboard: $($dash.StatusCode) (content length: $($dash.Content.Length))"

# Get XSRF after authenticated session
$xsrf = ($session.Cookies.GetCookies("http://localhost:8080") | Where-Object {$_.Name -eq "XSRF-TOKEN"}).Value
Write-Host "XSRF after login: $xsrf"

# Debug: show all cookies
foreach($c in $session.Cookies.GetCookies("http://localhost:8080")) {
  Write-Host "  Cookie: $($c.Name) = $($c.Value.Substring(0, [Math]::Min(20, $c.Value.Length)))..."
}

$headers = @{
  "X-XSRF-TOKEN" = $xsrf
  "Content-Type" = "application/json"
}

# Quick auth test
try {
  $test = Invoke-WebRequest -Uri "http://localhost:8080/api/strategies" -UseBasicParsing -WebSession $session -Headers @{"X-XSRF-TOKEN"=$xsrf} -TimeoutSec 10
  Write-Host "Auth test OK: $($test.StatusCode) (strategies count in response)"
} catch {
  Write-Host "Auth test FAILED: $($_.Exception.Message)"
  Write-Host "Trying without XSRF header..."
  try {
    $test2 = Invoke-WebRequest -Uri "http://localhost:8080/api/strategies" -UseBasicParsing -WebSession $session -TimeoutSec 10
    Write-Host "Without XSRF OK: $($test2.StatusCode)"
  } catch {
    Write-Host "Still failed: $($_.Exception.Message)"
    exit 1
  }
}

function Run-Backtest($name, $jsonBody) {
  try {
    $r = Invoke-WebRequest -Uri "http://localhost:8080/api/backtest" -Method POST -Body $jsonBody -WebSession $session -Headers $headers -UseBasicParsing -TimeoutSec 300
    $d = $r.Content | ConvertFrom-Json
    Write-Host "=== $name ==="
    Write-Host ("  ROI: {0:N2}%  Trades: {1}  WinRate: {2:N1}%  Final: {3:N0}" -f $d.roiPct, $d.totalSells, $d.winRate, $d.finalCapital)
    if ($d.tradeDetails) {
      Write-Host "  Wins: $($d.wins)  TP: $($d.tpSells)  SL: $($d.slSells)  Pattern: $($d.patternSells)"
    }
  } catch {
    Write-Host "=== $name === ERROR: $($_.Exception.Message)"
  }
}

# Test 1: BTC (ATM + ERT + BE + ESS) TP2/SL1.5
$btc = '{"period":"365d","capitalKrw":1000000,"groups":[{"groupName":"BTC","markets":["KRW-BTC"],"strategies":["ADAPTIVE_TREND_MOMENTUM","EMA_RSI_TREND","BEARISH_ENGULFING","EVENING_STAR_SELL"],"candleUnitMin":60,"takeProfitPct":2.0,"stopLossPct":1.5,"maxAddBuys":0,"minConfidence":6.0,"strategyLock":false,"timeStopMinutes":360,"orderSizingMode":"PCT","orderSizingValue":90}]}'
Run-Backtest "KRW-BTC (ATM+ERT, TP2/SL1.5)" $btc

# Test 2: SOL (RFP + ATM + BBSqueeze + BE + ESS) TP5/SL3
$sol = '{"period":"365d","capitalKrw":1000000,"groups":[{"groupName":"SOL","markets":["KRW-SOL"],"strategies":["REGIME_PULLBACK","ADAPTIVE_TREND_MOMENTUM","BOLLINGER_SQUEEZE_BREAKOUT","BEARISH_ENGULFING","EVENING_STAR_SELL"],"candleUnitMin":60,"takeProfitPct":5.0,"stopLossPct":3.0,"maxAddBuys":0,"minConfidence":6.0,"strategyLock":false,"timeStopMinutes":720,"orderSizingMode":"PCT","orderSizingValue":90}]}'
Run-Backtest "KRW-SOL (RFP+ATM+BBSq, TP5/SL3)" $sol

# Test 3: XRP (BEC + BE + ESS + 3BC) TP4/SL2
$xrp = '{"period":"365d","capitalKrw":1000000,"groups":[{"groupName":"XRP","markets":["KRW-XRP"],"strategies":["BULLISH_ENGULFING_CONFIRM","BEARISH_ENGULFING","EVENING_STAR_SELL","THREE_BLACK_CROWS_SELL"],"candleUnitMin":240,"takeProfitPct":4.0,"stopLossPct":2.0,"maxAddBuys":0,"minConfidence":4.0,"strategyLock":false,"timeStopMinutes":0,"orderSizingMode":"PCT","orderSizingValue":90}]}'
Run-Backtest "KRW-XRP (BEC+sell, TP4/SL2)" $xrp

# Test 4: ETH (ATM + ERT + MFP + BE + ESS) TP3/SL2
$eth = '{"period":"365d","capitalKrw":1000000,"groups":[{"groupName":"ETH","markets":["KRW-ETH"],"strategies":["ADAPTIVE_TREND_MOMENTUM","EMA_RSI_TREND","MOMENTUM_FVG_PULLBACK","BEARISH_ENGULFING","EVENING_STAR_SELL"],"candleUnitMin":60,"takeProfitPct":3.0,"stopLossPct":2.0,"maxAddBuys":0,"minConfidence":6.0,"strategyLock":false,"timeStopMinutes":360,"orderSizingMode":"PCT","orderSizingValue":90}]}'
Run-Backtest "KRW-ETH (ATM+ERT+MFP, TP3/SL2)" $eth

# Test 5: ADA (ERT + BBSqueeze + 3MKT + BE + ESS + 3BC) TP3/SL2
$ada = '{"period":"365d","capitalKrw":1000000,"groups":[{"groupName":"ADA","markets":["KRW-ADA"],"strategies":["EMA_RSI_TREND","BOLLINGER_SQUEEZE_BREAKOUT","THREE_MARKET_PATTERN","BEARISH_ENGULFING","EVENING_STAR_SELL","THREE_BLACK_CROWS_SELL"],"candleUnitMin":60,"takeProfitPct":3.0,"stopLossPct":2.0,"maxAddBuys":0,"minConfidence":6.0,"strategyLock":false,"timeStopMinutes":360,"orderSizingMode":"PCT","orderSizingValue":90}]}'
Run-Backtest "KRW-ADA (ERT+BBSq+3MKT, TP3/SL2)" $ada

Write-Host ""
Write-Host "=== ALL 5 COIN TESTS COMPLETE ==="
