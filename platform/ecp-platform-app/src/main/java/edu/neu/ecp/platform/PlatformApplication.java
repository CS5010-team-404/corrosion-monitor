package edu.neu.ecp.platform;

import java.time.Clock;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The Corrosion Watch data platform as its own service: device ingestion, the simulator, sensor health and the
 * forecast API. It shares the MySQL database with the team backend and talks to it only through the shared
 * tables (readings, sensor_health, alert_signals, audit_outbox), as the proposal's architecture describes.
 */
@SpringBootApplication(scanBasePackages = "edu.neu.ecp")
@EnableScheduling
public class PlatformApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(PlatformApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
