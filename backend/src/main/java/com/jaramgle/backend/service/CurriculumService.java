package com.jaramgle.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaramgle.backend.dto.curriculum.CreateCurriculumRequest;
import com.jaramgle.backend.dto.curriculum.CurriculumActionResponse;
import com.jaramgle.backend.dto.curriculum.CurriculumDetailDto;
import com.jaramgle.backend.dto.curriculum.CurriculumJobDto;
import com.jaramgle.backend.dto.curriculum.CurriculumSummaryDto;
import com.jaramgle.backend.dto.curriculum.CurriculumWeekDto;
import com.jaramgle.backend.dto.curriculum.UpdateWeekGoalRequest;
import com.jaramgle.backend.dto.curriculum.WeekGenerationRequest;
import com.jaramgle.backend.entity.Curriculum;
import com.jaramgle.backend.entity.CurriculumGenerationMode;
import com.jaramgle.backend.entity.CurriculumJob;
import com.jaramgle.backend.entity.CurriculumJobStatus;
import com.jaramgle.backend.entity.CurriculumJobType;
import com.jaramgle.backend.entity.CurriculumSeriesMemory;
import com.jaramgle.backend.entity.CurriculumStatus;
import com.jaramgle.backend.entity.CurriculumWeek;
import com.jaramgle.backend.entity.CurriculumWeekStatus;
import com.jaramgle.backend.repository.CurriculumJobRepository;
import com.jaramgle.backend.repository.CurriculumRepository;
import com.jaramgle.backend.repository.CurriculumSeriesMemoryRepository;
import com.jaramgle.backend.repository.CurriculumWeekRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CurriculumService {

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("KO", "EN", "JA", "FR", "ES", "DE", "ZH");
    private static final Set<CurriculumWeekStatus> COMPLETED_WEEK_STATUSES = EnumSet.of(
            CurriculumWeekStatus.SUCCEEDED,
            CurriculumWeekStatus.PARTIAL_SUCCEEDED,
            CurriculumWeekStatus.SKIPPED
    );

    private final CurriculumRepository curriculumRepository;
    private final CurriculumWeekRepository curriculumWeekRepository;
    private final CurriculumJobRepository curriculumJobRepository;
    private final CurriculumSeriesMemoryRepository curriculumSeriesMemoryRepository;
    private final CurriculumJobProcessor curriculumJobProcessor;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<CurriculumSummaryDto> getCurriculums(String userId) {
        List<Curriculum> curriculums = curriculumRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userId);
        List<CurriculumSummaryDto> summaries = new ArrayList<>();
        for (Curriculum curriculum : curriculums) {
            List<CurriculumWeek> weeks = curriculumWeekRepository.findByCurriculumIdOrderByWeekNoAsc(curriculum.getId());
            int completed = (int) weeks.stream()
                    .filter(week -> COMPLETED_WEEK_STATUSES.contains(week.getStatus()))
                    .count();
            Integer nextWeek = resolveNextWeekToGenerate(weeks);
            summaries.add(new CurriculumSummaryDto(
                    curriculum.getId(),
                    curriculum.getTitle(),
                    curriculum.getCategory(),
                    curriculum.getSubTopic(),
                    curriculum.getAgeRange(),
                    curriculum.getBaseLanguage(),
                    curriculum.getTranslationLanguage(),
                    curriculum.getWeeks(),
                    completed,
                    curriculum.getStatus() == null ? CurriculumStatus.DRAFT.name() : curriculum.getStatus().name(),
                    nextWeek,
                    curriculum.getCreatedAt(),
                    curriculum.getUpdatedAt()
            ));
        }
        return summaries;
    }

    @Transactional(readOnly = true)
    public CurriculumDetailDto getCurriculumDetail(String userId, Long curriculumId) {
        Curriculum curriculum = getOwnedCurriculum(curriculumId, userId);
        return toDetailDto(curriculum);
    }

    @Transactional
    public CurriculumDetailDto createCurriculum(String userId, CreateCurriculumRequest request) {
        validateWeeks(request.getWeeks());
        validateLanguage(request.getBaseLanguage());
        validateTranslationLanguage(request.getTranslationLanguage(), request.getBaseLanguage());
        validateCharacterIds(request.getDefaultCharacterIds());

        Map<Integer, CreateCurriculumRequest.WeekGoalRequest> goalsByWeek = normalizeAndValidateGoals(request.getWeekGoals(), request.getWeeks());

        Curriculum curriculum = new Curriculum();
        curriculum.setUserId(userId);
        curriculum.setTitle(request.getTitle().trim());
        curriculum.setWeeks(request.getWeeks());
        curriculum.setCategory(request.getCategory().trim());
        curriculum.setSubTopic(normalizeNullable(request.getSubTopic()));
        curriculum.setAgeRange(normalizeNullable(request.getAgeRange()));
        String normalizedBaseLanguage = normalizeLanguage(request.getBaseLanguage());
        curriculum.setBaseLanguage(normalizedBaseLanguage);
        curriculum.setTranslationLanguage(resolveTranslationLanguage(request.getTranslationLanguage(), normalizedBaseLanguage));
        curriculum.setGenerationMode(parseGenerationMode(request.getGenerationMode()));
        curriculum.setScheduleRule(normalizeNullable(request.getScheduleRule()));
        curriculum.setNextRunAt(request.getNextRunAt());
        curriculum.setStatus(CurriculumStatus.DRAFT);
        curriculum.setDefaultCharacterIdsJson(toJsonOrNull(request.getDefaultCharacterIds()));
        curriculum.setDefaultArtStyle(normalizeNullable(request.getDefaultArtStyle()));
        curriculum.setDefaultVoice(normalizeNullable(request.getDefaultVoice()));
        curriculum.setDeleted(false);

        Curriculum saved = curriculumRepository.save(curriculum);

        for (int weekNo = 1; weekNo <= request.getWeeks(); weekNo++) {
            CreateCurriculumRequest.WeekGoalRequest goal = goalsByWeek.get(weekNo);
            CurriculumWeek week = new CurriculumWeek();
            week.setCurriculum(saved);
            week.setWeekNo(weekNo);
            week.setPrimaryGoal(goal.getPrimaryGoal().trim());
            week.setSubGoalsJson(toJsonOrNull(normalizeSubGoals(goal.getSubGoals())));
            week.setStatus(CurriculumWeekStatus.NOT_STARTED);
            curriculumWeekRepository.save(week);
        }

        CurriculumSeriesMemory memory = new CurriculumSeriesMemory();
        memory.setCurriculum(saved);
        memory.setLastSummary(null);
        memory.setCharacterStateJson("{}");
        memory.setCoveredTopicsJson("[]");
        curriculumSeriesMemoryRepository.save(memory);

        return toDetailDto(saved);
    }

    @Transactional
    public CurriculumActionResponse updateWeekGoal(String userId, Long curriculumId, Integer weekNo, UpdateWeekGoalRequest request) {
        Curriculum curriculum = getOwnedCurriculum(curriculumId, userId);
        CurriculumWeek week = getWeek(curriculumId, weekNo);

        if (!(week.getStatus() == CurriculumWeekStatus.NOT_STARTED || week.getStatus() == CurriculumWeekStatus.PENDING)) {
            throw new IllegalStateException("RUNNING 이상 상태에서는 목표를 수정할 수 없습니다.");
        }

        week.setPrimaryGoal(request.getPrimaryGoal().trim());
        week.setSubGoalsJson(toJsonOrNull(normalizeSubGoals(request.getSubGoals())));

        CurriculumJob newJob = null;
        if (week.getStatus() == CurriculumWeekStatus.PENDING) {
            Optional<CurriculumJob> pendingJobOptional = curriculumJobRepository
                    .findTopByWeekIdAndStatusOrderByQueuedAtDesc(week.getId(), CurriculumJobStatus.PENDING);
            if (pendingJobOptional.isPresent()) {
                CurriculumJob pendingJob = pendingJobOptional.get();
                pendingJob.setStatus(CurriculumJobStatus.CANCELLED);
                pendingJob.setCancelReason("goal_updated");
                pendingJob.setFinishedAt(LocalDateTime.now());
                curriculumJobRepository.save(pendingJob);

                WeekGenerationRequest override = extractOverrideFromSnapshot(pendingJob.getRequestSnapshotJson());
                week.setStatus(CurriculumWeekStatus.NOT_STARTED);
                curriculumWeekRepository.save(week);

                newJob = enqueueWeekJob(
                        curriculum,
                        week,
                        pendingJob.getJobType(),
                        pendingJob.isChargeRequired(),
                        override,
                        pendingJob.getRetryOfJob()
                );
            } else {
                week.setStatus(CurriculumWeekStatus.NOT_STARTED);
                curriculumWeekRepository.save(week);
            }
        } else {
            curriculumWeekRepository.save(week);
        }

        recalculateCurriculumStatus(curriculum.getId());
        if (newJob != null) {
            dispatchAfterCommit(newJob.getId());
        }
        return new CurriculumActionResponse(toWeekDto(week), CurriculumJobDto.fromEntity(newJob));
    }

    @Transactional
    public CurriculumActionResponse requestWeekGenerate(String userId, Long curriculumId, Integer weekNo, WeekGenerationRequest request) {
        Curriculum curriculum = getOwnedCurriculum(curriculumId, userId);
        CurriculumWeek week = getWeek(curriculumId, weekNo);

        if (week.getStatus() != CurriculumWeekStatus.NOT_STARTED) {
            throw new IllegalStateException("생성은 NOT_STARTED 상태에서만 가능합니다.");
        }
        validateSequentialGate(curriculumId, weekNo);

        CurriculumJob job = enqueueWeekJob(curriculum, week, CurriculumJobType.GENERATE, true, request, null);
        recalculateCurriculumStatus(curriculumId);
        dispatchAfterCommit(job.getId());
        return new CurriculumActionResponse(toWeekDto(week), CurriculumJobDto.fromEntity(job));
    }

    @Transactional
    public CurriculumActionResponse requestWeekRetry(String userId, Long curriculumId, Integer weekNo, WeekGenerationRequest request) {
        Curriculum curriculum = getOwnedCurriculum(curriculumId, userId);
        CurriculumWeek week = getWeek(curriculumId, weekNo);

        if (!(week.getStatus() == CurriculumWeekStatus.FAILED || week.getStatus() == CurriculumWeekStatus.FAILED_TIMEOUT)) {
            throw new IllegalStateException("재시도는 FAILED 또는 FAILED_TIMEOUT 상태에서만 가능합니다.");
        }

        boolean chargeRequired = week.isManualRetryUsed();
        if (!week.isManualRetryUsed()) {
            week.setManualRetryUsed(true);
            curriculumWeekRepository.save(week);
        }

        CurriculumJob retryOf = curriculumJobRepository.findTopByWeekIdOrderByQueuedAtDesc(week.getId()).orElse(null);
        CurriculumJob job = enqueueWeekJob(curriculum, week, CurriculumJobType.RETRY, chargeRequired, request, retryOf);
        recalculateCurriculumStatus(curriculumId);
        dispatchAfterCommit(job.getId());
        return new CurriculumActionResponse(toWeekDto(week), CurriculumJobDto.fromEntity(job));
    }

    @Transactional
    public CurriculumActionResponse requestWeekRegenerate(String userId, Long curriculumId, Integer weekNo, WeekGenerationRequest request) {
        Curriculum curriculum = getOwnedCurriculum(curriculumId, userId);
        CurriculumWeek week = getWeek(curriculumId, weekNo);

        if (!(week.getStatus() == CurriculumWeekStatus.SUCCEEDED || week.getStatus() == CurriculumWeekStatus.PARTIAL_SUCCEEDED)) {
            throw new IllegalStateException("재생성은 SUCCEEDED 또는 PARTIAL_SUCCEEDED 상태에서만 가능합니다.");
        }

        CurriculumJob retryOf = curriculumJobRepository.findTopByWeekIdOrderByQueuedAtDesc(week.getId()).orElse(null);
        CurriculumJob job = enqueueWeekJob(curriculum, week, CurriculumJobType.REGENERATE, true, request, retryOf);
        recalculateCurriculumStatus(curriculumId);
        dispatchAfterCommit(job.getId());
        return new CurriculumActionResponse(toWeekDto(week), CurriculumJobDto.fromEntity(job));
    }

    @Transactional
    public CurriculumActionResponse cancelPendingJob(String userId, Long curriculumId, Integer weekNo) {
        getOwnedCurriculum(curriculumId, userId);
        CurriculumWeek week = getWeek(curriculumId, weekNo);

        if (week.getStatus() != CurriculumWeekStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 취소할 수 있습니다.");
        }

        CurriculumJob job = curriculumJobRepository
                .findTopByWeekIdAndStatusOrderByQueuedAtDesc(week.getId(), CurriculumJobStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException("취소할 PENDING job이 없습니다."));

        job.setStatus(CurriculumJobStatus.CANCELLED);
        job.setCancelReason("user_cancelled");
        job.setFinishedAt(LocalDateTime.now());
        curriculumJobRepository.save(job);

        week.setStatus(CurriculumWeekStatus.NOT_STARTED);
        curriculumWeekRepository.save(week);

        recalculateCurriculumStatus(curriculumId);
        return new CurriculumActionResponse(toWeekDto(week), CurriculumJobDto.fromEntity(job));
    }

    @Transactional
    public CurriculumActionResponse adminSkipWeek(Long curriculumId, Integer weekNo, String reason) {
        Curriculum curriculum = curriculumRepository.findById(curriculumId)
                .orElseThrow(() -> new EntityNotFoundException("Curriculum not found"));
        if (curriculum.isDeleted()) {
            throw new EntityNotFoundException("Curriculum not found");
        }

        CurriculumWeek week = getWeek(curriculumId, weekNo);
        if (!(week.getStatus() == CurriculumWeekStatus.NOT_STARTED
                || week.getStatus() == CurriculumWeekStatus.FAILED
                || week.getStatus() == CurriculumWeekStatus.FAILED_TIMEOUT)) {
            throw new IllegalStateException("SKIP은 NOT_STARTED/FAILED/FAILED_TIMEOUT 상태에서만 가능합니다.");
        }

        week.setStatus(CurriculumWeekStatus.SKIPPED);
        week.setSkipReason(StringUtils.hasText(reason) ? reason.trim() : "admin_skipped");
        week.setContinuityStale(false);
        curriculumWeekRepository.save(week);

        recalculateCurriculumStatus(curriculumId);
        return new CurriculumActionResponse(toWeekDto(week), null);
    }

    @Transactional(readOnly = true)
    public CurriculumJobDto getLatestWeekJob(String userId, Long curriculumId, Integer weekNo) {
        getOwnedCurriculum(curriculumId, userId);
        CurriculumWeek week = getWeek(curriculumId, weekNo);
        CurriculumJob latest = curriculumJobRepository.findTopByWeekIdOrderByQueuedAtDesc(week.getId()).orElse(null);
        return CurriculumJobDto.fromEntity(latest);
    }

    @Transactional
    public void recalculateCurriculumStatus(Long curriculumId) {
        Curriculum curriculum = curriculumRepository.findById(curriculumId)
                .orElseThrow(() -> new EntityNotFoundException("Curriculum not found"));
        if (curriculum.isDeleted()) {
            return;
        }

        List<CurriculumWeek> weeks = curriculumWeekRepository.findByCurriculumIdOrderByWeekNoAsc(curriculumId);
        if (weeks.isEmpty() || weeks.stream().allMatch(w -> w.getStatus() == CurriculumWeekStatus.NOT_STARTED)) {
            curriculum.setStatus(CurriculumStatus.DRAFT);
        } else if (weeks.stream().allMatch(w -> COMPLETED_WEEK_STATUSES.contains(w.getStatus()))) {
            curriculum.setStatus(CurriculumStatus.COMPLETED);
        } else {
            curriculum.setStatus(CurriculumStatus.IN_PROGRESS);
        }
        curriculumRepository.save(curriculum);
    }

    private CurriculumJob enqueueWeekJob(
            Curriculum curriculum,
            CurriculumWeek week,
            CurriculumJobType jobType,
            boolean chargeRequired,
            WeekGenerationRequest request,
            CurriculumJob retryOf
    ) {
        validateCharacterIds(request == null ? null : request.getCharacterIds());

        if (week.getStatus() == CurriculumWeekStatus.RUNNING || week.getStatus() == CurriculumWeekStatus.PENDING) {
            throw new IllegalStateException("이미 처리 중인 주차입니다.");
        }

        week.setStatus(CurriculumWeekStatus.PENDING);
        curriculumWeekRepository.save(week);

        CurriculumJob job = new CurriculumJob();
        job.setCurriculum(curriculum);
        job.setWeek(week);
        job.setWeekNo(week.getWeekNo());
        job.setJobType(jobType);
        job.setStatus(CurriculumJobStatus.PENDING);
        job.setChargeRequired(chargeRequired);
        job.setHeartAmount(1);
        job.setRetryOfJob(retryOf);
        job.setRequestSnapshotJson(buildSnapshotJson(curriculum, week, request));
        CurriculumJob saved = curriculumJobRepository.save(job);

        return saved;
    }

    private void validateSequentialGate(Long curriculumId, Integer weekNo) {
        if (weekNo <= 1) {
            return;
        }
        CurriculumWeek prev = curriculumWeekRepository.findByCurriculumIdAndWeekNo(curriculumId, weekNo - 1)
                .orElseThrow(() -> new IllegalStateException("이전 주차를 찾을 수 없습니다."));
        if (!COMPLETED_WEEK_STATUSES.contains(prev.getStatus())) {
            throw new IllegalStateException("이전 주차가 완료되어야 다음 주차를 생성할 수 있습니다.");
        }
    }

    private String buildSnapshotJson(Curriculum curriculum, CurriculumWeek week, WeekGenerationRequest request) {
        WeekGenerationRequest safeRequest = request == null ? new WeekGenerationRequest() : request;
        List<Long> characterIds = (safeRequest.getCharacterIds() != null && !safeRequest.getCharacterIds().isEmpty())
                ? safeRequest.getCharacterIds()
                : parseLongList(curriculum.getDefaultCharacterIdsJson());

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("primaryGoal", week.getPrimaryGoal());
        snapshot.put("subGoals", parseStringList(week.getSubGoalsJson()));
        snapshot.put("category", curriculum.getCategory());
        snapshot.put("subTopic", curriculum.getSubTopic());
        snapshot.put("ageRange", curriculum.getAgeRange());
        snapshot.put("baseLanguage", curriculum.getBaseLanguage());
        snapshot.put("translationLanguage", curriculum.getTranslationLanguage());
        snapshot.put("characterIds", characterIds);
        snapshot.put("artStyle", StringUtils.hasText(safeRequest.getArtStyle())
                ? safeRequest.getArtStyle().trim()
                : curriculum.getDefaultArtStyle());
        snapshot.put("voicePreset", StringUtils.hasText(safeRequest.getVoicePreset())
                ? safeRequest.getVoicePreset().trim()
                : curriculum.getDefaultVoice());

        return toJsonOrNull(snapshot);
    }

    private WeekGenerationRequest extractOverrideFromSnapshot(String snapshotJson) {
        WeekGenerationRequest request = new WeekGenerationRequest();
        if (!StringUtils.hasText(snapshotJson)) {
            return request;
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(snapshotJson, new TypeReference<>() {});
            request.setArtStyle(asString(snapshot.get("artStyle")));
            request.setVoicePreset(asString(snapshot.get("voicePreset")));
            Object value = snapshot.get("characterIds");
            if (value instanceof List<?> list) {
                List<Long> ids = list.stream()
                        .filter(item -> item != null)
                        .map(item -> Long.parseLong(String.valueOf(item)))
                        .toList();
                request.setCharacterIds(ids);
            }
        } catch (Exception ex) {
            log.warn("Failed to parse snapshot for override: {}", ex.getMessage());
        }
        return request;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private CurriculumDetailDto toDetailDto(Curriculum curriculum) {
        List<CurriculumWeek> weeks = curriculumWeekRepository.findByCurriculumIdOrderByWeekNoAsc(curriculum.getId());
        List<CurriculumWeekDto> weekDtos = weeks.stream()
                .sorted(Comparator.comparing(CurriculumWeek::getWeekNo))
                .map(this::toWeekDto)
                .toList();

        return new CurriculumDetailDto(
                curriculum.getId(),
                curriculum.getTitle(),
                curriculum.getCategory(),
                curriculum.getSubTopic(),
                curriculum.getAgeRange(),
                curriculum.getBaseLanguage(),
                curriculum.getTranslationLanguage(),
                curriculum.getWeeks(),
                curriculum.getGenerationMode() == null ? CurriculumGenerationMode.ON_DEMAND.name() : curriculum.getGenerationMode().name(),
                curriculum.getScheduleRule(),
                curriculum.getNextRunAt(),
                curriculum.getStatus() == null ? CurriculumStatus.DRAFT.name() : curriculum.getStatus().name(),
                curriculum.getDefaultArtStyle(),
                curriculum.getDefaultVoice(),
                parseLongList(curriculum.getDefaultCharacterIdsJson()),
                curriculum.isBaseLanguageLocked(),
                curriculum.getCreatedAt(),
                curriculum.getUpdatedAt(),
                weekDtos
        );
    }

    private CurriculumWeekDto toWeekDto(CurriculumWeek week) {
        CurriculumJob latest = curriculumJobRepository.findTopByWeekIdOrderByQueuedAtDesc(week.getId()).orElse(null);
        return new CurriculumWeekDto(
                week.getId(),
                week.getWeekNo(),
                week.getPrimaryGoal(),
                parseStringList(week.getSubGoalsJson()),
                week.getStatus() == null ? CurriculumWeekStatus.NOT_STARTED.name() : week.getStatus().name(),
                week.getStory() == null ? null : week.getStory().getId(),
                week.getCurrentVersionNo(),
                week.isContinuityStale(),
                week.isAutoRetryUsed(),
                week.isManualRetryUsed(),
                week.getSkipReason(),
                week.getCreatedAt(),
                week.getUpdatedAt(),
                CurriculumJobDto.fromEntity(latest)
        );
    }

    private Integer resolveNextWeekToGenerate(List<CurriculumWeek> weeks) {
        boolean chainSatisfied = true;
        for (CurriculumWeek week : weeks.stream().sorted(Comparator.comparing(CurriculumWeek::getWeekNo)).toList()) {
            if (week.getStatus() == CurriculumWeekStatus.PENDING || week.getStatus() == CurriculumWeekStatus.RUNNING) {
                return week.getWeekNo();
            }
            if (COMPLETED_WEEK_STATUSES.contains(week.getStatus())) {
                continue;
            }
            if (chainSatisfied && (week.getStatus() == CurriculumWeekStatus.NOT_STARTED
                    || week.getStatus() == CurriculumWeekStatus.FAILED
                    || week.getStatus() == CurriculumWeekStatus.FAILED_TIMEOUT)) {
                return week.getWeekNo();
            }
            chainSatisfied = false;
        }
        return null;
    }

    private Curriculum getOwnedCurriculum(Long curriculumId, String userId) {
        return curriculumRepository.findByIdAndUserIdAndDeletedFalse(curriculumId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Curriculum not found"));
    }

    private CurriculumWeek getWeek(Long curriculumId, Integer weekNo) {
        return curriculumWeekRepository.findByCurriculumIdAndWeekNo(curriculumId, weekNo)
                .orElseThrow(() -> new EntityNotFoundException("Week not found"));
    }

    private void validateWeeks(Integer weeks) {
        if (weeks == null || (weeks != 2 && weeks != 4)) {
            throw new IllegalArgumentException("기간은 2주 또는 4주만 지원합니다.");
        }
    }

    private void validateLanguage(String baseLanguage) {
        if (!StringUtils.hasText(baseLanguage)) {
            throw new IllegalArgumentException("baseLanguage는 필수입니다.");
        }
        normalizeLanguage(baseLanguage);
    }

    private String normalizeLanguage(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            throw new IllegalArgumentException("지원하지 않는 언어 코드입니다: " + normalized);
        }
        return normalized;
    }

    private void validateTranslationLanguage(String translationLanguage, String sourceLanguage) {
        resolveTranslationLanguage(translationLanguage, sourceLanguage);
    }

    private String resolveTranslationLanguage(String translationLanguage, String sourceLanguage) {
        if (!StringUtils.hasText(translationLanguage)) {
            return null;
        }
        String normalized = translationLanguage.trim().toUpperCase(Locale.ROOT);
        if ("NONE".equals(normalized)) {
            return null;
        }
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            throw new IllegalArgumentException("지원하지 않는 번역 언어 코드입니다: " + normalized);
        }
        String sourceNormalized = normalizeLanguage(sourceLanguage);
        if (normalized.equals(sourceNormalized)) {
            return null;
        }
        return normalized;
    }

    private void validateCharacterIds(List<Long> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return;
        }
        if (characterIds.size() > 2) {
            throw new IllegalArgumentException("커리큘럼 기본 캐릭터는 최대 2개까지 선택할 수 있습니다.");
        }
    }

    private Map<Integer, CreateCurriculumRequest.WeekGoalRequest> normalizeAndValidateGoals(
            List<CreateCurriculumRequest.WeekGoalRequest> goals,
            int weeks
    ) {
        if (goals == null || goals.isEmpty()) {
            throw new IllegalArgumentException("주차 목표는 최소 1개 이상 필요합니다.");
        }

        Map<Integer, CreateCurriculumRequest.WeekGoalRequest> byWeek = new HashMap<>();
        for (CreateCurriculumRequest.WeekGoalRequest goal : goals) {
            if (goal == null || goal.getWeekNo() == null) {
                throw new IllegalArgumentException("주차 목표 형식이 올바르지 않습니다.");
            }
            if (goal.getWeekNo() < 1 || goal.getWeekNo() > weeks) {
                throw new IllegalArgumentException("weekNo는 기간 범위 내에서만 지정할 수 있습니다.");
            }
            if (!StringUtils.hasText(goal.getPrimaryGoal())) {
                throw new IllegalArgumentException("primaryGoal은 필수입니다.");
            }
            if (goal.getSubGoals() != null && goal.getSubGoals().size() > 2) {
                throw new IllegalArgumentException("subGoals는 최대 2개까지 허용됩니다.");
            }
            if (byWeek.put(goal.getWeekNo(), goal) != null) {
                throw new IllegalArgumentException("중복된 weekNo가 있습니다: " + goal.getWeekNo());
            }
        }

        for (int weekNo = 1; weekNo <= weeks; weekNo++) {
            if (!byWeek.containsKey(weekNo)) {
                throw new IllegalArgumentException("weekNo " + weekNo + " 목표가 누락되었습니다.");
            }
        }
        return byWeek;
    }

    private CurriculumGenerationMode parseGenerationMode(String value) {
        if (!StringUtils.hasText(value)) {
            return CurriculumGenerationMode.ON_DEMAND;
        }
        try {
            return CurriculumGenerationMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("generationMode는 ON_DEMAND 또는 SCHEDULED만 지원합니다.");
        }
    }

    private String normalizeNullable(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> normalizeSubGoals(List<String> subGoals) {
        if (subGoals == null || subGoals.isEmpty()) {
            return List.of();
        }
        List<String> normalized = subGoals.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .limit(2)
                .collect(Collectors.toList());
        return normalized;
    }

    private String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> collection && collection.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 직렬화에 실패했습니다.", ex);
        }
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<>() {});
            if (list == null) {
                return List.of();
            }
            return list.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .filter(text -> !text.isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Long> parseLongList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<Long> list = objectMapper.readValue(json, new TypeReference<>() {});
            if (list == null) {
                return List.of();
            }
            return list.stream()
                    .filter(item -> item != null)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            return List.of();
        }
    }

    private void dispatchAfterCommit(Long jobId) {
        if (jobId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    curriculumJobProcessor.dispatch(jobId);
                }
            });
            return;
        }
        curriculumJobProcessor.dispatch(jobId);
    }
}
