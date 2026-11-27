package com.example.stocksinglewas.matching.engine;

import com.example.stocksinglewas.matching.engine.SymbolWorker.State;

/**
 * 종목 엔진 하나의 현재 상태. 체결감시와 메트릭이 읽는다. 각 값은 읽는 순간의 값이라 서로 정확히 같은 시점은 아니다.
 *
 * @param state          워커 상태. FAILED면 그 종목 주문이 재시작 전까지 처리되지 않는다(ADR 0013)
 * @param busy           워커가 주문 하나를 처리하는 중인지
 * @param queueSize      입력 대기열에 남은 주문 수
 * @param overflowed     넘침 상태(워커가 DB의 RECEIVED를 이어 처리하는 중)
 * @param restoredOrders 엔진을 준비할 때 DB에서 호가창으로 복원한 대기 주문 수
 * @param nextSeq        다음 처리순번
 */
public record EngineStatus(String symbolCode, State state, boolean busy, int queueSize, boolean overflowed,
                           int restoredOrders, long nextSeq) {
}
