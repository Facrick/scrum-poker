package com.scrumpoker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Чистые URL → статические файлы
        registry.addViewController("/login").setViewName("forward:/login.html");
        registry.addViewController("/account").setViewName("forward:/account.html");
        registry.addViewController("/results").setViewName("forward:/results.html");
    }

    /**
     * HTML-страницы не кешируем: они ссылаются на версионированные JS/CSS (?v=N),
     * поэтому сам HTML обязан быть всегда свежим — иначе после деплоя браузер
     * грузит старую разметку со старыми ссылками на ассеты (баг: после деплоя
     * страница ЛК тянула устаревший account.js, и выход не работал).
     * Этот обработчик специфичнее дефолтного "/**", поэтому для *.html выигрывает;
     * остальные ассеты с ?v= по-прежнему кешируются на сутки
     * (spring.web.resources.cache.period).
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/*.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache().mustRevalidate());
    }
}
