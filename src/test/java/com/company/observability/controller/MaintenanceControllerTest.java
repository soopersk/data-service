package com.company.observability.controller;

import com.company.observability.config.TestMetricsConfig;
import com.company.observability.dto.response.PartitionOperationResponse;
import com.company.observability.exception.GlobalExceptionHandler;
import com.company.observability.service.PartitionMaintenanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MaintenanceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TestMetricsConfig.class})
class MaintenanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartitionMaintenanceService service;

    private static final PartitionOperationResponse.PartitionStat SAMPLE_STAT =
            new PartitionOperationResponse.PartitionStat(
                    "calculator_runs_2026_05_10",
                    LocalDate.of(2026, 5, 10),
                    1000L, "8192 bytes", 800L, 200L
            );

    @Test
    void createPartitions_returnsOkWithStructuredResponse() throws Exception {
        var response = new PartitionOperationResponse("create", 150L, 62, List.of(SAMPLE_STAT));
        when(service.createPartitions()).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/maintenance/partitions/create"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("create"))
                .andExpect(jsonPath("$.partitionCount").value(62))
                .andExpect(jsonPath("$.durationMs").value(150))
                .andExpect(jsonPath("$.stats[0].partitionName").value("calculator_runs_2026_05_10"));

        verify(service).createPartitions();
    }

    @Test
    void createPartitions_whenServiceFails_returns500() throws Exception {
        when(service.createPartitions()).thenThrow(new RuntimeException("DB unavailable"));

        mockMvc.perform(post("/api/v1/admin/maintenance/partitions/create"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void dropPartitions_returnsOkWithStructuredResponse() throws Exception {
        var response = new PartitionOperationResponse("drop", 3200L, 58, List.of(SAMPLE_STAT));
        when(service.dropPartitions()).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/maintenance/partitions/drop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("drop"))
                .andExpect(jsonPath("$.partitionCount").value(58))
                .andExpect(jsonPath("$.durationMs").value(3200));

        verify(service).dropPartitions();
    }

    @Test
    void dropPartitions_whenServiceFails_returns500() throws Exception {
        when(service.dropPartitions()).thenThrow(new RuntimeException("DB unavailable"));

        mockMvc.perform(post("/api/v1/admin/maintenance/partitions/drop"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void getStats_returnsOkWithPartitionStats() throws Exception {
        var response = new PartitionOperationResponse("stats", 45L, 62, List.of(SAMPLE_STAT));
        when(service.getStats()).thenReturn(response);

        mockMvc.perform(get("/api/v1/admin/maintenance/partitions/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("stats"))
                .andExpect(jsonPath("$.partitionCount").value(62))
                .andExpect(jsonPath("$.stats").isArray())
                .andExpect(jsonPath("$.stats[0].rowCount").value(1000));

        verify(service).getStats();
    }
}
