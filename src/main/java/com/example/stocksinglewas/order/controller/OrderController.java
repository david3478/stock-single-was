package com.example.stocksinglewas.order.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.stocksinglewas.order.dto.PlaceOrderRequest;
import com.example.stocksinglewas.order.dto.PlaceOrderResponse;
import com.example.stocksinglewas.order.service.OrderIntakeService;

import jakarta.validation.Valid;

/**
 * 주문접수Controller.
 * 202 Accepted는 접수 확정만 뜻한다. 체결은 종목 워커가 이후에 처리한다.
 */
@RestController
public class OrderController {

    private final OrderIntakeService intake;

    public OrderController(OrderIntakeService intake) {
        this.intake = intake;
    }

    /** 주문하기. */
    @PostMapping("/api/accounts/{accountId}/orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PlaceOrderResponse placeOrder(@PathVariable long accountId, @Valid @RequestBody PlaceOrderRequest request) {
        return new PlaceOrderResponse(intake.receive(accountId, request));
    }
}
