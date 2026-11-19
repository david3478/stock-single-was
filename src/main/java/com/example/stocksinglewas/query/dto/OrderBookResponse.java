package com.example.stocksinglewas.query.dto;

import java.util.List;

/**
 * 종목 호가창. DB에 커밋된 대기 주문 기준이다.
 *
 * @param bids 매수 호가, 높은 가격부터
 * @param asks 매도 호가, 낮은 가격부터
 */
public record OrderBookResponse(String symbolCode, List<PriceLevel> bids, List<PriceLevel> asks) {
}
