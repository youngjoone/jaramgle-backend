package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.CurriculumEpisodeVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CurriculumEpisodeVersionRepository extends JpaRepository<CurriculumEpisodeVersion, Long> {

    List<CurriculumEpisodeVersion> findByWeekIdOrderByVersionNoDesc(Long weekId);

    Optional<CurriculumEpisodeVersion> findTopByWeekIdOrderByVersionNoDesc(Long weekId);
}
