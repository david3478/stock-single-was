package com.example.stocksinglewas.matching.engine;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 종목 엔진 준비 상태. readiness 그룹에 넣어 기동 복구가 끝나기 전에는 readiness가 DOWN이 되게 한다(ADR 0013).
 * 이름은 빈 이름에서 HealthIndicator를 뺀 "engines"다.
 */
@Component
public class EnginesHealthIndicator implements HealthIndicator {

    private final SymbolPreparer preparer;

    public EnginesHealthIndicator(SymbolPreparer preparer) {
        this.preparer = preparer;
    }

    @Override
    public Health health() {
        return preparer.health();
    }
}
