package com.team404.corrosion.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** Row-locked, so two concurrent logins cannot both read the same failure count. Email collation is case-insensitive. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AppUser> findForLoginByEmail(String email);

    Optional<AppUser> findByEmail(String email);
}
