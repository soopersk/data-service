package com.company.observability.service.projection;

import com.company.observability.cache.DashboardCacheService;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.CalculatorDashboardResponse;
import com.company.observability.dto.response.CalculatorDashboardResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorDashboardResponse.DashboardSection;
import com.company.observability.dto.response.CalculatorDashboardResponse.DependencyStatus;
import com.company.observability.dto.response.CalculatorDashboardResponse.LastRunIndicator;
import com.company.observability.dto.response.CalculatorDashboardResponse.SectionSla;
import com.company.observability.dto.response.CalculatorDashboardResponse.SectionSummary;
import com.company.observability.dto.response.CalculatorDashboardResponse.SubRunStatus;
import com.company.observability.dto.response.TimeReference;
import com.company.observability.repository.CalculatorRunRepository.HistoricalRunStatus;
import com.company.observability.service.DashboardService;
import com.company.observability.service.DashboardService.CalculatorEntryResult;
import com.company.observability.service.DashboardService.DashboardResult;
import com.company.observability.service.DashboardService.SectionResult;
import com.company.observability.service.DashboardService.SubRunResult;
import com.company.observability.service.RegionalBatchService.EstimatedTime;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Maps {@link DashboardService} domain data to the pre-formatted
 * {@link CalculatorDashboardResponse} used by the calculator-dashboard endpoint.
 */
@Service
@RequiredArgsConstructor
public class DashboardProjection {

    private final DashboardService dashboardService;
    private final DashboardCacheService dashboardCacheService;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH);

    public CalculatorDashboardResponse getCalculatorDashboard(
            String tenantId, LocalDate reportingDate, String frequency, int runNumber) {

        CalculatorDashboardResponse cached =
                dashboardCacheService.getStatusResponse(tenantId, reportingDate, frequency, runNumber);
        if (cached != null) return cached;

        DashboardResult result = dashboardService.buildDashboard(tenantId, reportingDate, frequency, runNumber);
        CalculatorDashboardResponse response = toDashboardResponse(result);
        dashboardCacheService.putStatusResponse(tenantId, reportingDate, frequency, runNumber, response);
        return response;
    }

    private CalculatorDashboardResponse toDashboardResponse(DashboardResult result) {
        String dateFormatted = result.reportingDate().format(DATE_FORMATTER);

        List<DashboardSection> sections = result.sections().stream()
                .map(this::toSection)
                .toList();

        return new CalculatorDashboardResponse(
                result.reportingDate(),
                dateFormatted,
                result.frequency(),
                result.runNumber(),
                sections
        );
    }

    private DashboardSection toSection(SectionResult section) {
        BigDecimal slaHour = TimeUtils.calculateCetHour(section.slaDeadline());
        String slaTime = TimeUtils.formatCetHour(slaHour);
        SectionSla sla = new SectionSla(slaTime, slaHour, section.slaBreached());

        DependencyStatus dependency = null;
        if (section.dependency() != null) {
            var dep = section.dependency();
            dependency = new DependencyStatus(dep.dependsOnSection(), dep.met(), dep.label());
        }

        SectionSummary summary = toSectionSummary(section.summary());

        List<CalculatorEntry> calculators = section.entries().stream()
                .map(this::toCalculatorEntry)
                .toList();

        return new DashboardSection(
                section.sectionKey(),
                section.displayName(),
                section.displayOrder(),
                sla,
                dependency,
                summary,
                calculators,
                section.displayLabels()
        );
    }

    private SectionSummary toSectionSummary(DashboardService.SectionSummary s) {
        TimeReference estStart = toTimeReference(s.estimatedStart());
        TimeReference estEnd   = toTimeReference(s.estimatedEnd());
        return new SectionSummary(
                s.totalCalculators(),
                s.completedCount(),
                s.runningCount(),
                s.failedCount(),
                s.notStartedCount(),
                estStart,
                estEnd
        );
    }

    private TimeReference toTimeReference(EstimatedTime est) {
        if (est == null) return null;
        BigDecimal hourCet = TimeUtils.calculateCetHour(est.time());
        String timeCet = TimeUtils.formatCetHour(hourCet) + " CET";
        return new TimeReference(timeCet, hourCet, est.basedOn(), est.actual());
    }

    private CalculatorEntry toCalculatorEntry(CalculatorEntryResult entry) {
        CalculatorRun run = entry.run();

        BigDecimal startHour = run != null ? TimeUtils.calculateCetHour(run.getStartTime()) : null;
        BigDecimal endHour   = run != null ? TimeUtils.calculateCetHour(run.getEndTime()) : null;
        String startCet = startHour != null ? TimeUtils.formatCetHour(startHour) + " CET" : null;
        String endCet   = endHour   != null ? TimeUtils.formatCetHour(endHour)   + " CET" : null;
        Long durationMs = run != null ? run.getDurationMs() : null;

        List<SubRunStatus> subRuns = null;
        if (entry.subRuns() != null && !entry.subRuns().isEmpty()) {
            subRuns = entry.subRuns().stream()
                    .map(this::toSubRunStatus)
                    .toList();
        }

        List<LastRunIndicator> lastRuns = entry.lastRuns() == null
                ? List.of()
                : entry.lastRuns().stream()
                        .map(h -> new LastRunIndicator(h.reportingDate(), toDotStatus(h)))
                        .toList();

        return new CalculatorEntry(
                entry.calculatorId(),
                entry.calculatorName(),
                run != null ? run.getRunId() : null,
                entry.status(),
                startCet,
                endCet,
                startHour,
                endHour,
                durationMs,
                TimeUtils.formatDuration(durationMs),
                entry.slaBreached(),
                subRuns,
                lastRuns
        );
    }

    private SubRunStatus toSubRunStatus(SubRunResult sub) {
        CalculatorRun run = sub.run();
        BigDecimal startHour = run != null ? TimeUtils.calculateCetHour(run.getStartTime()) : null;
        BigDecimal endHour   = run != null ? TimeUtils.calculateCetHour(run.getEndTime()) : null;
        String startCet = startHour != null ? TimeUtils.formatCetHour(startHour) + " CET" : null;
        String endCet   = endHour   != null ? TimeUtils.formatCetHour(endHour)   + " CET" : null;
        Long durationMs = run != null ? run.getDurationMs() : null;

        return new SubRunStatus(
                sub.subRunKey(),
                run != null ? run.getRunId() : null,
                sub.status(),
                startCet,
                endCet,
                durationMs,
                TimeUtils.formatDuration(durationMs),
                sub.slaBreached()
        );
    }

    /** Maps a historical run record to a dot status: ON_TIME, DELAYED, or FAILED. */
    private String toDotStatus(HistoricalRunStatus h) {
        RunStatus runStatus = RunStatus.fromString(h.status());
        if (!runStatus.isTerminal() || runStatus == RunStatus.FAILED
                || runStatus == RunStatus.TIMEOUT || runStatus == RunStatus.CANCELLED) {
            return "FAILED";
        }
        return h.slaBreached() ? "DELAYED" : "ON_TIME";
    }
}
