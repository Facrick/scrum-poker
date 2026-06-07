package com.scrumpoker.config;

import com.scrumpoker.account.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Достаёт JWT из заголовка {@code Authorization: Bearer <token>}, проверяет его
 * и кладёт в {@link SecurityContextHolder} аутентификацию с принципалом
 * {@link OAuth2User} — той же формы, что выдавал OAuth2-логин, чтобы контроллеры
 * с {@code @AuthenticationPrincipal OAuth2User} продолжали работать без изменений.
 * <p>
 * Если токена нет или он невалиден — фильтр просто ничего не делает; защищённые
 * эндпоинты вернут 401 через стандартный entry point.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            String userId = jwtService.parseUserId(header.substring(7));
            if (userId != null) {
                userRepository.findById(userId).ifPresent(user -> {
                    Map<String, Object> attrs = Map.of("_userId", user.id());
                    OAuth2User principal = new DefaultOAuth2User(
                            Set.of(new SimpleGrantedAuthority("ROLE_MODERATOR")),
                            attrs,
                            "_userId");
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(req));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            }
        }

        chain.doFilter(req, res);
    }
}
