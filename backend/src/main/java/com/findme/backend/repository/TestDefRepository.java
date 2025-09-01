package com.findme.backend.repository;

import com.findme.backend.entity.TestDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestDefRepository extends JpaRepository<TestDef, Long> {
    Optional<TestDef> findByCodeAndVersion(String code, int version);
    List<TestDef> findByCodeOrderByVersionDesc(String code);
    Optional<TestDef> findByCodeAndStatus(String code, String status);
    List<TestDef> findByCodeAndStatusOrderByVersionDesc(String code, String status);
}
