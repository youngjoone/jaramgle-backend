package com.findme.backend.repository;

import com.findme.backend.entity.TestAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestAuditRepository extends JpaRepository<TestAudit, Long> {
}
