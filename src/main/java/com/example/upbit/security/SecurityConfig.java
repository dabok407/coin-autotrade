package com.example.upbit.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final CustomUserDetailsService userDetailsService;

    public SecurityConfig(CustomUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.userDetailsService(userDetailsService).passwordEncoder(passwordEncoder());
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            // ── URL 권한 설정 ──
            .authorizeRequests()
                // 로그인 페이지, RSA 공개키, 정적 자원만 허용
                .antMatchers("/login", "/api/auth/pubkey", "/api/auth/login", "/api/auth/sso-login", "/api/auth/sso-info").permitAll()
                .antMatchers("/css/**", "/js/**", "/favicon.ico").permitAll()
                // 백테스트 API는 조회성(시뮬레이션)이므로 인증 없이 허용
                .antMatchers("/api/backtest/**", "/api/strategies").permitAll()
                .antMatchers("/api/report/**").permitAll()
                // 나머지 모든 요청은 인증 필요
                .anyRequest().authenticated()
            .and()

            // ── 기본 폼 로그인 비활성화 (커스텀 RSA 로그인 사용) ──
            .formLogin().disable()
            .httpBasic().disable()

            // ── 비인증 요청 처리 ──
            .exceptionHandling()
                // 페이지 요청 → /login 리다이렉트
                // API 요청 → 401 JSON 응답
                .authenticationEntryPoint((req, resp, ex) -> {
                    String accept = req.getHeader("Accept");
                    String xReq = req.getHeader("X-Requested-With");
                    String uri = req.getRequestURI();
                    String ctx = req.getContextPath();
                    boolean isApi = uri.startsWith(ctx + "/api/")
                            || "XMLHttpRequest".equals(xReq)
                            || (accept != null && accept.contains("application/json"));
                    if (isApi) {
                        resp.setStatus(401);
                        resp.setContentType("application/json;charset=UTF-8");
                        resp.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다. 로그인해주세요.\"}");
                    } else {
                        resp.sendRedirect(ctx + "/login");
                    }
                })
            .and()

            // ── 로그아웃 ──
            .logout()
                .logoutUrl("/api/auth/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            .and()

            // ── CSRF: Cookie 기반 (JS에서 X-XSRF-TOKEN 헤더로 전송) ──
            .csrf()
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // 로그인/공개키 엔드포인트만 CSRF 면제
                .ignoringAntMatchers("/api/auth/login", "/api/auth/pubkey", "/api/auth/sso-login", "/api/backtest/**", "/api/report/**")
            .and()

            // ── iframe 클릭재킹 방어: DENY (H2 콘솔 미사용) ──
            .headers()
                .frameOptions().deny()
            .and()

            // ── 세션 관리: 단일 세션 (동시 접속 불가) ──
            .sessionManagement()
                .maximumSessions(1)
                    // false = 새 로그인이 기존 세션을 만료시킴
                    .maxSessionsPreventsLogin(false)
                    .expiredUrl("/login?expired")
                    .sessionRegistry(sessionRegistry());
    }

    /** SessionRegistry 빈 — 동시 세션 추적에 필수 (AuthController에서도 사용) */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** AuthenticationManager를 빈으로 노출 (AuthController에서 사용) */
    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }

    /** 세션 이벤트 퍼블리셔 — 동시 세션 제어에 필수 */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
