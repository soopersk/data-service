package com.company.observability.controller;

import com.company.observability.config.TestMetricsConfig;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.*;
import com.company.observability.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMetricsConfig.class)
class AnalyticsControllerTest {

    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyticsService analyticsService;

    @Test
    void getRuntimeAnalytics_returnsCacheableResponse_andParsesFrequency() throws Exception {
        RuntimeAnalyticsResponse response = new RuntimeAnalyticsResponse(
                "calc-1", 30, "MONTHLY", 1200, 0, 0, 12, 0.95,
                List.of(new RuntimeAnalyticsResponse.DailyDataPoint(
                        LocalDate.parse("2026-02-21"), 1200, 2, 2)));

        when(analyticsService.getRuntimeAnalytics("calc-1", "tenant-a", 30, CalculatorFrequency.MONTHLY))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/calculators/calc-1/runtime")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "30")
                        .param("frequency", "MONTHLY"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.calculatorId").value("calc-1"))
                .andExpect(jsonPath("$.frequency").value("MONTHLY"));

        verify(analyticsService).getRuntimeAnalytics("calc-1", "tenant-a", 30, CalculatorFrequency.MONTHLY);
    }

    @Test
    void getSlaBreachDetails_returnsNoCacheHeader() throws Exception {
        PagedResponse<SlaBreachDetailResponse> response = new PagedResponse<>(
                List.of(new SlaBreachDetailResponse(
                        101L, "run-1", "calc-1", null,
                        null, "HIGH", "RED",
                        null, null, Instant.parse("2026-02-22T06:20:00Z"))),
                0, 20, 1, 1, null);

        when(analyticsService.getSlaBreachDetails("calc-1", "tenant-a", 7, null, 0, 20, null))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/calculators/calc-1/sla-breaches")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "7")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")))
                .andExpect(jsonPath("$.content[0].runId").value("run-1"));

        verify(analyticsService).getSlaBreachDetails("calc-1", "tenant-a", 7, null, 0, 20, null);
    }

    @Test
    void getRunPerformanceData_returnsCacheableResponse() throws Exception {
        RunPerformanceData response = new RunPerformanceData(
                "calc-1", "Calculator One", "DAILY", 30, 180000L,
                1, 1, 1, 0, 0,
                List.of(
                        new RunPerformanceData.RunDataPoint(
                                "run-1", LocalDate.parse("2026-02-21"),
                                Instant.parse("2026-02-21T04:00:00Z"),
                                Instant.parse("2026-02-21T04:03:00Z"),
                                180000L, "SUCCESS", false, "SLA_MET"),
                        new RunPerformanceData.RunDataPoint(
                                "run-2", LocalDate.parse("2026-02-22"),
                                Instant.parse("2026-02-22T04:00:00Z"),
                                null,
                                null, "RUNNING", false, "RUNNING")
                ),
                Instant.parse("2026-02-21T04:00:00Z"),
                Instant.parse("2026-02-21T06:15:00Z"));

        when(analyticsService.getRunPerformanceData("calc-1", "tenant-a", 30, CalculatorFrequency.DAILY))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/calculators/calc-1/run-performance")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(jsonPath("$.calculatorId").value("calc-1"))
                .andExpect(jsonPath("$.runningRuns").value(1))
                .andExpect(jsonPath("$.runs[0].slaStatus").value("SLA_MET"))
                .andExpect(jsonPath("$.runs[1].slaStatus").value("RUNNING"));

        verify(analyticsService).getRunPerformanceData("calc-1", "tenant-a", 30, CalculatorFrequency.DAILY);
    }

    @Test
    void getSlaSummary_returnsCacheableResponse() throws Exception {
        when(analyticsService.getSlaSummary("calc-1", "tenant-a", 14))
                .thenReturn(new SlaSummaryResponse("calc-1", 14, 2, 10, 2, 2, null, null));

        mockMvc.perform(get("/api/v1/analytics/calculators/calc-1/sla-summary")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.totalBreaches").value(2));

        verify(analyticsService).getSlaSummary("calc-1", "tenant-a", 14);
    }

    @Test
    void getTrends_returnsCacheableResponse() throws Exception {
        TrendAnalyticsResponse response = new TrendAnalyticsResponse(
                "calc-1", 30, List.of());

        when(analyticsService.getTrends("calc-1", "tenant-a", 30))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/calculators/calc-1/trends")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.calculatorId").value("calc-1"));

        verify(analyticsService).getTrends("calc-1", "tenant-a", 30);
    }

    @Test
    void missingTenantIdHeader_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/calculators/calc-1/runtime")
                        .param("days", "30"))
                .andExpect(status().isBadRequest());
    }
}
