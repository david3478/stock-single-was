package com.example.stocksinglewas.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.mysql.MySQLContainer;

/**
 * MySQL 통합 테스트 공통 부모. MySQL 8.4 컨테이너 하나를 모든 테스트 클래스가 같이 쓴다.
 * 클래스마다 컨테이너를 새로 띄우면 캐시된 Spring 컨텍스트가 죽은 컨테이너를 가리키므로,
 * static 초기화로 한 번만 띄우고 JVM 종료 시 Testcontainers가 정리하게 둔다.
 * Docker가 없으면 건너뛰지 않고 실패한다 (통과한 것처럼 보이지 않도록).
 */
@SpringBootTest
public abstract class MySqlIntegrationTest {

    @ServiceConnection
    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();
    }
}
