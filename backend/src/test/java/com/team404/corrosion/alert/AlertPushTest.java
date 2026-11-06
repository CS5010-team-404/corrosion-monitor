package com.team404.corrosion.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.team404.corrosion.AivenMySql;
import com.team404.corrosion.alert.lifecycle.AlertService;
import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A browser-like STOMP client on /ws (no token) receives the notice when an alert changes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001", "ecp.demo.seed=false",
        "ecp.alerts.poll-interval=PT1H", "ecp.alerts.escalation-interval=PT1H"})
@Testcontainers
class AlertPushTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AlertService service;

    @Test
    @SuppressWarnings("unchecked")
    void acknowledgingAnAlertPushesANoticeOnTopicAlerts() throws Exception {
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user (name, email, password_hash, role) VALUES ('Tara Tech', 'tara@test.local', 'x', 'TECHNICIAN')");
        jdbc.update("""
                INSERT INTO alert (type, severity, title, message, status, opened_at, source)
                VALUES ('CONDENSATION_RISK', 'CRITICAL', 'Test', 'Margin is low', 'OPEN', ?, 'ANALYTICS')
                """, Timestamp.from(Instant.now()));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM alert", Long.class);

        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() { })
                .get(10, TimeUnit.SECONDS);
        session.subscribe("/topic/alerts", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { received.add((Map<String, Object>) payload); }
        });
        Thread.sleep(500); // let the subscription reach the broker

        // A client cannot broadcast on the topic itself: this spoof must never arrive.
        session.send("/topic/alerts", Map.of("alertId", -1, "action", "SPOOFED"));
        Thread.sleep(500);
        service.acknowledge(id, "tara@test.local");

        Map<String, Object> notice = received.poll(10, TimeUnit.SECONDS);
        session.disconnect();
        assertThat(notice).isNotNull();
        assertThat(((Number) notice.get("alertId")).longValue()).isEqualTo(id);
        assertThat(notice.get("action")).isEqualTo("ALERT_ACKNOWLEDGED");
        assertThat(received).isEmpty();
    }
}
