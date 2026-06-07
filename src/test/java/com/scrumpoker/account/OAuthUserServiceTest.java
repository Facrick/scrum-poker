package com.scrumpoker.account;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Epic("ЛК модератора")
@Feature("OAuth2: маппинг пользователей")
@DisplayName("OAuthUserService: маппинг атрибутов Google/GitHub → User")
class OAuthUserServiceTest {

    private final UserRepository        userRepo = mock(UserRepository.class);
    private final OAuthUserService      service  = new OAuthUserService(userRepo);

    private OAuth2User googleRaw(String sub, String name, String email, String picture) {
        // Пустой набор authorities — допустим для сырого пользователя от провайдера
        return new DefaultOAuth2User(
                Set.of(),
                Map.of("sub", sub, "name", name, "email", email, "picture", picture),
                "sub");
    }

    private OAuth2User githubRaw(int id, String login, String name, String email, String avatarUrl) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", id);
        attrs.put("login", login);
        attrs.put("name", name);          // может быть null
        attrs.put("email", email);
        attrs.put("avatar_url", avatarUrl);
        return new DefaultOAuth2User(Set.of(), attrs, "id");
    }

    private User stubUser(String id, String provider, String providerId) {
        return new User(id, provider, providerId, "e@e.com", "Name", null);
    }

    // ─────────────────────────────────────────────────────────────

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Google: атрибуты sub/name/email/picture маппятся корректно")
    void googleAttributesMappedCorrectly() {
        when(userRepo.upsert("google", "g-sub-1", "alice@gmail.com", "Alice Smith", "https://pic/a"))
                .thenReturn(stubUser("uid-1", "google", "g-sub-1"));

        OAuth2User result = service.processUser("google",
                googleRaw("g-sub-1", "Alice Smith", "alice@gmail.com", "https://pic/a"));

        assertThat((String) result.getAttribute("_userId")).isEqualTo("uid-1");
        verify(userRepo).upsert("google", "g-sub-1", "alice@gmail.com", "Alice Smith", "https://pic/a");
    }

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GitHub: атрибуты id/name/email/avatar_url маппятся корректно")
    void githubAttributesMappedCorrectly() {
        when(userRepo.upsert("github", "42", "bob@github.com", "Bob Builder", "https://avatars/u/42"))
                .thenReturn(stubUser("uid-2", "github", "42"));

        OAuth2User result = service.processUser("github",
                githubRaw(42, "bobgh", "Bob Builder", "bob@github.com", "https://avatars/u/42"));

        assertThat((String) result.getAttribute("_userId")).isEqualTo("uid-2");
        verify(userRepo).upsert("github", "42", "bob@github.com", "Bob Builder", "https://avatars/u/42");
    }

    @Test
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("GitHub: null имя заменяется на логин")
    void githubNullNameFallsBackToLogin() {
        when(userRepo.upsert(eq("github"), eq("99"), any(), eq("carol-gh"), any()))
                .thenReturn(stubUser("uid-3", "github", "99"));

        service.processUser("github",
                githubRaw(99, "carol-gh", null, "carol@example.com", "https://avatars/u/99"));

        verify(userRepo).upsert("github", "99", "carol@example.com", "carol-gh", "https://avatars/u/99");
    }

    @Test
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Результирующий OAuth2User содержит ROLE_MODERATOR и _userId")
    void principalHasModeratorRoleAndInternalId() {
        when(userRepo.upsert(any(), any(), any(), any(), any()))
                .thenReturn(stubUser("uid-4", "google", "sub-4"));

        OAuth2User result = service.processUser("google",
                googleRaw("sub-4", "Dana", "dana@gmail.com", "https://pic/d"));

        assertThat(result.getAuthorities()).anySatisfy(a ->
                assertThat(a.getAuthority()).isEqualTo("ROLE_MODERATOR"));
        assertThat((String) result.getAttribute("_userId")).isEqualTo("uid-4");
    }
}
