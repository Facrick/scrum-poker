package com.scrumpoker.account;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me")
public class AccountController {

    private final UserRepository userRepository;
    private final SessionHistoryRepository sessionHistoryRepository;

    public AccountController(UserRepository userRepository,
                             SessionHistoryRepository sessionHistoryRepository) {
        this.userRepository = userRepository;
        this.sessionHistoryRepository = sessionHistoryRepository;
    }

    public record UserResponse(
            String id,
            String displayName,
            String email,
            String avatarUrl,
            String provider
    ) {}

    public record SessionResponse(
            String roomId,
            String roomName,
            int participantCount,
            int taskCount,
            int estimatedCount,
            String startedAt,
            String lastActiveAt
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

    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@AuthenticationPrincipal OAuth2User oauthUser) {
        if (oauthUser == null) return List.of();
        String userId = oauthUser.getAttribute("_userId");
        return sessionHistoryRepository.findByOwnerUserId(userId).stream()
                .map(s -> new SessionResponse(
                        s.roomId(), s.roomName(),
                        s.participantCount(), s.taskCount(), s.estimatedCount(),
                        s.startedAt().toString(), s.lastActiveAt().toString()))
                .toList();
    }
}
