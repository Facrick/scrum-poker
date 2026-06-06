package com.scrumpoker.account;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class OAuthUserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    public OAuthUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User raw = super.loadUser(request);
        String provider = request.getClientRegistration().getRegistrationId();

        String providerId, email, displayName, avatarUrl;
        String nameAttributeKey;

        if ("github".equals(provider)) {
            // GitHub возвращает числовой id и опциональный email (может быть приватным)
            providerId = String.valueOf(raw.getAttribute("id"));
            email = raw.getAttribute("email");
            String name = raw.getAttribute("name");
            displayName = (name != null && !name.isBlank()) ? name : raw.getAttribute("login");
            avatarUrl = raw.getAttribute("avatar_url");
            nameAttributeKey = "id";
        } else {
            // Google
            providerId = raw.getAttribute("sub");
            email = raw.getAttribute("email");
            displayName = raw.getAttribute("name");
            avatarUrl = raw.getAttribute("picture");
            nameAttributeKey = "sub";
        }

        User user = userRepository.upsert(provider, providerId, email, displayName, avatarUrl);

        // Добавляем внутренний userId к атрибутам, чтобы контроллер мог его прочитать
        Map<String, Object> attrs = new HashMap<>(raw.getAttributes());
        attrs.put("_userId", user.id());

        return new DefaultOAuth2User(
                Set.of(new OAuth2UserAuthority("ROLE_MODERATOR", attrs)),
                attrs,
                nameAttributeKey);
    }
}
