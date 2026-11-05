package com.team404.corrosion.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row of the team's app_user table (V1). Times are UTC DATETIME(3) columns, mapped as Instant with a plain TIMESTAMP
 * JDBC type: with hibernate.jdbc.time_zone=UTC they are written in UTC whatever the JVM's zone is. (A LocalDateTime
 * would be shifted by the JVM's offset on a developer machine, and Hibernate's default TIMESTAMP_UTC type for Instant
 * fails schema validation against DATETIME.)
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    // VARCHAR(20) with a CHECK constraint, not a MySQL ENUM column
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUser() {
    }

    public AppUser(String name, String email, String passwordHash, Role role, Instant createdAt) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public LoginPolicy.State loginState() {
        return new LoginPolicy.State(failedLogins, lockedUntil);
    }

    public void applyLoginState(LoginPolicy.State state) {
        this.failedLogins = state.failedLogins();
        this.lockedUntil = state.lockedUntil();
    }
}
