package com.team404.corrosion.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless API secured by the shared JWT (docs/CONTRACTS.md). Role rules:
 * technicians and admins act on alerts; managers and admins see reports and the audit log; only admins use
 * /api/v1/admin/**. Every other /api/v1 path needs a valid token, and anything else is closed.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter converter, ApiErrors errors,
                            ObjectMapper json) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, e) -> {
            String message = request.getHeader(HttpHeaders.AUTHORIZATION) == null
                    ? "Sign in to continue."
                    : "Your session is invalid or has expired. Sign in again.";
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(response, json, errors.body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message, Map.of()));
        };
        AccessDeniedHandler forbidden = (request, response, e) -> write(response, json,
                errors.body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role is not allowed to do this.", Map.of()));

        http.csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/reports/**", "/api/v1/audit/**").hasAnyRole("MANAGER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/alerts/**").hasAnyRole("TECHNICIAN", "ADMIN")
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o
                .jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(unauthorized)
                .accessDeniedHandler(forbidden))
            .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
        return http.build();
    }

    private static void write(HttpServletResponse response, ObjectMapper json, Map<String, Object> body)
            throws IOException {
        response.setStatus((Integer) body.get("status"));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }
}
