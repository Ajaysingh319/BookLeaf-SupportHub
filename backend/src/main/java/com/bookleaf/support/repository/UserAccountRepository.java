package com.bookleaf.support.repository;

import com.bookleaf.support.domain.Role;
import com.bookleaf.support.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findBySourceAuthorId(String sourceAuthorId);

    Optional<UserAccount> findByEmail(String email);

    List<UserAccount> findByRoleOrderByNameAsc(Role role);
}
