package com.company.observability.controller;

import com.company.observability.config.TestMetricsConfig;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.CalculatorBatchRunsResponse;
import com.company.observability.dto.response.CalculatorStatusResponse;
import com.company.observability.dto.response.RunStatusInfo;
import com.company.observability.service.CalculatorStateService;
import com.company.observability.service.RunQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RunQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestMetricsConfig.class)
class RunQueryControllerTest {

    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RunQueryService queryService;

    @MockitoBean
    private CalculatorStateService calculatorStateService;

    @Test
    void getCalculatorStatus_returnsCacheableResponse_whenBypassCacheFalse() throws Exception {
        CalculatorStatusResponse response = sampleStatusResponse("Calculator One");
        when(queryService.getCalculatorStatus("calc-1",
                Frequency.DAILY, 5, false)).thenReturn(response);

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

        verify(queryService).getCalculatorStatus("calc-1",
                Frequency.DAILY, 5, false);
    }

    @Test
    void getCalculatorStatus_returnsNoCache_whenBypassCacheTrue() throws Exception {
        CalculatorStatusResponse response = sampleStatusResponse("Calculator Monthly");
        when(queryService.getCalculatorStatus("calc-1",
                Frequency.MONTHLY, 3, true)).thenReturn(response);

        mockMvc.perform(get("/api/v1/calculators/calc-1/status")
                        .header(TENANT_HEADER, "tenant-a")
                        .param("frequency", "MONTHLY")
                        .param("historyLimit", "3")
                        .param("bypassCache", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")));

        verify(queryService).getCalculatorStatus("calc-1",
                Frequency.MONTHLY, 3, true);
    }

    @Test
    void getBatchCalculatorStatus_returnsCacheableResponse_whenAllowStaleTrue() throws Exception {
        List<CalculatorStatusResponse> response = List.of(
                sampleStatusResponse("Calc A"),
                sampleStatusResponse("Calc B")
        );
        when(queryService.getBatchCalculatorStatus(
                List.of("calc-a", "calc-b"),
                Frequency.DAILY, 5, true)).thenReturn(response);

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
                List.of("calc-a", "calc-b"),
                Frequency.DAILY, 5, true);
    }

    @Test
    void getBatchCalculatorStatus_returnsNoCache_whenAllowStaleFalse() throws Exception {
        List<CalculatorStatusResponse> response = List.of(sampleStatusResponse("Calc Fresh"));
        when(queryService.getBatchCalculatorStatus(
                List.of("calc-fresh"),
                Frequency.DAILY, 5, false)).thenReturn(response);

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
                List.of("calc-fresh"),
                Frequency.DAILY, 5, false);
    }

    @Test
    void getCalculatorStatus_missingTenantIdHeader_succeeds() throws Exception {
        when(queryService.getCalculatorStatus("calc-1",
                Frequency.DAILY, 5, false))
                .thenReturn(sampleStatusResponse("Calculator One"));

        mockMvc.perform(get("/api/v1/calculators/calc-1/status")
                        .param("frequency", "DAILY")
                        .param("historyLimit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculatorName").value("Calculator One"));
    }

    @Test
    void getBatchStatus_missingTenantIdHeader_succeeds() throws Exception {
        when(queryService.getBatchCalculatorStatus(
                List.of("calc-1"), Frequency.DAILY, 5, true))
                .thenReturn(List.of(sampleStatusResponse("Calc A")));

        mockMvc.perform(post("/api/v1/calculators/batch/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("frequency", "DAILY")
                        .param("historyLimit", "5")
                        .content(objectMapper.writeValueAsString(List.of("calc-1"))))
                .andExpect(status().isOk());
    }

    @Test
    void batchRuns_returns200WithMapKeyedByCalculatorName() throws Exception {
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capitalcalc", List.of());
        when(calculatorStateService.getState(eq(LocalDate.of(2026, 3, 6)),
                eq(Frequency.DAILY), eq("1"), eq(List.of("capitalcalc"))))
                .thenReturn(Map.of("capitalcalc", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("frequency", "DAILY")
                        .param("run_number", "1")
                        .param("keys", "capitalcalc")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportingDate").value("2026-03-06"))
                .andExpect(jsonPath("$.runNumber").value("1"))
                .andExpect(jsonPath("$.calculators.capitalcalc.calculatorName").value("capitalcalc"))
                .andExpect(jsonPath("$.calculators.capitalcalc.calculatorId").doesNotExist())
                .andExpect(jsonPath("$.calculators.capitalcalc.runs").isArray());
    }

    @Test
    void batchRuns_returns400WhenReportingDateMissing() throws Exception {
        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchRuns_pipeSeparatedKeysParsedToList() throws Exception {
        when(calculatorStateService.getState(any(), any(), isNull(),
                eq(List.of("capital", "modelled-exposure", "portfolio"))))
                .thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("keys", "capital|modelled-exposure|portfolio")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk());

        verify(calculatorStateService).getState(any(), any(), isNull(),
                eq(List.of("capital", "modelled-exposure", "portfolio")));
    }

    @Test
    void batchRuns_omittedRunNumberPassesNullToService() throws Exception {
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capital", List.of());
        when(calculatorStateService.getState(eq(LocalDate.of(2026, 3, 6)),
                eq(Frequency.DAILY), isNull(), eq(List.of("capital"))))
                .thenReturn(Map.of("capital", entry));

        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runNumber").doesNotExist());

        verify(calculatorStateService).getState(any(), any(), isNull(), any());
    }

    @Test
    void batchRuns_acceptsArbitraryRunNumber() throws Exception {
        // @Pattern restriction removed — any run_number value is now accepted (blank→null normalised in service)
        when(calculatorStateService.getState(any(), any(), eq("3"), any()))
                .thenReturn(Map.of());
        mockMvc.perform(get("/api/v1/calculators/batch/runs")
                        .param("reporting_date", "2026-03-06")
                        .param("run_number", "3")
                        .param("keys", "capital")
                        .header(TENANT_HEADER, "t1"))
                .andExpect(status().isOk());
    }

    private static CalculatorStatusResponse sampleStatusResponse(String calculatorName) {
        RunStatusInfo current = new RunStatusInfo(
                "run-1", "RUNNING", Instant.parse("2026-02-22T06:00:00Z"),
                null, null, null, null, null, null, null, null);

        return new CalculatorStatusResponse(
                calculatorName, Instant.parse("2026-02-22T06:01:00Z"),
                current, Collections.emptyList());
    }
}
