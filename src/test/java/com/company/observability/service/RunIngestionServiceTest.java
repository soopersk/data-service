package com.company.observability.service;

import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.SlaMode;
import com.company.observability.domain.enums.CompletionStatus;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.dto.request.CompleteRunRequest;
import com.company.observability.dto.request.StartRunRequest;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.exception.DomainAccessDeniedException;
import com.company.observability.exception.DomainValidationException;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.domain.SlaEvaluationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunIngestionServiceTest {

    @Mock
    private CalculatorRunRepository runRepository;
    @Mock
    private SlaEvaluationService slaEvaluationService;
    @Mock
    private SlaBaselineResolver slaBaselineResolver;
    @Mock
    private CalculatorProfileService calculatorProfileService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SlaMonitoringCache slaMonitoringCache;

    private RunIngestionService service;

    private static final CalculatorProfile EMPTY_PROFILE =
            new CalculatorProfile("Calculator 1", "DAILY", 0, 0, 0, 0);

    private SlaProperties slaProperties;
    private DurationBasedSlaProperties durationProps;

    @BeforeEach
    void setUp() {
        slaProperties = new SlaProperties(); // defaults to CLOCK_TIME
        durationProps = new DurationBasedSlaProperties();
        service = new RunIngestionService(
                runRepository,
                slaEvaluationService,
                slaBaselineResolver,
                calculatorProfileService,
                eventPublisher,
                new SimpleMeterRegistry(),
                slaMonitoringCache,
                new com.company.observability.logging.LifecycleLogger(),
                slaProperties,
                durationProps
        );
    }

    @Test
    void completeRun_rejectsEndTimeBeforeStartTime() {
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        when(runRepository.findById(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.minusSeconds(1))
                .status(CompletionStatus.SUCCESS)
                .build();

        assertThrows(DomainValidationException.class,
                () -> service.completeRun("run-1", request, "tenant-1"));

        verify(runRepository, never()).upsert(any());
        verify(slaMonitoringCache, never()).deregisterFromSlaMonitoring(anyString(), anyString(), any(LocalDate.class));
    }

    @Test
    void startRun_derivedDeadline_freezesSlaTimeAndRegistersForMonitoring() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");
        Instant derivedDeadline = start.plusSeconds(3600);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-derived")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .build();

        when(runRepository.findById("run-derived", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY))
                .thenReturn(EMPTY_PROFILE);
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        3_600_000L,
                        derivedDeadline
                ));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                derivedDeadline.equals(run.getSlaTime())
                        && run.getSlaBand() == null));
        verify(slaMonitoringCache).registerForSlaMonitoring(any(CalculatorRun.class));
        verify(eventPublisher, never()).publishEvent(any(SlaBreachedEvent.class));
    }

    @Test
    void startRun_monthlyWithDerivedDeadline_registersForMonitoring() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate eom = LocalDate.of(2026, 2, 28);
        Instant start = Instant.parse("2026-02-28T05:00:00Z");
        Instant derivedDeadline = start.plusSeconds(7200);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-monthly-derived")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.MONTHLY)
                .reportingDate(eom)
                .startTime(start)
                .build();

        when(runRepository.findById("run-monthly-derived", eom)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.MONTHLY))
                .thenReturn(EMPTY_PROFILE);
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.MONTHLY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        7_200_000L,
                        derivedDeadline
                ));

        service.startRun(request, "tenant-1");

        // MONTHLY now registers for live monitoring (previously DAILY-only).
        verify(slaMonitoringCache).registerForSlaMonitoring(any(CalculatorRun.class));
        verify(eventPublisher, never()).publishEvent(any(SlaBreachedEvent.class));
    }

    @Test
    void startRun_noBaseline_skipsMonitoringAndDoesNotBreach() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-ungraded")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .build();

        when(runRepository.findById("run-ungraded", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY))
                .thenReturn(EMPTY_PROFILE);
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        null,
                        null
                ));

        service.startRun(request, "tenant-1");

        verify(slaMonitoringCache, never()).registerForSlaMonitoring(any(CalculatorRun.class));
        verify(runRepository).upsert(argThat(run -> run.getSlaTime() == null
                && run.getSlaBand() == null));
        verify(eventPublisher).publishEvent(any(com.company.observability.event.RunStartedEvent.class));
    }

    @Test
    void startRun_durationMode_persistsResolvedBaselineAsExpectedDuration() {
        // In DURATION mode the resolver's baseline IS the expectedDurationMs.
        slaProperties.setMode(SlaMode.DURATION);
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");
        Instant derivedDeadline = start.plusSeconds(4_200);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-baseline")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .slaTime("PT1H")
                .expectedDurationMs(9_999_999L)
                .build();

        when(runRepository.findById("run-baseline", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY)).thenReturn(EMPTY_PROFILE);
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(3_600_000L, derivedDeadline));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                Long.valueOf(3_600_000L).equals(run.getExpectedDurationMs())
                        && derivedDeadline.equals(run.getSlaTime())));
    }

    @Test
    void startRun_clockMode_persistsRequestExpectedDurationMs() {
        // In CLOCK_TIME mode expectedDurationMs comes from the request, not the SLA resolution.
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");
        Instant clockDeadline = Instant.parse("2026-02-20T22:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-clock")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .slaTime("22:00")
                .expectedDurationMs(7_200_000L)
                .build();

        when(runRepository.findById("run-clock", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY)).thenReturn(EMPTY_PROFILE);
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, clockDeadline));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                Long.valueOf(7_200_000L).equals(run.getExpectedDurationMs())
                        && clockDeadline.equals(run.getSlaTime())));
    }

    @Test
    void startRun_clockMode_estimatedEndDefaultsToDeadlineWhenNoDuration() {
        // In CLOCK_TIME mode with no duration baseline, estimatedEndTime = deadline.
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");
        Instant clockDeadline = Instant.parse("2026-02-20T22:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-clock-end")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .slaTime("22:00")
                .build(); // no expectedDurationMs, no estimatedEndTime

        when(runRepository.findById("run-clock-end", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY)).thenReturn(EMPTY_PROFILE);
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, clockDeadline));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                clockDeadline.equals(run.getEstimatedEndTime())
                        && clockDeadline.equals(run.getSlaTime())));
    }

    @Test
    void startRun_estimatedEnd_usesResolvedBaselineWhenOnlySlaTimeProvided() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);

        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-sla-only")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .slaTime("PT2H")
                .build();

        when(runRepository.findById("run-sla-only", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY)).thenReturn(EMPTY_PROFILE);
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        7_200_000L,
                        start.plusSeconds(8_640)
                ));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                start.plusSeconds(7_200).equals(run.getEstimatedEndTime())));
    }

    @Test
    void completeRun_alreadyBreachedPublishesCompletedEventAndSkipsDuplicateBreachEvent() {
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        run.setSlaBand(SlaBand.LATE);
        run.setSlaBreachReason("Start time is after SLA deadline");

        when(runRepository.findById(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));
        when(slaEvaluationService.evaluateSla(any(CalculatorRun.class)))
                .thenReturn(new SlaEvaluationResult(SlaBand.LATE, "Finished 30 minutes late"));
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(600))
                .status(CompletionStatus.SUCCESS)
                .build();

        service.completeRun("run-1", request, "tenant-1");

        verify(eventPublisher).publishEvent(any(RunCompletedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(SlaBreachedEvent.class));
    }

    @Test
    void completeRun_newBreachOnCompletion_persistsBreachAndPublishesBreachEvent() {
        Instant start = Instant.parse("2026-02-20T10:00:00Z");
        CalculatorRun run = runningRun(start);
        run.setSlaBand(null);
        run.setSlaBreachReason(null);

        when(runRepository.findById(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));
        when(slaEvaluationService.evaluateSla(any(CalculatorRun.class)))
                .thenReturn(new SlaEvaluationResult(SlaBand.LATE, "Finished 30 minutes late"));
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(1800))
                .status(CompletionStatus.SUCCESS)
                .build();

        service.completeRun("run-1", request, "tenant-1");

        verify(runRepository).upsert(argThat(saved ->
                SlaBand.LATE.equals(saved.getSlaBand())
                        && "Finished 30 minutes late".equals(saved.getSlaBreachReason())));
        verify(eventPublisher).publishEvent(any(SlaBreachedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(RunCompletedEvent.class));
    }

    // ---------------------------------------------------------------
    // startRun — additional coverage
    // ---------------------------------------------------------------

    @Test
    void startRun_idempotent_returnsExistingRunWithoutPublishingEvents() {
        LocalDate reportingDate = LocalDate.of(2026, 4, 10);
        Instant start = Instant.parse("2026-04-10T05:00:00Z");
        CalculatorRun existing = runningRun(start);

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.DAILY)
                .reportingDate(reportingDate)
                .startTime(start)
                .slaTime("2026-02-20T05:15:00Z")
                .build();

        when(runRepository.findById("run-1", reportingDate)).thenReturn(Optional.of(existing));

        CalculatorRun result = service.startRun(request, "tenant-1");

        assertThat(result).isEqualTo(existing);
        verify(runRepository, never()).upsert(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void startRun_monthly_nonEomDate_warnsButPersistsRun() {
        // Jan 15 is not end-of-month — should warn but NOT reject
        LocalDate nonEom = LocalDate.of(2026, 1, 15);
        Instant start = Instant.parse("2026-01-15T05:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-monthly")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .frequency(Frequency.MONTHLY)
                .reportingDate(nonEom)
                .startTime(start)
                .slaTime("2026-01-15T05:15:00Z")
                .build();

        when(runRepository.findById("run-monthly", nonEom)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.MONTHLY))
                .thenReturn(EMPTY_PROFILE);
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.MONTHLY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(null, null));

        // Should not throw
        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(any());
        verify(eventPublisher).publishEvent(any(com.company.observability.event.RunStartedEvent.class));
    }

    // ---------------------------------------------------------------
    // completeRun — additional coverage
    // ---------------------------------------------------------------

    @Test
    void completeRun_endTimeEqualsStartTime_durationIsZeroAndSucceeds() {
        Instant start = Instant.parse("2026-04-10T05:00:00Z");
        CalculatorRun run = runningRun(start);
        when(runRepository.findById(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));
        when(slaEvaluationService.evaluateSla(any())).thenReturn(new SlaEvaluationResult(SlaBand.ON_TIME, null));
        when(runRepository.upsert(any())).thenAnswer(inv -> inv.getArgument(0));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start)   // exactly equal to startTime
                .status(CompletionStatus.SUCCESS)
                .build();

        CalculatorRun result = service.completeRun("run-1", request, "tenant-1");

        assertThat(result.getDurationMs()).isZero();
        verify(eventPublisher).publishEvent(any(RunCompletedEvent.class));
    }

    @Test
    void completeRun_tenantMismatch_throwsDomainAccessDeniedException() {
        Instant start = Instant.parse("2026-04-10T05:00:00Z");
        CalculatorRun run = runningRun(start); // tenantId = "tenant-1"
        when(runRepository.findById(eq("run-1"), any(LocalDate.class))).thenReturn(Optional.of(run));

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(LocalDate.now())
                .endTime(start.plusSeconds(600))
                .status(CompletionStatus.SUCCESS)
                .build();

        assertThrows(DomainAccessDeniedException.class,
                () -> service.completeRun("run-1", request, "different-tenant"));

        verify(runRepository, never()).upsert(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---------------------------------------------------------------
    // startRun — estimated start/end precedence
    // ---------------------------------------------------------------

    @Test
    void startRun_estimatedTimes_requestValuesWinOverProfile() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);
        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");
        Instant reqEstStart = Instant.parse("2026-02-20T04:30:00Z");
        Instant reqEstEnd = Instant.parse("2026-02-20T06:30:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-est").calculatorId("calc-1").calculatorName("Calculator 1")
                .frequency(Frequency.DAILY).reportingDate(reportingDate).startTime(start)
                .estimatedStartTime(reqEstStart).estimatedEndTime(reqEstEnd)
                .build();

        when(runRepository.findById("run-est", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));
        // Profile has samples, but request values must take precedence.
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY))
                .thenReturn(new CalculatorProfile("Calculator 1", "DAILY", 3_600_000L, 200, 300, 10));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        null,
                        null
                ));

        service.startRun(request, "tenant-1");

        verify(runRepository).upsert(argThat(run ->
                reqEstStart.equals(run.getEstimatedStartTime())
                        && reqEstEnd.equals(run.getEstimatedEndTime())));
    }

    @Test
    void startRun_estimatedTimes_derivedFromProfileWhenRequestOmits() {
        ReflectionTestUtils.setField(service, "liveTrackingEnabled", true);
        LocalDate reportingDate = LocalDate.of(2026, 2, 20);
        Instant start = Instant.parse("2026-02-20T05:00:00Z");

        StartRunRequest request = StartRunRequest.builder()
                .runId("run-est2").calculatorId("calc-1").calculatorName("Calculator 1")
                .frequency(Frequency.DAILY).reportingDate(reportingDate).startTime(start)
                .build(); // no estimated times, no expectedDurationMs

        // avg start = 270 min UTC = 04:30; avg duration = 1h
        when(runRepository.findById("run-est2", reportingDate)).thenReturn(Optional.empty());
        when(runRepository.upsert(any(CalculatorRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(calculatorProfileService.getProfile("Calculator 1", Frequency.DAILY))
                .thenReturn(new CalculatorProfile("Calculator 1", "DAILY", 3_600_000L, 270, 330, 10));
        when(slaBaselineResolver.resolve(any(StartRunRequest.class), eq(Frequency.DAILY), any()))
                .thenReturn(new SlaBaselineResolver.SlaResolution(
                        3_600_000L,
                        null
                ));

        service.startRun(request, "tenant-1");

        Instant expectedStart = Instant.parse("2026-02-20T04:30:00Z");          // date(start) + 270 min UTC
        Instant expectedEnd = expectedStart.plusMillis(3_600_000L);             // estimatedStart + avgDuration
        verify(runRepository).upsert(argThat(run ->
                expectedStart.equals(run.getEstimatedStartTime())
                        && expectedEnd.equals(run.getEstimatedEndTime())));
    }

    @Test
    void completeRun_wrongReportingDate_throwsNotFoundException() {
        LocalDate wrongDate = LocalDate.of(2026, 1, 1);
        when(runRepository.findById(eq("run-1"), eq(wrongDate))).thenReturn(Optional.empty());

        CompleteRunRequest request = CompleteRunRequest.builder()
                .reportingDate(wrongDate)
                .endTime(Instant.parse("2026-04-10T06:00:00Z"))
                .status(CompletionStatus.SUCCESS)
                .build();

        assertThrows(com.company.observability.exception.DomainNotFoundException.class,
                () -> service.completeRun("run-1", request, "tenant-1"));

        verify(runRepository, never()).upsert(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    private CalculatorRun runningRun(Instant start) {
        return CalculatorRun.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .tenantId("tenant-1")
                .frequency(Frequency.DAILY)
                .reportingDate(LocalDate.now())
                .startTime(start)
                .status(RunStatus.RUNNING)
                .createdAt(start)
                .slaBand(null)
                .build();
    }
}

