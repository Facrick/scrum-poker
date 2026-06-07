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
        return processUser(request.getClientRegistration().getRegistrationId(), raw);
    }

    /**
     * Маппинг атрибутов провайдера → User + обогащённый OAuth2User.
     * Метод пакетной видимости специально для модульных тестов —
     * позволяет тестировать бизнес-логику без HTTP-вызова к провайдеру.
     */
    OAuth2User processUser(String provider, OAuth2User raw) {
        String providerId, email, displayName, avatarUrl, nameAttributeKey;

        if ("github".equals(provider)) {
            // GitHub: числовой id, опциональный email (может быть приватным)
            providerId = String.valueOf((Object) raw.getAttribute("id"));
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

        Map<String, Object> attrs = new HashMap<>(raw.getAttributes());
        attrs.put("_userId", user.id());

        return new DefaultOAuth2User(
                Set.of(new OAuth2UserAuthority("ROLE_MODERATOR", attrs)),
                attrs,
                nameAttributeKey);
    }
}
