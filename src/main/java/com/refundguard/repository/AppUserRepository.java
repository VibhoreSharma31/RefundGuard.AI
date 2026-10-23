package com.refundguard.repository;

import com.refundguard.model.AppUser;
import com.refundguard.security.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    long countByRoleAndEnabled(Role role, Boolean enabled);
}
