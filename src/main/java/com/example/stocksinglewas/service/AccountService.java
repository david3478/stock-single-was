package com.example.stocksinglewas.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.stocksinglewas.dao.AccountDao;
import com.example.stocksinglewas.matching.Fill;
import com.example.stocksinglewas.matching.FillSettlement;
import com.example.stocksinglewas.matching.SettlementCalculator;
import com.example.stocksinglewas.service.OrderRejectedException.Reason;

/**
 * 계좌관리Service. 예약은 호출자의 트랜잭션(T1) 안에서만 실행한다.
 *
 * <p>두 메서드 모두 계좌 행을 먼저 잠근다. 매도에서 보유 행을 먼저 갱신하면, 이어지는 주문 INSERT의
 * 외래키 검사가 계좌 행에 공유 잠금을 요청하게 된다. 체결 정산(T2)은 계좌 행 → 보유 행 순으로 잠그므로
 * 같은 계좌의 매도 접수와 정산이 서로를 기다리는 데드락이 생긴다.
 */
@Service
public class AccountService {

    private final AccountDao accounts;

    public AccountService(AccountDao accounts) {
        this.accounts = accounts;
    }

    /** 매수금액예약. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveCash(long accountId, long amount) {
        lockExisting(accountId);
        if (!accounts.reserveCash(accountId, amount)) {
            throw new OrderRejectedException(Reason.INSUFFICIENT_CASH,
                    "available cash is less than " + amount);
        }
    }

    /** 매도수량예약. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveQuantity(long accountId, String symbolCode, int quantity) {
        lockExisting(accountId);
        if (!accounts.reserveQuantity(accountId, symbolCode, quantity)) {
            throw new OrderRejectedException(Reason.INSUFFICIENT_QUANTITY,
                    "sellable quantity of " + symbolCode + " is less than " + quantity);
        }
    }

    /**
     * T2 시작 시 관련 계좌를 전부 ID 오름차순으로 잠근다(ADR 0003 ④).
     * 이후의 정산과 알림 INSERT는 이미 잡은 잠금 안에서 일어나므로 잠금 순서가 뒤집히지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForMatching(List<Long> ascendingAccountIds) {
        for (long accountId : ascendingAccountIds) {
            if (!accounts.lock(accountId)) {
                throw new IllegalStateException("account " + accountId + " disappeared");
            }
        }
    }

    /** 체결정산: 체결 1건을 양쪽 계좌와 보유에 반영한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(String symbolCode, Fill fill) {
        FillSettlement s = SettlementCalculator.settle(fill);
        if (!accounts.settleBuyerCash(fill.buyAccountId(), s.buyerReservedCashRelease(), s.buyerCashRefund())) {
            throw new IllegalStateException("buyer " + fill.buyAccountId() + " reserved cash is short");
        }
        accounts.addHolding(fill.buyAccountId(), symbolCode, s.quantity());
        accounts.addCash(fill.sellAccountId(), s.sellerCashProceeds());
        if (!accounts.releaseSoldHolding(fill.sellAccountId(), symbolCode, s.quantity())) {
            throw new IllegalStateException("seller " + fill.sellAccountId() + " reserved quantity is short");
        }
    }

    private void lockExisting(long accountId) {
        if (!accounts.lock(accountId)) {
            throw new OrderRejectedException(Reason.ACCOUNT_NOT_FOUND, "account " + accountId + " not found");
        }
    }
}
