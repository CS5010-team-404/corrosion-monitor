package com.team404.corrosion.auth;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** Row-locked, so two concurrent logins cannot both read the same failure count. Email collation is case-insensitive. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AppUser> findForLoginByEmail(String email);

    Optional<AppUser> findByEmail(String email);

    /** Row-locked for an admin change (unlock, role). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AppUser> findForUpdateById(Long id);

    /**
     * Locks every account holding a role, in id order. Taken before a role change so two administrators demoting each
     * other at the same moment are serialised and the second one sees there is no other admin left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<AppUser> findForUpdateByRoleOrderById(Role role);

    List<AppUser> findAllByOrderByNameAscIdAsc();
}
