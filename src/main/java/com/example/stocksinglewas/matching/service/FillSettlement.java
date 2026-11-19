package com.example.stocksinglewas.matching.service;

/**
 * 체결 1건이 계좌·보유에 주는 변화량.
 * <ul>
 *   <li>매수 계좌: reserved_cash -= buyerReservedCashRelease, cash_balance += buyerCashRefund,
 *       보유 quantity += quantity</li>
 *   <li>매도 계좌: cash_balance += sellerCashProceeds,
 *       보유 quantity -= quantity, reserved_quantity -= quantity</li>
 * </ul>
 * 매수 대금은 접수 때 cash_balance에서 reserved_cash로 이미 옮겼으므로 여기서 cash_balance를 다시 빼지 않는다.
 */
public record FillSettlement(
        long buyerReservedCashRelease,
        long buyerCashRefund,
        long sellerCashProceeds,
        int quantity) {
}
