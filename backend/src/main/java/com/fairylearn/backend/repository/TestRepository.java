package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.Test;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TestRepository extends JpaRepository<Test, String> {
    Optional<Test> findByCode(String code);
}