package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.CurriculumSeriesMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CurriculumSeriesMemoryRepository extends JpaRepository<CurriculumSeriesMemory, Long> {

    Optional<CurriculumSeriesMemory> findByCurriculumId(Long curriculumId);
}
