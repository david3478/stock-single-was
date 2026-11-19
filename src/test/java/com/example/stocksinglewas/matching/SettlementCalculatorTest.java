package com.example.stocksinglewas.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SettlementCalculatorTest {

    // --- 매수 접수 예약 ---

    @Test
    void reservesLimitPriceTimesQuantity() {
        assertThat(SettlementCalculator.buyReservation(1_000, 7)).isEqualTo(7_000);
    }

    @Test
    void reservationBeyondIntRangeStaysExact() {
        assertThat(SettlementCalculator.buyReservation(3_000_000, 1_000)).isEqualTo(3_000_000_000L);
    }

    @Test
    void rejectsReservationOverflow() {
        assertThatThrownBy(() -> SettlementCalculator.buyReservation(Long.MAX_VALUE / 2, 3))
                .isInstanceOf(ArithmeticException.class);
    }

    // --- 체결 정산 ---

    @Test
    void refundsDifferenceWhenFilledBelowBuyerLimit() {
        Fill fill = new Fill(1, 2, 10, 20, 1_100, 1_000, 5);

        FillSettlement s = SettlementCalculator.settle(fill);

        assertThat(s.buyerReservedCashRelease()).isEqualTo(5_500);
        assertThat(s.buyerCashRefund()).isEqualTo(500);
        assertThat(s.sellerCashProceeds()).isEqualTo(5_000);
        assertThat(s.quantity()).isEqualTo(5);
    }

    @Test
    void noRefundWhenFilledAtBuyerLimit() {
        FillSettlement s = SettlementCalculator.settle(new Fill(1, 2, 10, 20, 1_000, 1_000, 5));

        assertThat(s.buyerCashRefund()).isZero();
        assertThat(s.buyerReservedCashRelease()).isEqualTo(s.sellerCashProceeds());
    }

    /** 접수에서 예약한 금액을 정산에서 다시 차감하지 않는다: 매수자 총현금은 체결대금만큼만 줄어든다. */
    @Test
    void buyerTotalCashDropsByTradeValueOnly() {
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
    void rejectsFillAboveBuyerLimit() {
        assertThatThrownBy(() -> SettlementCalculator.settle(new Fill(1, 2, 10, 20, 1_000, 1_001, 5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSettlementOverflow() {
        Fill fill = new Fill(1, 2, 10, 20, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, 3);

        assertThatThrownBy(() -> SettlementCalculator.settle(fill)).isInstanceOf(ArithmeticException.class);
    }
}
