package com.scrumpoker.account;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class AccountController {

    private final UserRepository userRepository;

    public AccountController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record UserResponse(
            String id,
            String displayName,
            String email,
            String avatarUrl,
            String provider
    ) {}

    @GetMapping
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal OAuth2User oauthUser) {
        if (oauthUser == null) {
            return ResponseEntity.status(401).build();
        }
        String userId = oauthUser.getAttribute("_userId");
        return userRepository.findById(userId)
                .map(u -> ResponseEntity.ok(new UserResponse(
                        u.id(), u.displayName(), u.email(), u.avatarUrl(), u.provider())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }
}
