package com.example.stocksinglewas.matching.engine;

/**
 * 계획이 상대 주문에 가하는 변경. before는 계획을 만들 때의 상태로,
 * T2의 조건부 UPDATE(예상 잔량 확인)와 커밋 후 반영 시 상태 확인에 쓴다.
 */
public record CounterpartyChange(RestingOrder before, int remainingAfter) {

    public boolean filled() {
        return remainingAfter == 0;
    }
}
