package com.company.observability.controller;

import com.company.observability.config.TestMetricsConfig;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.PerformanceCardResponse;
import com.company.observability.service.ProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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
    private ProjectionService projectionService;

    @Test
    void getPerformanceCard_returnsCacheableResponse() throws Exception {
        PerformanceCardResponse response = new PerformanceCardResponse(
                "calc-1",
                "Calculator One",
                new PerformanceCardResponse.ScheduleInfo("06:00", "DAILY"),
                30,
                180000L,
                "3m 0s",
                new PerformanceCardResponse.SlaSummaryPct(1, 1, 100.0, 0, 0.0, 0, 0.0),
                List.of(),
                new PerformanceCardResponse.ReferenceLines(
                        BigDecimal.valueOf(6.00), BigDecimal.valueOf(6.25)));

        when(projectionService.getPerformanceCard("calc-1", "tenant-a", 30, CalculatorFrequency.DAILY))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/projections/calculators/calc-1/performance-card")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.calculatorId").value("calc-1"))
                .andExpect(jsonPath("$.meanDurationFormatted").value("3m 0s"))
                .andExpect(jsonPath("$.schedule.frequency").value("DAILY"));

        verify(projectionService).getPerformanceCard("calc-1", "tenant-a", 30, CalculatorFrequency.DAILY);
    }

    @Test
    void getPerformanceCard_defaultsFrequencyToDaily() throws Exception {
        PerformanceCardResponse response = new PerformanceCardResponse(
                "calc-2", null, null, 30, 0L, null,
                new PerformanceCardResponse.SlaSummaryPct(0, 0, 0.0, 0, 0.0, 0, 0.0),
                List.of(), null);

        when(projectionService.getPerformanceCard("calc-2", "tenant-b", 30, CalculatorFrequency.DAILY))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/analytics/projections/calculators/calc-2/performance-card")
                        .header(TENANT_HEADER, "tenant-b")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculatorId").value("calc-2"));

        verify(projectionService).getPerformanceCard("calc-2", "tenant-b", 30, CalculatorFrequency.DAILY);
    }

    @Test
    void missingTenantIdHeader_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/projections/calculators/calc-1/performance-card")
                        .param("days", "30"))
                .andExpect(status().isBadRequest());
    }
}
