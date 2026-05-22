package com.company.observability.controller;

import com.company.observability.config.TestMetricsConfig;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.PerformanceCardResponse;
import com.company.observability.service.projection.DashboardProjection;
import com.company.observability.service.projection.PerformanceCardProjection;
import com.company.observability.service.projection.RegionalBatchProjection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ProjectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMetricsConfig.class)
class ProjectionControllerTest {

    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PerformanceCardProjection performanceCardProjection;

    @MockitoBean
    private RegionalBatchProjection regionalBatchProjection;

    @MockitoBean
    private DashboardProjection dashboardProjection;

    @Test
    void getPerformanceCard_returnsCacheableResponse() throws Exception {
        PerformanceCardResponse response = new PerformanceCardResponse(
                "calc-1",
                "Calculator One",
                List.of(new PerformanceCardResponse.ScheduleEntry("run1", "06:00", "06:15")),
                30,
                180000L,
                new PerformanceCardResponse.SlaSummaryPct(1, 1, 0, 0, 0.0),
                List.of(new PerformanceCardResponse.RunBar(
                        "run-1", LocalDate.of(2026, 2, 20), null, null, 180000L, "ON_TIME", null)),
                new PerformanceCardResponse.ReferenceLines(6.0, 6.25));

        when(performanceCardProjection.getPerformanceCard("calc-1", 30, CalculatorFrequency.DAILY))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/projections/calculators/calc-1/performance-card")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.calculatorId").value("calc-1"))
                .andExpect(jsonPath("$.meanDurationMs").value(180000L))
                .andExpect(jsonPath("$.schedule[0].runKey").value("run1"));

        verify(performanceCardProjection).getPerformanceCard("calc-1", 30, CalculatorFrequency.DAILY);
    }

    @Test
    void getPerformanceCard_defaultsFrequencyToDaily() throws Exception {
        PerformanceCardResponse response = new PerformanceCardResponse(
                "calc-2", null, List.of(), 30, 0L,
                new PerformanceCardResponse.SlaSummaryPct(0, 0, 0, 0, 0.0),
                List.of(), null);

        when(performanceCardProjection.getPerformanceCard("calc-2", 30, CalculatorFrequency.DAILY))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/projections/calculators/calc-2/performance-card")
                        .header(TENANT_HEADER, "tenant-b")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculatorId").value("calc-2"));

        verify(performanceCardProjection).getPerformanceCard("calc-2", 30, CalculatorFrequency.DAILY);
    }

    @Test
    void missingTenantIdHeader_succeeds() throws Exception {
        PerformanceCardResponse response = new PerformanceCardResponse(
                "calc-1", null, List.of(), 30, 0L,
                new PerformanceCardResponse.SlaSummaryPct(0, 0, 0, 0, 0.0),
                List.of(), null);
        when(performanceCardProjection.getPerformanceCard("calc-1", 30, CalculatorFrequency.DAILY))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/projections/calculators/calc-1/performance-card")
                        .param("days", "30"))
                .andExpect(status().isOk());
    }
}
