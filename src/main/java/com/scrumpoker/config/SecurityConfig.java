package com.scrumpoker.config;

import com.scrumpoker.account.OAuthUserService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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

            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )
            .securityContext(sc -> sc
                .securityContextRepository(new RequestAttributeSecurityContextRepository())
            )

            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/me", "/api/me/**").authenticated()
                .anyRequest().permitAll()
            )

            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .userInfoEndpoint(ui -> ui
                    // GitHub (plain OAuth2)
                    .userService(oauthUserService)
                    // Google (OIDC — openid scope) — нужен отдельный сервис
                    .oidcUserService(buildOidcUserService())
                )
                .successHandler((req, res, auth) -> {
                    OAuth2User principal = (OAuth2User) auth.getPrincipal();
                    String userId = principal.getAttribute("_userId");
                    String token = jwtService.issue(userId);
                    res.sendRedirect("/auth-callback.html#token=" + token);
                })
                .failureUrl("/login?error=true")
            )

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

    /**
     * OIDC-обёртка для Google: делегирует загрузку пользователя стандартному
     * {@link DefaultOidcUserService}, затем вызывает нашу логику upsert'а и
     * возвращает {@link OidcUser}, у которого {@code getAttribute("_userId")}
     * работает корректно.
     *
     * Без этой обёртки Spring Security использует {@link DefaultOidcUserService}
     * напрямую, минуя наш {@link OAuthUserService}, и {@code _userId} не ставится
     * → {@code jwtService.issue(null)} бросает исключение → 500.
     */
    private OidcUserService buildOidcUserService() {
        DefaultOidcUserService delegate = new DefaultOidcUserService();
        return new OidcUserService() {
            @Override
            public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
                OidcUser oidcUser = delegate.loadUser(request);
                // Получаем _userId через ту же логику, что и для GitHub
                OAuth2User enriched = oauthUserService.processUser("google", oidcUser);
                String userId = enriched.getAttribute("_userId");
                // Оборачиваем в OidcUser, добавляя _userId в атрибуты
                return new EnrichedOidcUser(oidcUser, userId);
            }
        };
    }

    /**
     * {@link OidcUser}, который делегирует всё оригинальному пользователю,
     * но добавляет {@code _userId} в {@link #getAttributes()}.
     */
    private static class EnrichedOidcUser extends DefaultOidcUser {
        private final Map<String, Object> enrichedAttrs;

        EnrichedOidcUser(OidcUser original, String userId) {
            super(original.getAuthorities(), original.getIdToken(),
                  original.getUserInfo(), "sub");
            this.enrichedAttrs = new HashMap<>(original.getAttributes());
            this.enrichedAttrs.put("_userId", userId);
        }

        @Override
        public Map<String, Object> getAttributes() {
            return enrichedAttrs;
        }
    }
}
