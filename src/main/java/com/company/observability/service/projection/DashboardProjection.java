package com.company.observability.service.projection;

import com.company.observability.cache.DashboardCacheService;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.CalculatorDashboardResponse;
import com.company.observability.dto.response.CalculatorDashboardResponse.DashboardNode;
import com.company.observability.dto.response.CalculatorDashboardResponse.DashboardSection;
import com.company.observability.dto.response.CalculatorDashboardResponse.DependencyStatus;
import com.company.observability.dto.response.CalculatorDashboardResponse.LastRunIndicator;
import com.company.observability.dto.response.CalculatorDashboardResponse.MatrixCell;
import com.company.observability.dto.response.CalculatorDashboardResponse.MatrixRow;
import com.company.observability.dto.response.CalculatorDashboardResponse.SectionSummary;
import com.company.observability.dto.response.CalculatorDashboardResponse.StatusMatrix;
import com.company.observability.dto.response.SlaIndicator;
import com.company.observability.dto.response.TimeReference;
import com.company.observability.repository.CalculatorRunRepository.HistoricalRunStatus;
import com.company.observability.service.DashboardService;
import com.company.observability.service.DashboardService.DashboardResult;
import com.company.observability.service.DashboardService.MatrixCellResult;
import com.company.observability.service.DashboardService.MatrixResult;
import com.company.observability.service.DashboardService.NodeResult;
import com.company.observability.service.DashboardService.SectionResult;
import com.company.observability.service.RegionalBatchService.EstimatedTime;
import com.company.observability.util.RunStatusClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Maps {@link DashboardService} domain data to the UTC REST response
 * {@link CalculatorDashboardResponse} used by the calculator-dashboard endpoint.
 */
@Service
@RequiredArgsConstructor
public class DashboardProjection {

    private final DashboardService dashboardService;
    private final DashboardCacheService dashboardCacheService;

    public CalculatorDashboardResponse getCalculatorDashboard(
            LocalDate reportingDate, String frequency, int runNumber) {

        CalculatorDashboardResponse cached =
                dashboardCacheService.getStatusResponse(reportingDate, frequency, runNumber);
        if (cached != null) return cached;

        DashboardResult result = dashboardService.buildDashboard(reportingDate, frequency, runNumber);
        CalculatorDashboardResponse response = toDashboardResponse(result);
        dashboardCacheService.putStatusResponse(reportingDate, frequency, runNumber, response);
        return response;
    }

    private CalculatorDashboardResponse toDashboardResponse(DashboardResult result) {
        return new CalculatorDashboardResponse(
                result.reportingDate(),
                result.frequency(),
                result.runNumber(),
                result.generatedAt(),
                result.sections().stream().map(this::toSection).toList()
        );
    }

    private DashboardSection toSection(SectionResult section) {
        SlaIndicator sla = new SlaIndicator(section.slaDeadline(), section.slaBreached());

        DependencyStatus dependency = null;
        if (section.dependency() != null) {
            var dep = section.dependency();
            dependency = new DependencyStatus(dep.dependsOnSection(), dep.met(), dep.label());
        }

        return new DashboardSection(
                section.sectionKey(),
                section.displayName(),
                section.displayOrder(),
                sla,
                dependency,
                toSectionSummary(section.summary()),
                section.nodes().stream().map(this::toNode).toList()
        );
    }

    private SectionSummary toSectionSummary(DashboardService.SectionSummary s) {
        return new SectionSummary(
                s.totalCount(),
                s.completedCount(),
                s.inProgressCount(),
                s.failedCount(),
                s.notStartedCount(),
                s.status(),
                s.latenessMs(),
                toTimeReference(s.estimatedStart()),
                toTimeReference(s.estimatedEnd())
        );
    }

    private TimeReference toTimeReference(EstimatedTime est) {
        if (est == null) return null;
        return new TimeReference(est.time(), est.basedOn(), est.actual());
    }

    private DashboardNode toNode(NodeResult node) {
        CalculatorRun run = node.run();
        StatusMatrix matrix = node.matrix() != null ? toMatrix(node.matrix()) : null;

        List<LastRunIndicator> lastRuns = (node.lastRuns() == null || node.lastRuns().isEmpty())
                ? List.of()
                : node.lastRuns().stream()
                        .map(h -> new LastRunIndicator(h.reportingDate(), toDotStatus(h)))
                        .toList();

        List<String> displayLabels = (node.displayLabels() == null || node.displayLabels().isEmpty())
                ? null : node.displayLabels();

        return new DashboardNode(
                node.nodeKey(),
                node.displayName(),
                node.displayOrder(),
                run != null ? run.getRunId() : null,
                node.status(),
                run != null ? run.getStartTime() : null,
                run != null ? run.getEndTime()   : null,
                node.estimatedStartTime(),
                node.estimatedEndTime(),
                node.nextRunTime(),
                run != null ? run.getDurationMs() : null,
                node.latenessMs(),
                node.slaBreached(),
                displayLabels,
                matrix,
                lastRuns
        );
    }

    private StatusMatrix toMatrix(MatrixResult m) {
        return new StatusMatrix(
                m.columnDimension(),
                m.columns(),
                m.rows().stream().map(r ->
                        new MatrixRow(r.rowKey(), r.label(),
                                r.cells().stream().map(this::toMatrixCell).toList())
                ).toList()
        );
    }

    private MatrixCell toMatrixCell(MatrixCellResult cell) {
        CalculatorRun run = cell.run();
        return new MatrixCell(
                cell.key(),
                run != null ? run.getRunId()              : null,
                cell.status(),
                run != null ? run.getStartTime()          : null,
                run != null ? run.getEndTime()            : null,
                run != null ? run.getEstimatedStartTime() : null,
                run != null ? run.getEstimatedEndTime()   : null,
                run != null ? run.getDurationMs()         : null,
                cell.latenessMs(),
                cell.slaBreached()
        );
    }

    /**
     * Maps a historical run record to a dot status: ON_TIME, LATE, or FAILED.
     * (Last-run dots use the simplified three-value vocabulary.)
     */
    private String toDotStatus(HistoricalRunStatus h) {
        RunStatus runStatus = RunStatus.fromString(h.status());
        if (!runStatus.isTerminal() || runStatus == RunStatus.FAILED
                || runStatus == RunStatus.TIMEOUT || runStatus == RunStatus.CANCELLED) {
            return RunStatusClassifier.FAILED;
        }
        return h.slaBreached() ? RunStatusClassifier.LATE : RunStatusClassifier.ON_TIME;
    }
}
