package com.company.observability.controller;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.CalculatorStatusResponse;
import com.company.observability.dto.response.RunStatusInfo;
import com.company.observability.service.RunQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RunQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(RunQueryControllerTest.MetricsTestConfig.class)
class RunQueryControllerTest {

    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RunQueryService queryService;

    @TestConfiguration
    static class MetricsTestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void getCalculatorStatus_returnsCacheableResponse_whenBypassCacheFalse() throws Exception {
        CalculatorStatusResponse response = sampleStatusResponse("Calculator One");
        when(queryService.getCalculatorStatus("calc-1", "tenant-a",
                CalculatorFrequency.DAILY, 5, false)).thenReturn(response);

        mockMvc.perform(get("/api/v1/calculators/calc-1/status")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("frequency", "d")
                        .param("historyLimit", "5")
                        .param("bypassCache", "false"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=30")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.calculatorName").value("Calculator One"))
                .andExpect(jsonPath("$.current.status").value("RUNNING"));

        verify(queryService).getCalculatorStatus("calc-1", "tenant-a",
                CalculatorFrequency.DAILY, 5, false);
    }

    @Test
    void getCalculatorStatus_returnsNoCache_whenBypassCacheTrue() throws Exception {
        CalculatorStatusResponse response = sampleStatusResponse("Calculator Monthly");
        when(queryService.getCalculatorStatus("calc-1", "tenant-a",
                CalculatorFrequency.MONTHLY, 3, true)).thenReturn(response);

        mockMvc.perform(get("/api/v1/calculators/calc-1/status")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("frequency", "MONTHLY")
                        .param("historyLimit", "3")
                        .param("bypassCache", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")));

        verify(queryService).getCalculatorStatus("calc-1", "tenant-a",
                CalculatorFrequency.MONTHLY, 3, true);
    }

    @Test
    void getBatchCalculatorStatus_returnsCacheableResponse_whenAllowStaleTrue() throws Exception {
        List<CalculatorStatusResponse> response = List.of(
                sampleStatusResponse("Calc A"),
                sampleStatusResponse("Calc B")
        );
        when(queryService.getBatchCalculatorStatus(
                List.of("calc-a", "calc-b"), "tenant-a",
                CalculatorFrequency.DAILY, 5, true)).thenReturn(response);

        mockMvc.perform(post("/api/v1/calculators/batch/status")
                        .header(TENANT_HEADER, "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("frequency", "DAILY")
                        .param("historyLimit", "5")
                        .param("allowStale", "true")
                        .content(objectMapper.writeValueAsString(List.of("calc-a", "calc-b"))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$[0].calculatorName").value("Calc A"));

        verify(queryService).getBatchCalculatorStatus(
                List.of("calc-a", "calc-b"), "tenant-a",
                CalculatorFrequency.DAILY, 5, true);
    }

    @Test
    void getBatchCalculatorStatus_returnsNoCache_whenAllowStaleFalse() throws Exception {
        List<CalculatorStatusResponse> response = List.of(sampleStatusResponse("Calc Fresh"));
        when(queryService.getBatchCalculatorStatus(
                List.of("calc-fresh"), "tenant-a",
                CalculatorFrequency.DAILY, 5, false)).thenReturn(response);

        mockMvc.perform(post("/api/v1/calculators/batch/status")
                        .header(TENANT_HEADER, "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("frequency", "DAILY")
                        .param("historyLimit", "5")
                        .param("allowStale", "false")
                        .content(objectMapper.writeValueAsString(List.of("calc-fresh"))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")))
                .andExpect(jsonPath("$[0].calculatorName").value("Calc Fresh"));

        verify(queryService).getBatchCalculatorStatus(
                List.of("calc-fresh"), "tenant-a",
                CalculatorFrequency.DAILY, 5, false);
    }

    private static CalculatorStatusResponse sampleStatusResponse(String calculatorName) {
        RunStatusInfo current = new RunStatusInfo(
                "run-1", "RUNNING", Instant.parse("2026-02-22T06:00:00Z"),
                null, null, null, null, null, null, null, null);

        return CalculatorStatusResponse.builder()
                .calculatorName(calculatorName)
                .lastRefreshed(Instant.parse("2026-02-22T06:01:00Z"))
                .current(current)
                .history(Collections.emptyList())
                .build();
    }
}
