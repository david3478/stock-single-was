package com.example.stocksinglewas.query.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.stocksinglewas.account.dao.AccountDao;
import com.example.stocksinglewas.global.config.QueryProperties;
import com.example.stocksinglewas.global.exception.NotFoundException;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.order.entity.Side;
import com.example.stocksinglewas.query.dto.BalanceResponse;
import com.example.stocksinglewas.query.dto.HoldingResponse;
import com.example.stocksinglewas.query.dto.OrderBookResponse;
import com.example.stocksinglewas.query.dto.OrderHistoryResponse;
import com.example.stocksinglewas.query.dto.SymbolResponse;
import com.example.stocksinglewas.symbol.dao.SymbolDao;

/**
 * 조회Service. 읽기 전용이며 DB에 커밋된 상태를 보여준다.
 * 체결은 비동기로 진행되므로, 방금 낸 주문은 잠시 RECEIVED로 보일 수 있다.
 */
@Service
public class QueryService {

    private final AccountDao accounts;
    private final OrderDao orders;
    private final SymbolDao symbols;
    private final QueryProperties props;

    public QueryService(AccountDao accounts, OrderDao orders, SymbolDao symbols, QueryProperties props) {
        this.accounts = accounts;
        this.orders = orders;
        this.symbols = symbols;
        this.props = props;
    }

    /** 잔고조회. */
    public BalanceResponse balance(long accountId) {
        return accounts.findBalance(accountId).orElseThrow(() -> NotFoundException.account(accountId));
    }

    /** 보유종목조회. */
    public List<HoldingResponse> holdings(long accountId) {
        requireAccount(accountId);
        return accounts.findHoldings(accountId);
    }

    /**
     * 주문내역묶음조회. 최신순.
     *
     * @param beforeId 이전 페이지의 마지막 주문번호. 첫 페이지는 null
     * @param size     null이면 기본값, 1 ~ 최대값으로 맞춘다
     */
    public List<OrderHistoryResponse> orders(long accountId, Long beforeId, Integer size) {
        requireAccount(accountId);
        return orders.findHistory(accountId, beforeId, clamp(size, props.defaultPageSize(), props.maxPageSize()));
    }

    /** 종목 호가창. */
    public OrderBookResponse orderBook(String symbolCode, Integer depth) {
        if (symbols.find(symbolCode).isEmpty()) {
            throw NotFoundException.symbol(symbolCode);
        }
        int levels = clamp(depth, props.defaultOrderBookDepth(), props.maxOrderBookDepth());
        return new OrderBookResponse(symbolCode,
                orders.findPriceLevels(symbolCode, Side.BUY, levels),
                orders.findPriceLevels(symbolCode, Side.SELL, levels));
    }

    public List<SymbolResponse> symbols() {
        return symbols.findAll().stream()
                .map(s -> new SymbolResponse(s.code(), s.name(), s.status().name()))
                .toList();
    }

    private void requireAccount(long accountId) {
        if (accounts.findBalance(accountId).isEmpty()) {
            throw NotFoundException.account(accountId);
        }
    }

    private static int clamp(Integer requested, int defaultValue, int max) {
        if (requested == null) {
            return defaultValue;
        }
        return Math.max(1, Math.min(requested, max));
    }
}
