package com.scrumpoker.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной (E2E) сценарий анонимного покера в реальном браузере.
 * <p>
 * Поднимает приложение на случайном порту (H2 in-memory) и через Playwright
 * проверяет ключевой поток: ведущий создаёт комнату → второй участник входит →
 * оба голосуют → вскрытие → консенсус.
 * <p>
 * Помечен тегом {@code e2e} и по умолчанию исключён из {@code mvn test}
 * (требует браузера). Запуск: {@code mvn -Pe2e test}. Playwright при первом
 * запуске сам скачает Chromium (нужен интернет).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("e2e")
@Epic("E2E")
@Feature("Анонимный покер: голосование до консенсуса")
@DisplayName("E2E: создание комнаты → вход второго → голосование → консенсус")
class PokerFlowE2ETest {

    @LocalServerPort
    int port;

    static Playwright playwright;
    static Browser browser;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test
    @DisplayName("Двое голосуют '5' → после вскрытия показан консенсус '5'")
    void twoPlayersReachConsensus() {
        String base = "http://localhost:" + port + "/";

        // ── Ведущий создаёт комнату ──
        BrowserContext modCtx = browser.newContext();
        Page mod = modCtx.newPage();
        mod.navigate(base);
        mod.fill("#nameInput", "Алиса");
        mod.fill("#roomNameInput", "E2E Спринт");
        mod.click("#primaryBtn");
        mod.waitForSelector("#room:not(.hidden)");
        mod.waitForURL(url -> url.contains("room="));
        String roomUrl = mod.url();

        // ── Второй участник входит по ссылке ──
        BrowserContext playerCtx = browser.newContext();
        Page player = playerCtx.newPage();
        player.navigate(roomUrl);                 // joinMode: вкладка «Войти», код предзаполнен
        player.fill("#nameInput", "Боб");
        player.click("#primaryBtn");
        player.waitForSelector("#room:not(.hidden)");

        // ── Оба голосуют «5» ──
        mod.click(".deck .pcard[title='5']");
        player.click(".deck .pcard[title='5']");

        // ── Ведущий вскрывает карты ──
        mod.click("#revealBtn");

        // ── Консенсус «5» виден у ведущего ──
        mod.waitForSelector(".consensus-hero");
        assertThat(mod.locator(".consensus-hero-value").textContent().trim()).isEqualTo("5");

        // ── …и у участника тоже (стейт прилетел по WebSocket) ──
        player.waitForSelector(".consensus-hero");
        assertThat(player.locator(".consensus-hero-value").textContent().trim()).isEqualTo("5");

        modCtx.close();
        playerCtx.close();
    }
}
