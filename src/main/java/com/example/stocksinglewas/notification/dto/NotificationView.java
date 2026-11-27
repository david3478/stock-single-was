package com.example.stocksinglewas.notification.dto;

/**
 * 브라우저로 보내는 알림 하나.
 *
 * @param id      알림 번호. SSE 이벤트 id이며 브라우저가 재연결할 때 Last-Event-ID로 돌려준다
 * @param type    알림종류. SSE 이벤트 이름
 * @param payload 주문 결과 JSON
 */
public record NotificationView(long id, String type, String payload) {
}
