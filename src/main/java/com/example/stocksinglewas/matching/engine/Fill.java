package com.example.stocksinglewas.matching.engine;

/**
 * 체결 1건(EA: 체결결과). 체결가는 먼저 대기하던 주문의 가격이다.
 * buyLimitPrice는 매수 접수 때 예약한 단가로, 정산에서 차액 환불을 계산하는 데 쓴다.
 */
public record Fill(
        long buyOrderId,
        long sellOrderId,
        long buyAccountId,
        long sellAccountId,
        long buyLimitPrice,
        long price,
        int quantity) {
}
