package edu.neu.ecp.platform.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** The React app is a separate Render static site, so its origin must be allowed explicitly. */
@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${FRONTEND_HOST:}") String frontendHost,
                                                    @Value("${ecp.cors.extra-origins:http://localhost:5173}") List<String> extra) {
        List<String> origins = new ArrayList<>(extra);
        if (!frontendHost.isBlank()) {
            origins.add(frontendHost.startsWith("http") ? frontendHost : "https://" + frontendHost);
        }
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }
}
