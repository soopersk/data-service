package com.company.observability.service.projection;

import com.company.observability.cache.DashboardCacheService;
import com.company.observability.dto.response.CalculatorDashboardResponse;
import com.company.observability.dto.response.CalculatorDashboardResponse.DashboardNode;
import com.company.observability.dto.response.CalculatorDashboardResponse.DashboardSection;
import com.company.observability.dto.response.CalculatorDashboardResponse.SectionSummary;
import com.company.observability.dto.response.SlaIndicator;
import com.company.observability.dto.response.TimeReference;
import com.company.observability.service.DashboardService;
import com.company.observability.service.DashboardService.DashboardResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardProjectionTest {

    @Mock
    private DashboardService dashboardService;

    @Mock
    private DashboardCacheService dashboardCacheService;

    private DashboardProjection projection;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 17);
    private static final Instant GENERATED_AT = Instant.parse("2026-04-17T08:00:00Z");

    @BeforeEach
    void setUp() {
        projection = new DashboardProjection(dashboardService, dashboardCacheService);
    }

    @Test
    void getCalculatorDashboard_cacheHit_returnsCachedResponseWithoutCallingService() {
        CalculatorDashboardResponse cached = minimalResponse(DATE);
        when(dashboardCacheService.getStatusResponse(DATE, "DAILY", 1)).thenReturn(cached);

        CalculatorDashboardResponse result = projection.getCalculatorDashboard(DATE, "DAILY", 1);

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(dashboardService);
    }

    @Test
    void getCalculatorDashboard_cacheMiss_buildsAndCachesResult() {
        when(dashboardCacheService.getStatusResponse(DATE, "DAILY", 1)).thenReturn(null);

        DashboardResult domainResult = new DashboardResult(DATE, "DAILY", 1, GENERATED_AT, List.of());
        when(dashboardService.buildDashboard(DATE, "DAILY", 1)).thenReturn(domainResult);

        CalculatorDashboardResponse response = projection.getCalculatorDashboard(DATE, "DAILY", 1);

        assertThat(response).isNotNull();
        assertThat(response.reportingDate()).isEqualTo(DATE);
        assertThat(response.frequency()).isEqualTo("DAILY");
        assertThat(response.runNumber()).isEqualTo(1);
        assertThat(response.generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(response.sections()).isEmpty();
        verify(dashboardCacheService).putStatusResponse(eq(DATE), eq("DAILY"), eq(1), any());
    }

    @Test
    void getCalculatorDashboard_run2_usesCorrectCacheKey() {
        when(dashboardCacheService.getStatusResponse(DATE, "DAILY", 2)).thenReturn(null);

        DashboardResult domainResult = new DashboardResult(DATE, "DAILY", 2, GENERATED_AT, List.of());
        when(dashboardService.buildDashboard(DATE, "DAILY", 2)).thenReturn(domainResult);

        projection.getCalculatorDashboard(DATE, "DAILY", 2);

        verify(dashboardCacheService).getStatusResponse(DATE, "DAILY", 2);
        verify(dashboardCacheService).putStatusResponse(eq(DATE), eq("DAILY"), eq(2), any());
        // Run 1 cache is never touched
        verify(dashboardCacheService, never()).getStatusResponse(DATE, "DAILY", 1);
    }

    @Test
    void calculatorDashboardResponse_serializesTimestampsAsUtcMillis() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Instant deadline = Instant.parse("2026-04-17T15:45:00Z");
        Instant start = Instant.parse("2026-04-17T04:15:05.030Z");
        Instant end = Instant.parse("2026-04-17T06:30:00Z");
        CalculatorDashboardResponse response = new CalculatorDashboardResponse(
                DATE,
                "DAILY",
                1,
                GENERATED_AT,
                List.of(new DashboardSection(
                        "REGIONAL",
                        "Regional",
                        1,
                        new SlaIndicator(deadline, false),
                        null,
                        new SectionSummary(
                                1, 1, 0, 0, 0,
                                "ON_TIME",
                                null,
                                new TimeReference(start, "WMAP", true),
                                new TimeReference(end, "WMDE", true)),
                        List.of(new DashboardNode(
                                "WMAP",
                                "WMAP",
                                1,
                                "run-wmap-20260417",
                                "ON_TIME",
                                start,
                                end,
                                null, null, null,
                                8094970L,
                                null,
                                false,
                                null,
                                null,
                                List.of())))));

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"deadlineTime\":\"2026-04-17T15:45:00.000Z\"");
        assertThat(json).contains("\"breached\":false");
        assertThat(json).contains("\"startTime\":\"2026-04-17T04:15:05.030Z\"");
        assertThat(json).contains("\"endTime\":\"2026-04-17T06:30:00.000Z\"");
        assertThat(json).contains("\"generatedAt\":\"2026-04-17T08:00:00.000Z\"");
        assertThat(json).doesNotContain("reportingDateFormatted");
        assertThat(json).doesNotContain("Cet");
    }

    private CalculatorDashboardResponse minimalResponse(LocalDate date) {
        return new CalculatorDashboardResponse(date, "DAILY", 1, GENERATED_AT, List.of());
    }
}
