package com.company.observability.service.projection;

import com.company.observability.cache.RegionalBatchCacheService;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.dto.response.RegionalBatchStatusResponse;
import com.company.observability.dto.response.TimeReference;
import com.company.observability.service.RegionalBatchService;
import com.company.observability.service.RegionalBatchService.EstimatedTime;
import com.company.observability.service.RegionalBatchService.RegionEntry;
import com.company.observability.service.RegionalBatchService.RegionalBatchResult;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps {@link RegionalBatchService} domain data to the pre-formatted
 * {@link RegionalBatchStatusResponse} used by the regional-batch-status endpoint.
 *
 * <p>This endpoint is deprecated — the unified calculator dashboard is the preferred alternative.
 * See {@link com.company.observability.controller.ProjectionController} for deprecation headers.
 */
@Service
@RequiredArgsConstructor
public class RegionalBatchProjection {

    private final RegionalBatchService regionalBatchService;
    private final RegionalBatchCacheService regionalBatchCacheService;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH);

    public RegionalBatchStatusResponse getRegionalBatchStatus(String tenantId, LocalDate reportingDate) {
        RegionalBatchStatusResponse cached = regionalBatchCacheService.getStatusResponse(tenantId, reportingDate);
        if (cached != null) {
            return cached;
        }
        RegionalBatchResult result = regionalBatchService.getRegionalBatchStatus(tenantId, reportingDate);
        RegionalBatchStatusResponse response = toRegionalBatchResponse(result);
        regionalBatchCacheService.putStatusResponse(tenantId, reportingDate, response);
        return response;
    }

    private RegionalBatchStatusResponse toRegionalBatchResponse(RegionalBatchResult result) {
        String dateFormatted = result.reportingDate().format(DATE_FORMATTER);

        String slaTimeCetStr = TimeUtils.formatCetHour(
                TimeUtils.calculateCetHour(result.slaDeadline()));
        var overallSla = new RegionalBatchStatusResponse.OverallSla(slaTimeCetStr, result.overallBreached());

        TimeReference estimatedStart = toTimeReference(result.estimatedStart());
        TimeReference estimatedEnd   = toTimeReference(result.estimatedEnd());

        List<RegionalBatchStatusResponse.RegionStatus> regions = new ArrayList<>();
        for (RegionEntry entry : result.entries()) {
            regions.add(toRegionStatus(entry, result.reportingDate()));
        }

        return new RegionalBatchStatusResponse(
                result.reportingDate(),
                dateFormatted,
                overallSla,
                estimatedStart,
                estimatedEnd,
                result.totalRegions(),
                result.completedRegions(),
                result.runningRegions(),
                result.failedRegions(),
                regions,
                result.slaBreachedRegions()
        );
    }

    private TimeReference toTimeReference(EstimatedTime est) {
        if (est == null) return null;
        BigDecimal hourCet = TimeUtils.calculateCetHour(est.time());
        String timeCet = TimeUtils.formatCetHour(hourCet) + " CET";
        return new TimeReference(timeCet, hourCet, est.basedOn(), est.actual());
    }

    private RegionalBatchStatusResponse.RegionStatus toRegionStatus(
            RegionEntry entry, LocalDate reportingDate) {
        CalculatorRun run = entry.run();

        if (run == null) {
            return new RegionalBatchStatusResponse.RegionStatus(
                    entry.region(), null, entry.status(),
                    null, null, null, null,
                    null, null, null, null, false);
        }

        BigDecimal startHour = TimeUtils.calculateCetHour(run.getStartTime());
        BigDecimal endHour   = TimeUtils.calculateCetHour(run.getEndTime());
        String startCet = startHour != null ? TimeUtils.formatCetHour(startHour) + " CET" : null;
        String endCet   = endHour   != null ? TimeUtils.formatCetHour(endHour)   + " CET" : null;
        LocalDate runDate = run.getStartTime() != null
                ? TimeUtils.getCetDate(run.getStartTime()) : reportingDate;
        String runDay = runDate.format(DATE_FORMATTER);

        String batchType = run.getRunType();

        return new RegionalBatchStatusResponse.RegionStatus(
                entry.region(),
                run.getRunId(),
                entry.status(),
                startCet,
                endCet,
                startHour,
                endHour,
                run.getDurationMs(),
                TimeUtils.formatDuration(run.getDurationMs()),
                runDay,
                batchType != null ? batchType.toString() : null,
                entry.slaBreached()
        );
    }
}
