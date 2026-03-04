package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.Curriculum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CurriculumRepository extends JpaRepository<Curriculum, Long> {

    Optional<Curriculum> findByIdAndUserIdAndDeletedFalse(Long id, String userId);

    List<Curriculum> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(String userId);
}
