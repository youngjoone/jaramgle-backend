package com.jaramgle.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaramgle.backend.dto.StableStoryPageDto;
import com.jaramgle.backend.dto.StoryGenerateRequest;
import com.jaramgle.backend.entity.Character;
import com.jaramgle.backend.entity.Curriculum;
import com.jaramgle.backend.entity.CurriculumEpisodeVersion;
import com.jaramgle.backend.entity.CurriculumJob;
import com.jaramgle.backend.entity.CurriculumJobLedger;
import com.jaramgle.backend.entity.CurriculumJobStatus;
import com.jaramgle.backend.entity.CurriculumJobType;
import com.jaramgle.backend.entity.CurriculumLedgerActionType;
import com.jaramgle.backend.entity.CurriculumSeriesMemory;
import com.jaramgle.backend.entity.CurriculumStatus;
import com.jaramgle.backend.entity.CurriculumWeek;
import com.jaramgle.backend.entity.CurriculumWeekStatus;
import com.jaramgle.backend.entity.HeartTransaction;
import com.jaramgle.backend.entity.Story;
import com.jaramgle.backend.entity.StoryPage;
import com.jaramgle.backend.entity.StorybookPage;
import com.jaramgle.backend.repository.CurriculumEpisodeVersionRepository;
import com.jaramgle.backend.repository.CurriculumJobLedgerRepository;
import com.jaramgle.backend.repository.CurriculumJobRepository;
import com.jaramgle.backend.repository.CurriculumRepository;
import com.jaramgle.backend.repository.CurriculumSeriesMemoryRepository;
import com.jaramgle.backend.repository.CurriculumWeekRepository;
import com.jaramgle.backend.repository.StoryPageRepository;
import com.jaramgle.backend.repository.StorybookPageRepository;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CurriculumJobProcessor {

    private static final int HEART_COST = 1;
    private static final int JOB_TIMEOUT_MINUTES = 10;
    private static final long AUTO_RETRY_BACKOFF_MILLIS = 30_000L;

    private static final Set<CurriculumWeekStatus> COMPLETED_WEEK_STATUSES = EnumSet.of(
            CurriculumWeekStatus.SUCCEEDED,
            CurriculumWeekStatus.PARTIAL_SUCCEEDED,
            CurriculumWeekStatus.SKIPPED
    );

    private final CurriculumJobRepository curriculumJobRepository;
    private final CurriculumWeekRepository curriculumWeekRepository;
    private final CurriculumRepository curriculumRepository;
    private final CurriculumSeriesMemoryRepository curriculumSeriesMemoryRepository;
    private final CurriculumEpisodeVersionRepository curriculumEpisodeVersionRepository;
    private final CurriculumJobLedgerRepository curriculumJobLedgerRepository;
    private final StoryService storyService;
    private final StorybookService storybookService;
    private final StoryPageRepository storyPageRepository;
    private final StorybookPageRepository storybookPageRepository;
    private final HeartWalletService heartWalletService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<CurriculumJobProcessor> selfProvider;

    private final ExecutorService dispatchExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    private final ExecutorService generationExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    private final Map<Long, Object> curriculumLocks = new ConcurrentHashMap<>();

    public void dispatch(Long jobId) {
        if (jobId == null) {
            return;
        }
        dispatchExecutor.submit(() -> processJob(jobId));
    }

    @PreDestroy
    void shutdownExecutors() {
        dispatchExecutor.shutdown();
        generationExecutor.shutdown();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        tx().recoverRunningJobsOnStartup();
        tx().dispatchPendingOnStartup();
    }

    private void processJob(Long jobId) {
        Long curriculumId = null;
        Long autoRetryJobId = null;
        boolean started = false;
        try {
            CurriculumJob snapshot = curriculumJobRepository.findById(jobId).orElse(null);
            if (snapshot == null) {
                return;
            }
            curriculumId = snapshot.getCurriculum().getId();
            Object lock = curriculumLocks.computeIfAbsent(curriculumId, ignored -> new Object());

            synchronized (lock) {
                started = tx().startJob(jobId);
                if (!started) {
                    return;
                }
            }

            tx().ensureCharge(jobId);

            Future<GenerationExecutionResult> future = generationExecutor.submit(() -> executeGeneration(jobId));
            GenerationExecutionResult result;
            try {
                result = future.get(JOB_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (TimeoutException timeoutException) {
                future.cancel(true);
                throw timeoutException;
            }

            tx().finishSuccess(jobId, result);
        } catch (TimeoutException timeoutException) {
            autoRetryJobId = tx().finishFailure(jobId, true, "TIMEOUT", "주차 생성 타임아웃");
        } catch (Exception ex) {
            log.error("Curriculum job {} failed", jobId, ex);
            autoRetryJobId = tx().finishFailure(jobId, false, "GENERATION_FAILED", summarizeError(ex));
        } catch (Throwable throwable) {
            log.error("Curriculum job {} fatal failure", jobId, throwable);
            autoRetryJobId = tx().finishFailure(jobId, false, "FATAL_ERROR", summarizeError(throwable));
        } finally {
            if (curriculumId != null) {
                dispatchNextPending(curriculumId);
            }
        }

        if (autoRetryJobId != null) {
            sleepBackoff();
            dispatch(autoRetryJobId);
        }
    }

    private CurriculumJobProcessor tx() {
        return selfProvider.getObject();
    }

    @Transactional
    public void dispatchPendingOnStartup() {
        List<CurriculumJob> pendingJobs = curriculumJobRepository.findByStatusOrderByQueuedAtAsc(CurriculumJobStatus.PENDING);
        Set<Long> seenCurriculum = new HashSet<>();
        for (CurriculumJob job : pendingJobs) {
            Long curriculumId = job.getCurriculum().getId();
            if (seenCurriculum.add(curriculumId)) {
                dispatch(job.getId());
            }
        }
    }

    @Transactional
    public void recoverRunningJobsOnStartup() {
        List<CurriculumJob> runningJobs = curriculumJobRepository.findByStatusOrderByQueuedAtAsc(CurriculumJobStatus.RUNNING);
        if (runningJobs.isEmpty()) {
            return;
        }
        for (CurriculumJob runningJob : runningJobs) {
            tx().finishFailure(
                    runningJob.getId(),
                    true,
                    "RECOVERED_ON_RESTART",
                    "앱 재기동으로 중단된 작업을 타임아웃 처리했습니다."
            );
        }
    }

    @Transactional
    protected boolean startJob(Long jobId) {
        CurriculumJob job = curriculumJobRepository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Job not found"));
        if (job.getStatus() != CurriculumJobStatus.PENDING) {
            return false;
        }

        Long curriculumId = job.getCurriculum().getId();
        if (curriculumJobRepository.existsByCurriculumIdAndStatus(curriculumId, CurriculumJobStatus.RUNNING)) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        job.setStatus(CurriculumJobStatus.RUNNING);
        job.setStartedAt(now);
        job.setTimeoutAt(now.plusMinutes(JOB_TIMEOUT_MINUTES));
        curriculumJobRepository.save(job);

        CurriculumWeek week = job.getWeek();
        week.setStatus(CurriculumWeekStatus.RUNNING);
        curriculumWeekRepository.save(week);

        recalculateCurriculumStatus(curriculumId);
        return true;
    }

    @Transactional
    protected void ensureCharge(Long jobId) {
        CurriculumJob job = curriculumJobRepository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Job not found"));

        if (!job.isChargeRequired() || job.isCharged()) {
            return;
        }

        if (curriculumJobLedgerRepository.existsByJobIdAndActionType(jobId, CurriculumLedgerActionType.CHARGE)) {
            job.setCharged(true);
            curriculumJobRepository.save(job);
            return;
        }

        Long numericUserId = parseNumericUserId(job.getCurriculum().getUserId());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("context", "curriculum_week_generation");
        metadata.put("curriculumId", job.getCurriculum().getId());
        metadata.put("weekNo", job.getWeekNo());
        metadata.put("jobId", job.getId());
        metadata.put("jobType", job.getJobType().name());

        HeartTransaction tx = heartWalletService.spendHearts(
                numericUserId,
                job.getHeartAmount() == null ? HEART_COST : job.getHeartAmount(),
                "커리큘럼 주차 생성",
                metadata
        );

        CurriculumJobLedger ledger = new CurriculumJobLedger();
        ledger.setJob(job);
        ledger.setActionType(CurriculumLedgerActionType.CHARGE);
        ledger.setHeartTransaction(tx);
        try {
            curriculumJobLedgerRepository.save(ledger);
        } catch (DataIntegrityViolationException duplicated) {
            log.warn("Duplicated charge ledger jobId={}", jobId);
        }

        job.setCharged(true);
        curriculumJobRepository.save(job);
    }

    private GenerationExecutionResult executeGeneration(Long jobId) {
        CurriculumJob job = curriculumJobRepository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Job not found"));
        Long curriculumId = job.getCurriculum().getId();
        Long weekId = job.getWeek().getId();
        Curriculum curriculum = curriculumRepository.findById(curriculumId)
                .orElseThrow(() -> new EntityNotFoundException("Curriculum not found"));
        CurriculumWeek week = curriculumWeekRepository.findById(weekId)
                .orElseThrow(() -> new EntityNotFoundException("Curriculum week not found"));

        GenerationSnapshot snapshot = parseSnapshot(job.getRequestSnapshotJson());
        StoryGenerateRequest request = buildStoryRequest(curriculum, week, snapshot);

        StoryService.GenerationResult generationResult = storyService.generateAiStory(request);
        Story story = storyService.saveGeneratedStoryForCurriculum(
                curriculum.getUserId(),
                request,
                generationResult.story(),
                generationResult.concept(),
                generationResult.translation()
        );

        StorybookService.CurriculumStorybookResult storybookResult = storybookService
                .createStorybookForCurriculum(story.getId(), snapshot.voicePreset());

        List<StoryPage> storyPages = storyPageRepository.findByStoryIdOrderByPageNoAsc(story.getId());
        String storyText = storyPages.stream()
                .sorted(Comparator.comparing(StoryPage::getPageNo))
                .map(StoryPage::getText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n"));

        String lastSummary = summarize(storyText, 320);
        String characterStateJson = buildCharacterStateJson(story, week.getWeekNo());
        String coveredTopicsJson = buildCoveredTopicsJson(snapshot);
        String assetRefsJson = buildAssetRefsJson(storybookResult.pages());

        return new GenerationExecutionResult(
                story,
                storyText,
                assetRefsJson,
                lastSummary,
                characterStateJson,
                coveredTopicsJson,
                storybookResult.audioFailed()
        );
    }

    private StoryGenerateRequest buildStoryRequest(Curriculum curriculum, CurriculumWeek week, GenerationSnapshot snapshot) {
        StoryGenerateRequest request = new StoryGenerateRequest();
        request.setTitle(buildEpisodeTitle(curriculum, week.getWeekNo()));
        request.setAgeRange(StringUtils.hasText(snapshot.ageRange()) ? snapshot.ageRange() : normalizeNullable(curriculum.getAgeRange()));

        List<String> topics = new ArrayList<>();
        if (StringUtils.hasText(snapshot.category())) {
            topics.add(snapshot.category());
        }
        if (StringUtils.hasText(snapshot.subTopic())) {
            topics.add(snapshot.subTopic());
        }
        if (StringUtils.hasText(snapshot.primaryGoal())) {
            topics.add(snapshot.primaryGoal());
        }
        if (topics.isEmpty()) {
            topics.add(curriculum.getCategory());
        }
        request.setTopics(topics.stream().filter(StringUtils::hasText).map(String::trim).toList());

        List<String> objectives = new ArrayList<>();
        if (StringUtils.hasText(snapshot.primaryGoal())) {
            objectives.add("이번 주 학습 목표: " + snapshot.primaryGoal());
        }
        objectives.addAll(snapshot.subGoals());

        CurriculumSeriesMemory memory = curriculumSeriesMemoryRepository.findByCurriculumId(curriculum.getId()).orElse(null);
        if (memory != null) {
            if (StringUtils.hasText(memory.getLastSummary())) {
                objectives.add("이전 이야기 요약: " + summarize(memory.getLastSummary(), 250));
            }
            List<String> coveredTopics = parseStringList(memory.getCoveredTopicsJson());
            if (!coveredTopics.isEmpty()) {
                objectives.add("이미 다룬 학습 포인트를 반복 설명하지 말고 확장하세요: "
                        + String.join(", ", coveredTopics));
            }
            if (StringUtils.hasText(memory.getCharacterStateJson())
                    && !"{}".equals(memory.getCharacterStateJson().trim())) {
                objectives.add("캐릭터/세계관 상태를 유지하세요: " + summarize(memory.getCharacterStateJson(), 220));
            }
        }
        request.setObjectives(objectives);

        request.setMinPages(10);
        request.setLanguage(resolveLanguage(snapshot.baseLanguage(), curriculum.getBaseLanguage()));

        List<Long> characterIds = snapshot.characterIds();
        if (characterIds == null || characterIds.isEmpty()) {
            characterIds = parseLongList(curriculum.getDefaultCharacterIdsJson());
        }
        request.setCharacterIds(characterIds);

        request.setMoral(snapshot.primaryGoal());
        request.setRequiredElements(snapshot.subGoals());

        String artStyle = StringUtils.hasText(snapshot.artStyle()) ? snapshot.artStyle() : curriculum.getDefaultArtStyle();
        request.setArtStyle(artStyle);
        request.setTranslationLanguage(resolveTranslationLanguage(
                snapshot.translationLanguage(),
                curriculum.getTranslationLanguage(),
                request.getLanguage()
        ));

        return request;
    }

    @Transactional
    protected void finishSuccess(Long jobId, GenerationExecutionResult result) {
        CurriculumJob job = curriculumJobRepository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Job not found"));
        if (job.getStatus() != CurriculumJobStatus.RUNNING) {
            return;
        }

        CurriculumWeek week = job.getWeek();
        Curriculum curriculum = job.getCurriculum();

        CurriculumWeekStatus weekStatus = result.audioFailed()
                ? CurriculumWeekStatus.PARTIAL_SUCCEEDED
                : CurriculumWeekStatus.SUCCEEDED;
        CurriculumJobStatus jobStatus = result.audioFailed()
                ? CurriculumJobStatus.PARTIAL_SUCCEEDED
                : CurriculumJobStatus.SUCCEEDED;

        job.setStatus(jobStatus);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setFinishedAt(LocalDateTime.now());
        curriculumJobRepository.save(job);

        week.setStatus(weekStatus);
        week.setStory(result.story());
        week.setCurrentVersionNo((week.getCurrentVersionNo() == null ? 0 : week.getCurrentVersionNo()) + 1);
        week.setContinuityStale(false);
        week.setSkipReason(null);
        curriculumWeekRepository.save(week);

        CurriculumEpisodeVersion version = new CurriculumEpisodeVersion();
        version.setWeek(week);
        version.setStory(result.story());
        version.setVersionNo(week.getCurrentVersionNo());
        version.setWeekStatus(weekStatus.name());
        version.setStoryText(result.storyText());
        version.setAssetRefsJson(result.assetRefsJson());
        curriculumEpisodeVersionRepository.save(version);

        CurriculumSeriesMemory memory = curriculumSeriesMemoryRepository.findByCurriculumId(curriculum.getId())
                .orElseGet(() -> {
                    CurriculumSeriesMemory created = new CurriculumSeriesMemory();
                    created.setCurriculum(curriculum);
                    created.setCharacterStateJson("{}");
                    created.setCoveredTopicsJson("[]");
                    return created;
                });
        memory.setLastSummary(result.lastSummary());
        memory.setCharacterStateJson(result.characterStateJson());
        memory.setCoveredTopicsJson(result.coveredTopicsJson());
        curriculumSeriesMemoryRepository.save(memory);

        if (job.getJobType() == CurriculumJobType.REGENERATE) {
            List<CurriculumWeek> laterWeeks = curriculumWeekRepository
                    .findByCurriculumIdAndWeekNoGreaterThanOrderByWeekNoAsc(curriculum.getId(), week.getWeekNo());
            for (CurriculumWeek laterWeek : laterWeeks) {
                laterWeek.setContinuityStale(true);
            }
            if (!laterWeeks.isEmpty()) {
                curriculumWeekRepository.saveAll(laterWeeks);
            }
        }

        if (!curriculum.isBaseLanguageLocked() && week.getWeekNo() == 1) {
            curriculum.setBaseLanguageLocked(true);
        }
        curriculumRepository.save(curriculum);

        recalculateCurriculumStatus(curriculum.getId());
    }

    @Transactional
    protected Long finishFailure(Long jobId, boolean timeout, String errorCode, String errorMessage) {
        CurriculumJob job = curriculumJobRepository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Job not found"));
        if (job.getStatus() != CurriculumJobStatus.RUNNING) {
            return null;
        }

        CurriculumWeek week = job.getWeek();
        Curriculum curriculum = job.getCurriculum();

        CurriculumJobStatus failedJobStatus = timeout ? CurriculumJobStatus.FAILED_TIMEOUT : CurriculumJobStatus.FAILED;
        CurriculumWeekStatus failedWeekStatus = timeout ? CurriculumWeekStatus.FAILED_TIMEOUT : CurriculumWeekStatus.FAILED;

        job.setStatus(failedJobStatus);
        job.setErrorCode(errorCode);
        job.setErrorMessage(errorMessage);
        job.setFinishedAt(LocalDateTime.now());
        curriculumJobRepository.save(job);

        week.setStatus(failedWeekStatus);
        curriculumWeekRepository.save(week);

        refundIfNeeded(job);

        Long autoRetryJobId = null;
        if (!week.isAutoRetryUsed()) {
            week.setAutoRetryUsed(true);
            week.setStatus(CurriculumWeekStatus.PENDING);
            curriculumWeekRepository.save(week);

            CurriculumJob autoRetry = new CurriculumJob();
            autoRetry.setCurriculum(curriculum);
            autoRetry.setWeek(week);
            autoRetry.setWeekNo(week.getWeekNo());
            autoRetry.setJobType(CurriculumJobType.RETRY);
            autoRetry.setStatus(CurriculumJobStatus.PENDING);
            autoRetry.setChargeRequired(false);
            autoRetry.setHeartAmount(1);
            autoRetry.setRetryOfJob(job);
            autoRetry.setRequestSnapshotJson(job.getRequestSnapshotJson());
            autoRetry = curriculumJobRepository.save(autoRetry);
            autoRetryJobId = autoRetry.getId();
        }

        recalculateCurriculumStatus(curriculum.getId());
        return autoRetryJobId;
    }

    @Transactional
    protected void refundIfNeeded(CurriculumJob job) {
        if (!job.isChargeRequired() || !job.isCharged() || job.isRefunded()) {
            return;
        }

        Long jobId = job.getId();
        if (curriculumJobLedgerRepository.existsByJobIdAndActionType(jobId, CurriculumLedgerActionType.REFUND)) {
            job.setRefunded(true);
            curriculumJobRepository.save(job);
            return;
        }

        Long numericUserId = parseNumericUserId(job.getCurriculum().getUserId());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("context", "curriculum_week_refund");
        metadata.put("curriculumId", job.getCurriculum().getId());
        metadata.put("weekNo", job.getWeekNo());
        metadata.put("jobId", job.getId());

        HeartTransaction tx = heartWalletService.adjustHearts(
                null,
                numericUserId,
                job.getHeartAmount() == null ? HEART_COST : job.getHeartAmount(),
                "커리큘럼 주차 생성 실패 환불",
                metadata
        );

        CurriculumJobLedger ledger = new CurriculumJobLedger();
        ledger.setJob(job);
        ledger.setActionType(CurriculumLedgerActionType.REFUND);
        ledger.setHeartTransaction(tx);
        try {
            curriculumJobLedgerRepository.save(ledger);
        } catch (DataIntegrityViolationException duplicated) {
            log.warn("Duplicated refund ledger jobId={}", jobId);
        }

        job.setRefunded(true);
        curriculumJobRepository.save(job);
    }

    private void dispatchNextPending(Long curriculumId) {
        Optional<CurriculumJob> next = curriculumJobRepository
                .findFirstByCurriculumIdAndStatusOrderByQueuedAtAsc(curriculumId, CurriculumJobStatus.PENDING);
        next.ifPresent(job -> dispatch(job.getId()));
    }

    @Transactional
    protected void recalculateCurriculumStatus(Long curriculumId) {
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

    private GenerationSnapshot parseSnapshot(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            return new GenerationSnapshot(null, List.of(), null, null, null, null, null, null, null, List.of());
        }
        try {
            Map<String, Object> root = objectMapper.readValue(snapshotJson, new TypeReference<>() {});
            return new GenerationSnapshot(
                    asString(root.get("primaryGoal")),
                    parseStringList(root.get("subGoals")),
                    asString(root.get("category")),
                    asString(root.get("subTopic")),
                    asString(root.get("ageRange")),
                    asString(root.get("baseLanguage")),
                    asString(root.get("translationLanguage")),
                    asString(root.get("artStyle")),
                    asString(root.get("voicePreset")),
                    parseLongList(root.get("characterIds"))
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid job snapshot", ex);
        }
    }

    private String buildEpisodeTitle(Curriculum curriculum, Integer weekNo) {
        return String.format("%s - Week %d", curriculum.getTitle(), weekNo);
    }

    private String resolveLanguage(String fromSnapshot, String fromCurriculum) {
        String candidate = StringUtils.hasText(fromSnapshot) ? fromSnapshot : fromCurriculum;
        if (!StringUtils.hasText(candidate)) {
            return "KO";
        }
        String normalized = candidate.trim().toUpperCase(Locale.ROOT);
        if (Set.of("KO", "EN", "JA", "FR", "ES", "DE", "ZH").contains(normalized)) {
            return normalized;
        }
        return "KO";
    }

    private String resolveTranslationLanguage(String fromSnapshot, String fromCurriculum, String sourceLanguage) {
        String candidate = StringUtils.hasText(fromSnapshot) ? fromSnapshot : fromCurriculum;
        if (!StringUtils.hasText(candidate)) {
            return null;
        }
        String normalized = candidate.trim().toUpperCase(Locale.ROOT);
        if ("NONE".equals(normalized)) {
            return null;
        }
        if (StringUtils.hasText(sourceLanguage) && normalized.equals(sourceLanguage.trim().toUpperCase(Locale.ROOT))) {
            return null;
        }
        if (Set.of("KO", "EN", "JA", "FR", "ES", "DE", "ZH").contains(normalized)) {
            return normalized;
        }
        return null;
    }

    private String buildCharacterStateJson(Story story, Integer weekNo) {
        Map<String, Object> state = new HashMap<>();
        state.put("lastStoryId", story.getId());
        state.put("lastStoryTitle", story.getTitle());
        state.put("lastWeek", weekNo);
        List<Map<String, Object>> characters = story.getCharacters().stream()
                .map(this::toCharacterState)
                .toList();
        state.put("characters", characters);
        return toJson(state, "{}");
    }

    private Map<String, Object> toCharacterState(Character character) {
        Map<String, Object> value = new HashMap<>();
        value.put("id", character.getId());
        value.put("name", character.getName());
        value.put("slug", character.getSlug());
        value.put("persona", character.getPersona());
        value.put("visualDescription", character.getVisualDescription());
        return value;
    }

    private String buildCoveredTopicsJson(GenerationSnapshot snapshot) {
        List<String> coveredTopics = new ArrayList<>();
        if (StringUtils.hasText(snapshot.primaryGoal())) {
            coveredTopics.add(snapshot.primaryGoal().trim());
        }
        coveredTopics.addAll(snapshot.subGoals().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .toList());
        if (coveredTopics.isEmpty() && StringUtils.hasText(snapshot.primaryGoal())) {
            coveredTopics = List.of(snapshot.primaryGoal().trim());
        }
        return toJson(coveredTopics, "[]");
    }

    private String buildAssetRefsJson(List<StorybookPage> storybookPages) {
        List<Map<String, Object>> pages = storybookPages.stream()
                .sorted(Comparator.comparing(StorybookPage::getPageNumber))
                .map(page -> {
                    Map<String, Object> node = new HashMap<>();
                    node.put("pageNo", page.getPageNumber());
                    node.put("imageUrl", page.getImageUrl());
                    node.put("audioUrl", page.getAudioUrl());
                    return node;
                })
                .toList();
        return toJson(pages, "[]");
    }

    private String summarize(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength)).trim() + "...";
    }

    private String summarizeError(Throwable ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        return summarize(message, 250);
    }

    private String normalizeNullable(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String toJson(Object value, String fallback) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return fallback;
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

    private List<String> parseStringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .filter(item -> item != null)
                .map(String::valueOf)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.toList());
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
            return list.stream().filter(item -> item != null).toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Long> parseLongList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            ids.add(Long.parseLong(String.valueOf(item)));
        }
        return ids;
    }

    private Long parseNumericUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid numeric user id for curriculum billing: " + userId, ex);
        }
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(AUTO_RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private record GenerationSnapshot(
            String primaryGoal,
            List<String> subGoals,
            String category,
            String subTopic,
            String ageRange,
            String baseLanguage,
            String translationLanguage,
            String artStyle,
            String voicePreset,
            List<Long> characterIds
    ) {
    }

    private record GenerationExecutionResult(
            Story story,
            String storyText,
            String assetRefsJson,
            String lastSummary,
            String characterStateJson,
            String coveredTopicsJson,
            boolean audioFailed
    ) {
    }
}
