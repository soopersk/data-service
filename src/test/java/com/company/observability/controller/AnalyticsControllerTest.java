package com.company.observability.controller;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.PagedResponse;
import com.company.observability.dto.response.PerformanceCardResponse;
import com.company.observability.dto.response.RuntimeAnalyticsResponse;
import com.company.observability.dto.response.SlaBreachDetailResponse;
import com.company.observability.service.AnalyticsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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
@Import(AnalyticsControllerTest.MetricsTestConfig.class)
class AnalyticsControllerTest {

    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalyticsService analyticsService;

    @TestConfiguration
    static class MetricsTestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void getRuntimeAnalytics_returnsCacheableResponse_andParsesFrequency() throws Exception {
        RuntimeAnalyticsResponse response = new RuntimeAnalyticsResponse(
                "calc-1", 30, "MONTHLY", 1200, null, 0, 0, 12, 0.95,
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
    void getPerformanceCard_defaultsFrequencyToDaily() throws Exception {
        PerformanceCardResponse response = new PerformanceCardResponse(
                "calc-1",
                "Calculator One",
                new PerformanceCardResponse.ScheduleInfo("06:00", "DAILY"),
                30,
                180000L,
                null,
                new PerformanceCardResponse.SlaSummaryPct(1, 1, 100.0, 0, 0.0, 0, 0.0),
                List.of(),
                new PerformanceCardResponse.ReferenceLines(
                        BigDecimal.valueOf(6.00), BigDecimal.valueOf(6.15)));

        when(analyticsService.getPerformanceCard("calc-1", "tenant-a", 30, CalculatorFrequency.DAILY))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/calculators/calc-1/performance-card")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculatorId").value("calc-1"))
                .andExpect(jsonPath("$.schedule.frequency").value("DAILY"));

        verify(analyticsService).getPerformanceCard("calc-1", "tenant-a", 30, CalculatorFrequency.DAILY);
    }

    @Test
    void getSlaSummary_returnsCacheableResponse() throws Exception {
        when(analyticsService.getSlaSummary("calc-1", "tenant-a", 14))
                .thenReturn(new com.company.observability.dto.response.SlaSummaryResponse(
                        "calc-1", 14, 2, 10, 2, 2, null, null));

        mockMvc.perform(get("/api/v1/analytics/calculators/calc-1/sla-summary")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.totalBreaches").value(2));

        verify(analyticsService).getSlaSummary("calc-1", "tenant-a", 14);
    }
}
