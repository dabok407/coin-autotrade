package com.example.upbit.web;

import com.example.upbit.security.RsaKeyHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 15 * 60 * 1000L; // 15분
    private static final long SSO_TOKEN_TTL_MS = 30_000L; // SSO 토큰 유효 시간 30초

    /** IP별 로그인 실패 추적: {ip -> [failCount, lastFailTimeMs]} */
    private final ConcurrentHashMap<String, long[]> loginAttempts = new ConcurrentHashMap<String, long[]>();

    private final RsaKeyHolder rsaKeyHolder;
    private final AuthenticationManager authManager;
    private final SessionRegistry sessionRegistry;
    private final UserDetailsService userDetailsService;

    @Value("${sso.secret:}")
    private String ssoSecret;

    @Value("${sso.partnerUrl:}")
    private String ssoPartnerUrl;

    @Value("${sso.partnerLabel:}")
    private String ssoPartnerLabel;

    public AuthController(RsaKeyHolder rsaKeyHolder, AuthenticationManager authManager,
                          SessionRegistry sessionRegistry, UserDetailsService userDetailsService) {
        this.rsaKeyHolder = rsaKeyHolder;
        this.authManager = authManager;
        this.sessionRegistry = sessionRegistry;
        this.userDetailsService = userDetailsService;
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isLockedOut(String ip) {
        long[] info = loginAttempts.get(ip);
        if (info == null) return false;
        if (info[0] < MAX_LOGIN_ATTEMPTS) return false;
        // 잠금 기간이 지났으면 해제
        if (System.currentTimeMillis() - info[1] > LOCKOUT_DURATION_MS) {
            loginAttempts.remove(ip);
            return false;
        }
        return true;
    }

    private void recordFailure(String ip) {
        long now = System.currentTimeMillis();
        loginAttempts.compute(ip, new java.util.function.BiFunction<String, long[], long[]>() {
            @Override
            public long[] apply(String key, long[] existing) {
                if (existing == null) return new long[]{1, now};
                // 이전 잠금이 만료되었으면 리셋
                if (System.currentTimeMillis() - existing[1] > LOCKOUT_DURATION_MS) {
                    return new long[]{1, now};
                }
                return new long[]{existing[0] + 1, now};
            }
        });
    }

    private void clearFailures(String ip) {
        loginAttempts.remove(ip);
    }

    /** 로그인 페이지 — 이미 인증된 상태이면 대시보드로 리다이렉트 */
    @GetMapping("/login")
    public String loginPage(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "redirect:/dashboard";
        }
        return "login";
    }

    /** RSA 공개키 반환 (클라이언트가 비밀번호 암호화에 사용) */
    @GetMapping("/api/auth/pubkey")
    @ResponseBody
    public Map<String, String> publicKey() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("publicKey", rsaKeyHolder.getPublicKeyBase64());
        return m;
    }

    /**
     * RSA 암호화된 로그인 요청 처리.
     *
     * 흐름:
     * 1. 클라이언트가 서버 공개키로 비밀번호를 RSA-OAEP 암호화하여 전송
     * 2. 서버가 개인키로 복호화
     * 3. Spring Security AuthenticationManager로 인증
     * 4. 성공 시 세션 생성 → 기존 세션은 자동 만료 (단일 세션)
     */
    @PostMapping("/api/auth/login")
    @ResponseBody
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        // ── 로그인 시도 횟수 제한 (IP 기반) ──
        String clientIp = getClientIp(request);
        if (isLockedOut(clientIp)) {
            long[] info = loginAttempts.get(clientIp);
            long remainSec = info != null ? (LOCKOUT_DURATION_MS - (System.currentTimeMillis() - info[1])) / 1000 : 0;
            log.warn("로그인 잠금 상태: ip={}, 남은시간={}초", clientIp, remainSec);
            result.put("success", false);
            result.put("message", "로그인 시도 횟수를 초과했습니다. " + (remainSec / 60 + 1) + "분 후에 다시 시도해주세요.");
            return result;
        }

        String username = body.get("username");
        String encryptedPassword = body.get("encryptedPassword");

        if (username == null || username.trim().isEmpty()
                || encryptedPassword == null || encryptedPassword.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "아이디와 비밀번호를 입력해주세요.");
            return result;
        }

        // RSA 복호화
        String plainPassword = rsaKeyHolder.decrypt(encryptedPassword.trim());
        if (plainPassword == null) {
            log.warn("RSA 복호화 실패 — username={}", username);
            result.put("success", false);
            result.put("message", "보안 인증에 실패했습니다. 페이지를 새로고침하세요.");
            return result;
        }

        try {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(username.trim(), plainPassword);
            Authentication auth = authManager.authenticate(token);

            // ── 단일 세션 강제: 해당 사용자의 기존 세션만 만료 ──
            // Spring Security의 maximumSessions(1)은 formLogin에서만 자동 작동하므로,
            // 커스텀 로그인에서는 SessionRegistry를 통해 수동 제어 필요
            List<SessionInformation> existingSessions =
                    sessionRegistry.getAllSessions(auth.getPrincipal(), false);
            for (SessionInformation si : existingSessions) {
                si.expireNow(); // 해당 사용자의 기존 세션만 만료
                log.info("기존 세션 만료 처리: sessionId={}", si.getSessionId());
            }

            // 기존 세션 무효화 후 새 세션 생성 (세션 고정 공격 방지)
            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) {
                sessionRegistry.removeSessionInformation(oldSession.getId());
                oldSession.invalidate();
            }
            HttpSession newSession = request.getSession(true);
            newSession.setMaxInactiveInterval(3600 * 4); // 4시간 세션 유지

            SecurityContextHolder.getContext().setAuthentication(auth);
            // Spring Security가 세션에 SecurityContext를 저장하도록
            newSession.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

            // 새 세션을 SessionRegistry에 등록
            sessionRegistry.registerNewSession(newSession.getId(), auth.getPrincipal());

            clearFailures(clientIp); // 성공 시 실패 기록 초기화

            log.info("로그인 성공: {} (새 세션: {})", username, newSession.getId());
            result.put("success", true);
            result.put("redirect", request.getContextPath() + "/dashboard");
        } catch (BadCredentialsException e) {
            recordFailure(clientIp);
            long[] info = loginAttempts.get(clientIp);
            int remaining = MAX_LOGIN_ATTEMPTS - (info != null ? (int)info[0] : 0);
            log.warn("로그인 실패 (잘못된 자격증명): {} ip={} 남은횟수={}", username, clientIp, remaining);
            result.put("success", false);
            result.put("message", remaining > 0
                    ? "아이디 또는 비밀번호가 올바르지 않습니다. (남은 시도: " + remaining + "회)"
                    : "로그인 시도 횟수를 초과했습니다. 15분 후에 다시 시도해주세요.");
        } catch (DisabledException e) {
            result.put("success", false);
            result.put("message", "비활성화된 계정입니다.");
        } catch (LockedException e) {
            result.put("success", false);
            result.put("message", "잠긴 계정입니다.");
        } catch (Exception e) {
            log.error("로그인 오류: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "서버 오류가 발생했습니다.");
        }

        return result;
    }

    /** 현재 인증 상태 확인 (프론트엔드용) */
    @GetMapping("/api/auth/check")
    @ResponseBody
    public Map<String, Object> checkAuth() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
        m.put("authenticated", authenticated);
        if (authenticated) {
            m.put("username", auth.getName());
        }
        return m;
    }

    /** SSO 파트너 정보 조회 (FE에서 버튼 표시용) */
    @GetMapping("/api/auth/sso-info")
    @ResponseBody
    public Map<String, String> ssoInfo() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("partnerUrl", ssoPartnerUrl != null ? ssoPartnerUrl : "");
        m.put("partnerLabel", ssoPartnerLabel != null ? ssoPartnerLabel : "");
        m.put("enabled", String.valueOf(ssoSecret != null && !ssoSecret.isEmpty()));
        return m;
    }

    /** SSO 토큰 생성: 현재 인증된 사용자의 SSO 토큰 반환 */
    @GetMapping("/api/auth/sso-token")
    @ResponseBody
    public Map<String, Object> generateSsoToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("success", false);
            err.put("message", "인증이 필요합니다.");
            return err;
        }
        if (ssoSecret == null || ssoSecret.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("success", false);
            err.put("message", "SSO가 설정되지 않았습니다.");
            return err;
        }
        String username = auth.getName();
        long timestamp = System.currentTimeMillis();
        String token = generateHmac(username, timestamp);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("token", token);
        result.put("username", username);
        result.put("timestamp", timestamp);
        return result;
    }

    /** SSO 로그인: 토큰 검증 후 자동 세션 생성 및 대시보드로 리다이렉트 */
    @GetMapping("/api/auth/sso-login")
    public String ssoLogin(@RequestParam("token") String token,
                           @RequestParam("username") String username,
                           @RequestParam("ts") long timestamp,
                           HttpServletRequest request, HttpServletResponse response) {

        // SSO 미설정
        if (ssoSecret == null || ssoSecret.isEmpty()) {
            log.warn("[SSO] SSO secret이 설정되지 않았습니다.");
            return "redirect:/login";
        }

        // 토큰 유효기간 확인 (30초)
        if (Math.abs(System.currentTimeMillis() - timestamp) > SSO_TOKEN_TTL_MS) {
            log.warn("[SSO] 토큰 만료: username={}, ts={}", username, timestamp);
            return "redirect:/login";
        }

        // HMAC 검증
        String expected = generateHmac(username, timestamp);
        if (expected == null || !expected.equals(token)) {
            log.warn("[SSO] 토큰 검증 실패: username={}", username);
            return "redirect:/login";
        }

        // 사용자 로드 및 세션 생성
        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

            // 기존 세션 만료
            List<SessionInformation> existingSessions =
                    sessionRegistry.getAllSessions(userDetails, false);
            for (SessionInformation si : existingSessions) {
                si.expireNow();
            }

            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) {
                sessionRegistry.removeSessionInformation(oldSession.getId());
                oldSession.invalidate();
            }
            HttpSession newSession = request.getSession(true);
            newSession.setMaxInactiveInterval(3600 * 4);

            SecurityContextHolder.getContext().setAuthentication(auth);
            newSession.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
            sessionRegistry.registerNewSession(newSession.getId(), userDetails);

            log.info("[SSO] SSO 로그인 성공: {}", username);
            return "redirect:/dashboard";
        } catch (Exception e) {
            log.error("[SSO] SSO 로그인 오류: {}", e.getMessage(), e);
            return "redirect:/login";
        }
    }

    /** HMAC-SHA256 토큰 생성 */
    private String generateHmac(String username, long timestamp) {
        try {
            String data = username + ":" + timestamp;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(ssoSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[SSO] HMAC 생성 오류", e);
            return null;
        }
    }
}
