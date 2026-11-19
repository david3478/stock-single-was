package com.example.stocksinglewas.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트용 DB 초기화와 조회 도우미. 테스트 클래스들이 같은 MySQL 컨테이너를 쓰므로 매 테스트 전에 비운다. */
public final class TestData {

    private final JdbcTemplate jdbc;

    public TestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM trades");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM holdings");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("DELETE FROM symbols");
    }

    public void symbol(String code) {
        jdbc.update("INSERT INTO symbols (code, name, status) VALUES (?, ?, 'ACTIVE')", code, code);
    }

    public void account(long id, long cash) {
        jdbc.update("INSERT INTO accounts (id, cash_balance, reserved_cash) VALUES (?, ?, 0)", id, cash);
    }

    public void holding(long accountId, String symbol, int quantity) {
        jdbc.update("INSERT INTO holdings (account_id, symbol_code, quantity, reserved_quantity) VALUES (?, ?, ?, 0)",
                accountId, symbol, quantity);
    }

    public long cash(long accountId) {
        return jdbc.queryForObject("SELECT cash_balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    public long reservedCash(long accountId) {
        return jdbc.queryForObject("SELECT reserved_cash FROM accounts WHERE id = ?", Long.class, accountId);
    }

    /** {총보유량, 예약수량}. 보유 행이 없으면 {0, 0}. */
    public int[] holdingOf(long accountId, String symbol) {
        return jdbc.query("SELECT quantity, reserved_quantity FROM holdings WHERE account_id = ? AND symbol_code = ?",
                        (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}, accountId, symbol)
                .stream().findFirst().orElse(new int[] {0, 0});
    }

    public String status(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    public int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** 이미 한 번 처리되어 대기 중인 주문으로 만든다(워커가 처리한 상태를 흉내 낸다). */
    public void markOpen(long orderId, long seq) {
        jdbc.update("UPDATE orders SET status = 'OPEN', seq = ? WHERE id = ?", seq, orderId);
    }
}
