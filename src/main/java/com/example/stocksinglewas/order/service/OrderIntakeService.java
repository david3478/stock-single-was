package com.example.stocksinglewas.order.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.stocksinglewas.global.exception.OrderRejectedException;
import com.example.stocksinglewas.account.service.AccountService;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.symbol.dao.SymbolDao;
import com.example.stocksinglewas.order.dto.PlaceOrderRequest;
import com.example.stocksinglewas.order.entity.Order;
import com.example.stocksinglewas.order.entity.OrderStatus;
import com.example.stocksinglewas.order.entity.Side;
import com.example.stocksinglewas.symbol.entity.Symbol;
import com.example.stocksinglewas.matching.engine.OrderDispatcher;
import com.example.stocksinglewas.matching.service.SettlementCalculator;
import com.example.stocksinglewas.global.exception.OrderRejectedException.Reason;

/**
 * 주문접수Service.
 *
 * <ol>
 *   <li>활성 종목인지 확인한다.</li>
 *   <li>같은 요청번호의 주문이 있으면 그 주문번호를 돌려준다(재요청).</li>
 *   <li>T1: 현금·수량 예약 + RECEIVED 주문 저장. 둘 중 하나라도 실패하면 둘 다 취소된다.</li>
 *   <li>T1 커밋 후 종목 입력 대기열로 전달한다. 전달 실패는 접수 실패가 아니다.</li>
 * </ol>
 */
@Service
public class OrderIntakeService {

    private static final Logger log = LoggerFactory.getLogger(OrderIntakeService.class);

    private final SymbolDao symbols;
    private final OrderDao orders;
    private final AccountService accounts;
    private final OrderDispatcher dispatcher;
    private final TransactionTemplate tx;

    public OrderIntakeService(SymbolDao symbols, OrderDao orders, AccountService accounts,
                              OrderDispatcher dispatcher, TransactionTemplate tx) {
        this.symbols = symbols;
        this.orders = orders;
        this.accounts = accounts;
        this.dispatcher = dispatcher;
        this.tx = tx;
    }

    /** 접수. 접수된 주문번호를 돌려준다. */
    public long receive(long accountId, PlaceOrderRequest request) {
        if (!symbols.find(request.symbolCode()).map(Symbol::tradable).orElse(false)) {
            throw new OrderRejectedException(Reason.SYMBOL_NOT_TRADABLE,
                    "symbol " + request.symbolCode() + " is not tradable");
        }
        Optional<Order> existing = orders.findByClientOrderId(accountId, request.clientOrderId());
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        long orderId;
        try {
            orderId = tx.execute(status -> reserveAndSave(accountId, request));
        } catch (DuplicateKeyException e) {
            // 같은 요청번호가 동시에 들어와 다른 요청이 먼저 커밋했다. 이 T1은 예약까지 모두 롤백됐다.
            Order winner = orders.findByClientOrderId(accountId, request.clientOrderId()).orElseThrow(() -> e);
            return replay(winner, request);
        }

        if (!dispatcher.dispatch(orderId, request.symbolCode())) {
            log.warn("order {} committed but not queued for {}; it stays RECEIVED in DB",
                    orderId, request.symbolCode());
        }
        return orderId;
    }

    private long reserveAndSave(long accountId, PlaceOrderRequest request) {
        if (request.side() == Side.BUY) {
            accounts.reserveCash(accountId, orderAmount(request));
        } else {
            accounts.reserveQuantity(accountId, request.symbolCode(), request.quantity());
        }
        return orders.insert(Order.received(accountId, request.symbolCode(), request.clientOrderId(),
                request.side(), request.limitPrice(), request.quantity()));
    }

    private static long orderAmount(PlaceOrderRequest request) {
        try {
            return SettlementCalculator.buyReservation(request.limitPrice(), request.quantity());
        } catch (ArithmeticException e) {
            throw new OrderRejectedException(Reason.AMOUNT_OUT_OF_RANGE, "limitPrice x quantity is out of range");
        }
    }

    /**
     * 재요청: 이미 접수된 주문을 돌려준다. 예약은 다시 하지 않는다.
     * 아직 RECEIVED이면 한 번 더 전달한다. 처음 전달이 실패했을 수 있고, 중복 전달은 워커가 무시한다.
     */
    private long replay(Order existing, PlaceOrderRequest request) {
        if (!existing.sameRequestAs(request.symbolCode(), request.side(), request.limitPrice(), request.quantity())) {
            throw new OrderRejectedException(Reason.REQUEST_ID_CONFLICT,
                    "clientOrderId " + request.clientOrderId() + " was used for a different order");
        }
        if (existing.status() == OrderStatus.RECEIVED) {
            dispatcher.dispatch(existing.id(), existing.symbolCode());
        }
        return existing.id();
    }
}
