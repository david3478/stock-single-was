package com.example.stocksinglewas.query.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.stocksinglewas.query.dto.BalanceResponse;
import com.example.stocksinglewas.query.dto.HoldingResponse;
import com.example.stocksinglewas.query.dto.OrderBookResponse;
import com.example.stocksinglewas.query.dto.OrderHistoryResponse;
import com.example.stocksinglewas.query.dto.SymbolResponse;
import com.example.stocksinglewas.query.service.QueryService;

/** 조회Controller. */
@RestController
public class QueryController {

    private final QueryService query;

    public QueryController(QueryService query) {
        this.query = query;
    }

    @GetMapping("/api/accounts/{accountId}/balance")
    public BalanceResponse balance(@PathVariable long accountId) {
        return query.balance(accountId);
    }

    @GetMapping("/api/accounts/{accountId}/holdings")
    public List<HoldingResponse> holdings(@PathVariable long accountId) {
        return query.holdings(accountId);
    }

    @GetMapping("/api/accounts/{accountId}/orders")
    public List<OrderHistoryResponse> orders(@PathVariable long accountId,
                                             @RequestParam(required = false) Long beforeId,
                                             @RequestParam(required = false) Integer size) {
        return query.orders(accountId, beforeId, size);
    }

    @GetMapping("/api/symbols")
    public List<SymbolResponse> symbols() {
        return query.symbols();
    }

    @GetMapping("/api/symbols/{symbolCode}/orderbook")
    public OrderBookResponse orderBook(@PathVariable String symbolCode,
                                      @RequestParam(required = false) Integer depth) {
        return query.orderBook(symbolCode, depth);
    }
}
