package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.User;
import com.jaramgle.backend.entity.UserStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByProviderAndProviderId(String provider, String providerId);
    Optional<User> findByIdAndDeletedFalse(Long id);

    @Query("""
        SELECT u FROM User u
        WHERE (:status IS NULL OR u.status = :status)
          AND (:deleted IS NULL OR u.deleted = :deleted)
          AND (
            :query IS NULL OR :query = '' OR
            LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR
            LOWER(u.name) LIKE LOWER(CONCAT('%', :query, '%'))
          )
        ORDER BY u.createdAt DESC
        """)
    Page<User> searchForAdmin(@Param("status") UserStatus status,
                              @Param("deleted") Boolean deleted,
                              @Param("query") String query,
                              Pageable pageable);
}
