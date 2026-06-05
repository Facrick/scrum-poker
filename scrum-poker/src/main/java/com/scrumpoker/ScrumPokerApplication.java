package com.scrumpoker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ScrumPokerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScrumPokerApplication.class, args);
    }
}
