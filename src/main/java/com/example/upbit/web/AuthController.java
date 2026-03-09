package com.example.upbit.web;

import com.example.upbit.security.RsaKeyHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final RsaKeyHolder rsaKeyHolder;
    private final AuthenticationManager authManager;
    private final SessionRegistry sessionRegistry;

    public AuthController(RsaKeyHolder rsaKeyHolder, AuthenticationManager authManager, SessionRegistry sessionRegistry) {
        this.rsaKeyHolder = rsaKeyHolder;
        this.authManager = authManager;
        this.sessionRegistry = sessionRegistry;
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

            // ── 단일 세션 강제: 기존 세션 모두 만료 ──
            // Spring Security의 maximumSessions(1)은 formLogin에서만 자동 작동하므로,
            // 커스텀 로그인에서는 SessionRegistry를 통해 수동 제어 필요
            for (Object principal : sessionRegistry.getAllPrincipals()) {
                List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
                for (SessionInformation si : sessions) {
                    si.expireNow(); // 기존 세션 즉시 만료
                    log.info("기존 세션 만료 처리: sessionId={}", si.getSessionId());
                }
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

            log.info("로그인 성공: {} (새 세션: {})", username, newSession.getId());
            result.put("success", true);
            result.put("redirect", "/dashboard");
        } catch (BadCredentialsException e) {
            log.warn("로그인 실패 (잘못된 자격증명): {}", username);
            result.put("success", false);
            result.put("message", "아이디 또는 비밀번호가 올바르지 않습니다.");
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
}
