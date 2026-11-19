package com.example.stocksinglewas.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SettlementCalculatorTest {

    // --- 매수 접수 예약 ---

    @Test
    void 매수_예약금은_지정가_곱하기_수량이다() {
        assertThat(SettlementCalculator.buyReservation(1_000, 7)).isEqualTo(7_000);
    }

    @Test
    void 예약금이_int_범위를_넘어도_정확하다() {
        assertThat(SettlementCalculator.buyReservation(3_000_000, 1_000)).isEqualTo(3_000_000_000L);
    }

    @Test
    void 예약금이_long_범위를_넘으면_예외가_난다() {
        assertThatThrownBy(() -> SettlementCalculator.buyReservation(Long.MAX_VALUE / 2, 3))
                .isInstanceOf(ArithmeticException.class);
    }

    // --- 체결 정산 ---

    @Test
    void 지정가보다_싸게_체결되면_차액을_돌려준다() {
        Fill fill = new Fill(1, 2, 10, 20, 1_100, 1_000, 5);

        FillSettlement s = SettlementCalculator.settle(fill);

        assertThat(s.buyerReservedCashRelease()).isEqualTo(5_500);
        assertThat(s.buyerCashRefund()).isEqualTo(500);
        assertThat(s.sellerCashProceeds()).isEqualTo(5_000);
        assertThat(s.quantity()).isEqualTo(5);
    }

    @Test
    void 지정가에_체결되면_환불이_없다() {
        FillSettlement s = SettlementCalculator.settle(new Fill(1, 2, 10, 20, 1_000, 1_000, 5));

        assertThat(s.buyerCashRefund()).isZero();
        assertThat(s.buyerReservedCashRelease()).isEqualTo(s.sellerCashProceeds());
    }

    /** 접수에서 예약한 금액을 정산에서 다시 차감하지 않는다: 매수자 총현금은 체결대금만큼만 줄어든다. */
    @Test
    void 예약금을_두_번_빼지_않아_매수자_총현금은_체결대금만큼만_준다() {
        long cash = 100_000;
        long limit = 1_100;
        int qty = 5;
        Fill fill = new Fill(1, 2, 10, 20, limit, 1_000, qty);

        long reserved = SettlementCalculator.buyReservation(limit, qty);
        long available = cash - reserved;
        FillSettlement s = SettlementCalculator.settle(fill);
        available += s.buyerCashRefund();
        reserved -= s.buyerReservedCashRelease();

        assertThat(reserved).isZero();
        assertThat(available + reserved).isEqualTo(cash - s.sellerCashProceeds());
    }

    @Test
    void 매수_지정가보다_비싼_체결은_거절한다() {
        assertThatThrownBy(() -> SettlementCalculator.settle(new Fill(1, 2, 10, 20, 1_000, 1_001, 5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정산_금액이_long_범위를_넘으면_예외가_난다() {
        Fill fill = new Fill(1, 2, 10, 20, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, 3);

        assertThatThrownBy(() -> SettlementCalculator.settle(fill)).isInstanceOf(ArithmeticException.class);
    }
}
