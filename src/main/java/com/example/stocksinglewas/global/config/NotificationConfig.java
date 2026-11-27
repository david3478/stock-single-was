package com.example.stocksinglewas.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 알림 송신 실행 자원. 체결 워커가 느린 브라우저를 기다리지 않도록 SSE 전송은 이 스레드들이 한다.
 * 스레드 수와 대기열은 고정이며, 대기열이 가득 차면 제출을 거절한다(호출자가 처리).
 * 연결유지 신호를 주기적으로 보내기 위해 스케줄링을 켠다.
 */
@Configuration
@EnableScheduling
public class NotificationConfig {

    public static final String SSE_SENDER_EXECUTOR = "sseSenderExecutor";

    @Bean(SSE_SENDER_EXECUTOR)
    public ThreadPoolTaskExecutor sseSenderExecutor(NotificationProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.senderThreads());
        executor.setMaxPoolSize(props.senderThreads());
        executor.setQueueCapacity(props.senderQueueCapacity());
        executor.setThreadNamePrefix("sse-sender-");
        return executor;
    }
}
