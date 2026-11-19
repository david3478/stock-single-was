package com.example.stocksinglewas.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 계좌DAO. 예약은 조건부 UPDATE 한 문장으로 "잔고 확인 + 차감"을 원자적으로 한다.
 * 갱신된 행이 없으면 잔고·수량이 부족한 것이다.
 */
@Repository
public class AccountDao {

    private final JdbcTemplate jdbc;

    public AccountDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 계좌 행을 트랜잭션 끝까지 잠근다. 계좌가 없으면 false.
     * 잠금 순서를 "계좌 행 → 보유 행"으로 고정하기 위해 보유 행을 갱신하기 전에 호출한다.
     */
    public boolean lock(long accountId) {
        return !jdbc.queryForList("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, accountId)
                .isEmpty();
    }

    /** 가능금액예약: 사용 가능 현금에서 예약 현금으로 옮긴다. */
    public boolean reserveCash(long accountId, long amount) {
        return jdbc.update("""
                UPDATE accounts
                SET cash_balance = cash_balance - ?, reserved_cash = reserved_cash + ?
                WHERE id = ? AND cash_balance >= ?""",
                amount, amount, accountId, amount) == 1;
    }

    /** 가능수량예약: 총보유량은 그대로 두고 예약 수량만 늘린다. */
    public boolean reserveQuantity(long accountId, String symbolCode, int quantity) {
        return jdbc.update("""
                UPDATE holdings
                SET reserved_quantity = reserved_quantity + ?
                WHERE account_id = ? AND symbol_code = ? AND quantity - reserved_quantity >= ?""",
                quantity, accountId, symbolCode, quantity) == 1;
    }
}
