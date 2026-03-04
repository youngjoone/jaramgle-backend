package com.jaramgle.backend.repository;

import com.jaramgle.backend.entity.CurriculumJobLedger;
import com.jaramgle.backend.entity.CurriculumLedgerActionType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CurriculumJobLedgerRepository extends JpaRepository<CurriculumJobLedger, Long> {

    boolean existsByJobIdAndActionType(Long jobId, CurriculumLedgerActionType actionType);
}
