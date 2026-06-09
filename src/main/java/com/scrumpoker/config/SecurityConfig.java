package com.scrumpoker.config;

import com.scrumpoker.account.OAuthUserService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final OAuthUserService oauthUserService;
    private final JwtService jwtService;
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(OAuthUserService oauthUserService,
                          JwtService jwtService,
                          JwtAuthFilter jwtAuthFilter) {
        this.oauthUserService = oauthUserService;
        this.jwtService = jwtService;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())

            // Аутентификация stateless — её держит ТОЛЬКО JWT в заголовке.
            // SecurityContext храним в атрибуте запроса, а НЕ в сессии: иначе
            // oauth2Login по умолчанию сохранял бы контекст в HttpSession, и вход
            // держался бы на JSESSIONID — тогда очистка токена при выходе не
            // разлогинивала бы пользователя (баг: бадж висел после "Выйти").
            // Сессия (IF_REQUIRED) нужна лишь OAuth2 для хранения authorization_request
            // на время редиректа на провайдера; держать вход она больше не будет.
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )
            .securityContext(sc -> sc
                .securityContextRepository(new RequestAttributeSecurityContextRepository())
            )

            .authorizeHttpRequests(auth -> auth
                // ЛК модератора требует авторизации (JWT)
                .requestMatchers("/api/me", "/api/me/**").authenticated()
                // /account — статическая страница SPA, она сама проверит токен и
                // редиректнёт на /login, если его нет; поэтому оставляем публичной.
                // Всё остальное публично — анонимные участники не трогаются
                .anyRequest().permitAll()
            )

            // JWT-фильтр до стандартного логина: восстанавливает аутентификацию из Bearer-токена.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .userInfoEndpoint(ui -> ui.userService(oauthUserService))
                // После успешной авторизации выпускаем JWT и передаём его SPA через
                // фрагмент URL (#token=...). Фрагмент не уходит на сервер и не попадает
                // в логи/Referer; статическая страница перекладывает его в localStorage.
                .successHandler((req, res, auth) -> {
                    OAuth2User principal = (OAuth2User) auth.getPrincipal();
                    String userId = principal.getAttribute("_userId");
                    String token = jwtService.issue(userId);
                    res.sendRedirect("/auth-callback.html#token=" + token);
                })
                .failureUrl("/login?error=true")
            )

            // Единый entry point: API → 401 JSON, остальное → редирект на /login.
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
