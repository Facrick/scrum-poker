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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

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
                    // GitHub (plain OAuth2) — наш сервис вызывается напрямую
                    .userService(oauthUserService)
                    // Google (OIDC, openid scope) — нужна отдельная обёртка,
                    // иначе DefaultOidcUserService вызывается в обход нашей логики
                    // и _userId не попадает в principal → jwtService.issue(null) → 500
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
     * OIDC-обёртка: расширяем {@link OidcUserService}, вызываем {@code super.loadUser()}
     * для получения стандартного OidcUser, затем обогащаем его через нашу логику upsert.
     */
    private OidcUserService buildOidcUserService() {
        return new OidcUserService() {
            @Override
            public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
                OidcUser oidcUser = super.loadUser(request);
                // Вызываем processUser для upsert пользователя и получения _userId
                OAuth2User enriched = oauthUserService.processUser("google", oidcUser);
                String userId = enriched.getAttribute("_userId");
                // Возвращаем OidcUser с _userId в атрибутах
                return new EnrichedOidcUser(oidcUser, userId);
            }
        };
    }

    /** DefaultOidcUser с дополнительным атрибутом _userId в getAttributes(). */
    private static final class EnrichedOidcUser extends DefaultOidcUser {
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
