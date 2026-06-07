package com.scrumpoker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Громко предупреждает в логах, если приложение работает на H2 in-memory:
 * в этом режиме все сессии и комнаты теряются при каждом рестарте/редеплое.
 * На проде (Railway) нужно подключить Postgres и задать DATABASE_URL.
 */
@Component
public class PersistenceStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(PersistenceStartupCheck.class);

    private final String datasourceUrl;

    public PersistenceStartupCheck(@Value("${spring.datasource.url:}") String datasourceUrl) {
        this.datasourceUrl = datasourceUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (datasourceUrl.contains("h2:mem")) {
            log.warn("============================================================");
            log.warn("ВНИМАНИЕ: используется H2 in-memory ({}).", datasourceUrl);
            log.warn("Все сессии и комнаты будут СТЁРТЫ при рестарте/редеплое!");
            log.warn("На проде подключите Postgres и задайте DATABASE_URL.");
            log.warn("============================================================");
        } else {
            log.info("Постоянное хранилище: {}", datasourceUrl);
        }
    }
}
