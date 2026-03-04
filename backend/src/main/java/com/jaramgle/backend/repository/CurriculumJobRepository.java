package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.CurriculumJob;
import com.jaramgle.backend.entity.CurriculumJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CurriculumJobRepository extends JpaRepository<CurriculumJob, Long> {

    Optional<CurriculumJob> findTopByWeekIdAndStatusOrderByQueuedAtDesc(Long weekId, CurriculumJobStatus status);

    Optional<CurriculumJob> findTopByWeekIdOrderByQueuedAtDesc(Long weekId);

    Optional<CurriculumJob> findFirstByCurriculumIdAndStatusOrderByQueuedAtAsc(Long curriculumId, CurriculumJobStatus status);

    boolean existsByCurriculumIdAndStatus(Long curriculumId, CurriculumJobStatus status);

    List<CurriculumJob> findByCurriculumIdAndStatusOrderByQueuedAtAsc(Long curriculumId, CurriculumJobStatus status);

    List<CurriculumJob> findByStatusOrderByQueuedAtAsc(CurriculumJobStatus status);
}
