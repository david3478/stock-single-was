package com.example.stocksinglewas.notification.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.stocksinglewas.notification.dao.NotificationDao;
import com.example.stocksinglewas.order.entity.OrderStatus;
import com.example.stocksinglewas.matching.engine.Fill;

/**
 * 알림기록기. 알림 INSERT는 이 클래스로만 한다(ADR 0003 ⑤).
 *
 * <p>같은 계좌 알림의 ID 발급 순서와 커밋 순서를 맞추려면 INSERT 전에 그 계좌 행을 잠가야 한다.
 * 호출자인 체결확정Service가 T2 시작 시 관련 계좌를 모두 잠그므로 여기서는 잠그지 않는다.
 * 늦게 여기서 처음 잠그면 정산과 잠금 순서가 뒤집혀 데드락이 생길 수 있다.
 */
@Service
public class NotificationWriter {

    private final NotificationDao notifications;

    public NotificationWriter(NotificationDao notifications) {
        this.notifications = notifications;
    }

    /** 대기 등록 결과. 체결 없음. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOpen(long accountId, long orderId, int remaining) {
        notifications.insert(accountId, orderId, null, OrderStatus.OPEN, remaining, null, null);
    }

    /** 체결 1건으로 바뀐 한 주문의 결과. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFill(long accountId, long orderId, long tradeId, Fill fill, OrderStatus status, int remaining) {
        notifications.insert(accountId, orderId, tradeId, status, remaining, fill.price(), fill.quantity());
    }
}
