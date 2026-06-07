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
                    // Явно сохраняем SecurityContext в сессию — страховка от Spring Security 6,
                    // где requireExplicitSave=true и контекст может не попасть в сессию автоматически.
                    HttpSession session = req.getSession(true);
                    SecurityContext ctx = SecurityContextHolder.getContext();
                    ctx.setAuthentication(auth);
                    session.setAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);

                    // Отдаём 200 + диагностическую страницу.
                    // JS делает fetch('/api/me') — если 200, значит сессия живая → редирект.
                    // Если 401 — показываем отладочную информацию вместо тихого loop-а.
                    res.setContentType("text/html;charset=UTF-8");
                    res.setHeader("Cache-Control", "no-store");
                    res.getWriter().write("""
                        <!doctype html><html><head><title>Входим...</title>
                        <style>body{font-family:sans-serif;padding:2rem;background:#0d1117;color:#e6edf3}
                        .ok{color:#3fb950}.err{color:#f85149}pre{background:#161b22;padding:1rem;border-radius:8px;font-size:.85rem}</style>
                        </head><body>
                        <p id="msg">Проверяем сессию…</p>
                        <script>
                        fetch('/api/me',{credentials:'include'}).then(async r=>{
                          const txt = await r.text();
                          if(r.ok){
                            document.getElementById('msg').textContent='✓ Авторизован, переходим…';
                            location.replace('/account');
                          } else {
                            document.getElementById('msg').innerHTML =
                              '<span class=\\"err\\">✗ Сессия не установлена ('+r.status+')</span><br>' +
                              '<pre>'+txt+'</pre>' +
                              '<p>Скопируйте эту страницу и отправьте разработчику.</p>';
                          }
                        }).catch(e=>{
                          document.getElementById('msg').innerHTML='<span class=\\"err\\">Ошибка сети: '+e+'</span>';
                        });
                        </script>
                        </body></html>
                        """);
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
