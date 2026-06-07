package com.scrumpoker.config;

import com.scrumpoker.account.OAuthUserService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
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
            // CSRF не нужен: REST-API с JSON-телом, WebSocket (SockJS)
            .csrf(csrf -> csrf.disable())

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
                    res.setContentType("text/html;charset=UTF-8");
                    res.setHeader("Cache-Control", "no-store");
                    res.getWriter().write("""
                        <!doctype html><html><head>
                        <meta http-equiv="refresh" content="0;url=/account">
                        </head><body>
                        <script>location.replace('/account');</script>
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
