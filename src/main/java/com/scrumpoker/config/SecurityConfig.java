package com.scrumpoker.config;

import com.scrumpoker.account.OAuthUserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final OAuthUserService oauthUserService;

    public SecurityConfig(OAuthUserService oauthUserService) {
        this.oauthUserService = oauthUserService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())

            // Явно требуем сессию и фиксируем стратегию — changeSessionId() должен
            // генерировать Set-Cookie ДО того, как successHandler пишет тело ответа.
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation().changeSessionId()
            )

            .authorizeHttpRequests(auth -> auth
                // ЛК модератора требует авторизации
                .requestMatchers("/account", "/account/**", "/api/me", "/api/me/**").authenticated()
                // Всё остальное публично — анонимные участники не трогаются
                .anyRequest().permitAll()
            )

            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .userInfoEndpoint(ui -> ui.userService(oauthUserService))
                // Отдаём 200 + JS-редирект вместо 302.
                // Railway (и другие reverse-proxy) иногда обрезают Set-Cookie в 302-ответах,
                // из-за чего JSESSIONID не сохраняется в браузере и сессия теряется.
                // 200-ответ с <meta refresh> гарантирует, что cookie установлен до перехода.
                .successHandler((req, res, auth) -> {
                    // Сохраняем SecurityContext в сессию явно.
                    HttpSession session = req.getSession(true);
                    SecurityContext ctx = SecurityContextHolder.getContext();
                    ctx.setAuthentication(auth);
                    session.setAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);

                    // Railway обрезает Set-Cookie заголовки из ЛЮБЫХ ответов (подтверждено).
                    // Обходное решение: кладём sessionId прямо в тело HTML,
                    // JS устанавливает JSESSIONID через document.cookie — тело Railway не трогает.
                    String sid = session.getId();
                    res.setContentType("text/html;charset=UTF-8");
                    res.setHeader("Cache-Control", "no-store");
                    res.getWriter().write("""
                        <!doctype html><html><head><title>Входим...</title>
                        <style>
                          body{background:#0d1117;color:#e6edf3;font-family:sans-serif;
                               display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
                          .box{text-align:center;padding:2rem}
                          #status{font-size:1.1rem;margin-bottom:1.5rem}
                          button{background:#2f81f7;color:#fff;border:none;border-radius:8px;
                                 padding:.7rem 1.6rem;font-size:1rem;cursor:pointer}
                          button:disabled{opacity:.4;cursor:not-allowed}
                          .err{color:#f85149}
                        </style>
                        </head><body><div class="box">
                        <p id="status">⏳ Устанавливаем сессию…</p>
                        <button id="btn" disabled>Перейти в кабинет</button>
                        <p id="debug" style="font-size:.75rem;color:#8b949e;margin-top:1rem"></p>
                        </div><script>
                        document.cookie = 'JSESSIONID=%s; path=/; SameSite=Lax';
                        fetch('/api/me', {credentials:'include'}).then(async r => {
                          const txt = await r.text();
                          if (r.ok) {
                            document.getElementById('status').textContent = '✓ Авторизован!';
                            const btn = document.getElementById('btn');
                            btn.disabled = false;
                            btn.onclick = () => location.replace('/account');
                            // Авто-переход через 2 сек
                            setTimeout(() => location.replace('/account'), 2000);
                          } else {
                            document.getElementById('status').innerHTML =
                              '<span class=\\"err\\">✗ Ошибка сессии (' + r.status + ')</span>';
                            document.getElementById('debug').textContent =
                              'sid=%s | resp=' + txt.substring(0, 80);
                          }
                        }).catch(e => {
                          document.getElementById('status').innerHTML =
                            '<span class=\\"err\\">Ошибка сети: ' + e + '</span>';
                        });
                        </script></body></html>
                        """.formatted(sid, sid));
                })
                .failureUrl("/login?error=true")
            )

            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/api/me/logout", "POST"))
                .logoutSuccessUrl("/")
                .deleteCookies("JSESSIONID")
                .invalidateHttpSession(true)
            )

            // Единый entry point: API → 401 JSON, остальное → редирект на /login.
            // Регистрируется последним, что гарантирует приоритет над oauth2Login.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) -> {
                    if (req.getRequestURI().startsWith("/api/")) {
                        res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
                    } else {
                        res.sendRedirect("/login");
                    }
                })
            );

        return http.build();
    }
}
