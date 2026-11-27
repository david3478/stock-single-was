package com.example.stocksinglewas.notification.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.stocksinglewas.notification.service.NotificationPushService;

/**
 * 알림구독Controller. 브라우저 EventSource가 연결한다.
 * 끊기면 EventSource가 자동으로 재연결하면서 마지막으로 받은 이벤트 id를 Last-Event-ID 헤더로 보낸다.
 */
@RestController
public class NotificationController {

    private final NotificationPushService push;

    public NotificationController(NotificationPushService push) {
        this.push = push;
    }

    @GetMapping(path = "/api/accounts/{accountId}/notifications", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable long accountId,
                                @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        return push.subscribe(accountId, lastEventId);
    }
}
