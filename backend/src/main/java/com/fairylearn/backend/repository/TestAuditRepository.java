package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.TestAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestAuditRepository extends JpaRepository<TestAudit, Long> {
}
