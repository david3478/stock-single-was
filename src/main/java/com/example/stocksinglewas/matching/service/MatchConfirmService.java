package com.example.stocksinglewas.matching.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.stocksinglewas.notification.service.NotificationWriter;
import com.example.stocksinglewas.account.service.AccountService;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.matching.dao.TradeDao;
import com.example.stocksinglewas.order.entity.OrderStatus;
import com.example.stocksinglewas.matching.engine.CounterpartyChange;
import com.example.stocksinglewas.matching.engine.Fill;
import com.example.stocksinglewas.matching.engine.IncomingOrder;
import com.example.stocksinglewas.matching.engine.MatchPlan;

/**
 * 체결확정Service. 종목 워커가 만든 체결 계획 하나를 T2 한 번으로 DB에 확정한다.
 *
 * <ol>
 *   <li>관련 계좌를 ID 오름차순으로 잠근다.</li>
 *   <li>입력 주문을 RECEIVED 조건으로 갱신하고 처리순번을 부여한다. 이미 처리됐으면 아무것도 하지 않는다.</li>
 *   <li>상대 주문을 예상 잔량 조건으로 갱신한다. 다르면 메모리와 DB가 어긋난 것이므로 전체 롤백.</li>
 *   <li>체결마다 체결 저장, 양쪽 정산, 양쪽 알림 저장. 체결이 없으면 대기 등록 알림.</li>
 * </ol>
 *
 * 워커는 이 메서드가 정상 반환한 뒤에만 계획을 호가창에 반영한다.
 * 워커와 다른 클래스여야 Spring 트랜잭션 프록시가 적용된다.
 */
@Service
public class MatchConfirmService {

    private final AccountService accounts;
    private final OrderDao orders;
    private final TradeDao trades;
    private final NotificationWriter notifications;

    public MatchConfirmService(AccountService accounts, OrderDao orders, TradeDao trades,
                               NotificationWriter notifications) {
        this.accounts = accounts;
        this.orders = orders;
        this.trades = trades;
        this.notifications = notifications;
    }

    /** 확정. 입력 주문이 이미 처리된 상태(중복 전달)면 false. */
    @Transactional
    public boolean confirm(String symbolCode, MatchPlan plan) {
        IncomingOrder in = plan.incoming();
        accounts.lockForMatching(plan.accountIds());

        if (!orders.markProcessed(in.orderId(), plan.seq(),
                statusOf(in.quantity(), plan.incomingRemaining()), plan.incomingRemaining())) {
            return false;
        }
        for (CounterpartyChange c : plan.counterparties()) {
            OrderStatus status = c.filled() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            if (!orders.updateResting(c.before().orderId(), c.before().remaining(), c.remainingAfter(), status)) {
                throw new IllegalStateException("resting order " + c.before().orderId() + " differs from book");
            }
        }

        List<Fill> fills = plan.fills();
        if (fills.isEmpty()) {
            notifications.recordOpen(in.accountId(), in.orderId(), in.quantity());
            return true;
        }
        int incomingRemaining = in.quantity();
        for (int i = 0; i < fills.size(); i++) {
            Fill fill = fills.get(i);
            CounterpartyChange counterparty = plan.counterparties().get(i);
            long tradeId = trades.insert(fill);
            accounts.settle(symbolCode, fill);

            incomingRemaining -= fill.quantity();
            notifications.recordFill(in.accountId(), in.orderId(), tradeId, fill,
                    statusOf(in.quantity(), incomingRemaining), incomingRemaining);
            notifications.recordFill(counterparty.before().accountId(), counterparty.before().orderId(), tradeId, fill,
                    counterparty.filled() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                    counterparty.remainingAfter());
        }
        return true;
    }

    private static OrderStatus statusOf(int quantity, int remaining) {
        if (remaining == quantity) {
            return OrderStatus.OPEN;
        }
        return remaining == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }
}
