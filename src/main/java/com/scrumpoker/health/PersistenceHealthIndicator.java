package com.scrumpoker.health;

import com.scrumpoker.service.RoomService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Индикатор здоровья персистентности.
 * Краснеет (DOWN), если подряд идут сбои записи снимков комнат в БД —
 * раньше такой сбой был виден только в логах и тихо терял данные.
 *
 * Регистрируется как компонент "persistence" в /actuator/health.
 * НЕ входит в группу liveness, поэтому не вызывает рестарт контейнера:
 * проблема с БД не лечится перезапуском приложения.
 */
@Component("persistence")
public class PersistenceHealthIndicator implements HealthIndicator {

    private final RoomService roomService;

    public PersistenceHealthIndicator(RoomService roomService) {
        this.roomService = roomService;
    }

    @Override
    public Health health() {
        int failures = roomService.getConsecutivePersistFailures();
        if (failures > 0) {
            return Health.down()
                    .withDetail("consecutiveFailures", failures)
                    .withDetail("message", "Снимки комнат не сохраняются в БД")
                    .build();
        }
        return Health.up()
                .withDetail("consecutiveFailures", 0)
                .build();
    }
}
