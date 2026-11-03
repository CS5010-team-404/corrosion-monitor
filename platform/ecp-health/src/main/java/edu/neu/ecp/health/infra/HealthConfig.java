package edu.neu.ecp.health.infra;

import edu.neu.ecp.health.domain.HealthEvaluator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HealthProperties.class)
public class HealthConfig {

    @Bean
    HealthEvaluator healthEvaluator(HealthProperties properties) {
        return new HealthEvaluator(properties.toPolicy());
    }
}
