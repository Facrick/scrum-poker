package com.scrumpoker.ws;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class DisconnectListener {

    private final PokerController pokerController;

    public DisconnectListener(PokerController pokerController) {
        this.pokerController = pokerController;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        pokerController.handleDisconnect(event.getSessionId());
    }
}
