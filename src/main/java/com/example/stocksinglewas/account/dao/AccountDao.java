package com.example.stocksinglewas.account.dao;

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

    /** 매수 정산: 예약 현금을 풀고 지정가와 체결가의 차액을 사용 가능 현금으로 돌려준다. */
    public boolean settleBuyerCash(long accountId, long reservedRelease, long refund) {
        return jdbc.update("""
                UPDATE accounts
                SET reserved_cash = reserved_cash - ?, cash_balance = cash_balance + ?
                WHERE id = ? AND reserved_cash >= ?""",
                reservedRelease, refund, accountId, reservedRelease) == 1;
    }

    /** 매도 정산: 체결대금을 사용 가능 현금에 더한다. */
    public boolean addCash(long accountId, long amount) {
        return jdbc.update("UPDATE accounts SET cash_balance = cash_balance + ? WHERE id = ?",
                amount, accountId) == 1;
    }

    /** 매수 체결 수량을 보유에 더한다. 처음 사는 종목이면 보유 행을 만든다. */
    public void addHolding(long accountId, String symbolCode, int quantity) {
        jdbc.update("""
                INSERT INTO holdings (account_id, symbol_code, quantity, reserved_quantity)
                VALUES (?, ?, ?, 0) AS new
                ON DUPLICATE KEY UPDATE quantity = holdings.quantity + new.quantity""",
                accountId, symbolCode, quantity);
    }

    /** 매도 체결: 총보유량과 예약 수량을 함께 줄인다. */
    public boolean releaseSoldHolding(long accountId, String symbolCode, int quantity) {
        return jdbc.update("""
                UPDATE holdings
                SET quantity = quantity - ?, reserved_quantity = reserved_quantity - ?
                WHERE account_id = ? AND symbol_code = ? AND reserved_quantity >= ?""",
                quantity, quantity, accountId, symbolCode, quantity) == 1;
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
