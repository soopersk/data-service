package com.company.observability.service.projection;

import com.company.observability.cache.RegionalBatchCacheService;
import com.company.observability.dto.response.RegionalBatchStatusResponse;
import com.company.observability.dto.response.SlaIndicator;
import com.company.observability.service.RegionalBatchService;
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
class RegionalBatchProjectionTest {

    @Mock
    private RegionalBatchService regionalBatchService;

    @Mock
    private RegionalBatchCacheService regionalBatchCacheService;

    private RegionalBatchProjection projection;

    @BeforeEach
    void setUp() {
        projection = new RegionalBatchProjection(regionalBatchService, regionalBatchCacheService);
    }

    @Test
    void getRegionalBatchStatus_cacheHit_returnsCachedResponseWithoutCallingService() {
        LocalDate date = LocalDate.of(2026, 4, 17);
        RegionalBatchStatusResponse cached = minimalResponse(date, 10, 10, 0, 0);

        when(regionalBatchCacheService.getStatusResponse(date)).thenReturn(cached);

        RegionalBatchStatusResponse result = projection.getRegionalBatchStatus(date);

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(regionalBatchService);
    }

    @Test
    void getRegionalBatchStatus_cacheMiss_computesAndCachesResult() {
        LocalDate date = LocalDate.of(2026, 4, 17);
        when(regionalBatchCacheService.getStatusResponse(date)).thenReturn(null);

        var result = new RegionalBatchService.RegionalBatchResult(
                date,
                Instant.parse("2026-04-17T15:45:00Z"),
                false, null, null,
                10, 10, 0, 0,
                List.of(),
                List.of()
        );
        when(regionalBatchService.getRegionalBatchStatus(date)).thenReturn(result);

        RegionalBatchStatusResponse response = projection.getRegionalBatchStatus(date);

        assertThat(response).isNotNull();
        assertThat(response.overallSla()).isEqualTo(
                new SlaIndicator(Instant.parse("2026-04-17T15:45:00Z"), false));
        assertThat(response.totalRegions()).isEqualTo(10);
        verify(regionalBatchCacheService).putStatusResponse(eq(date), any());
    }

    @Test
    void regionalBatchResponse_serializesSharedSlaIndicatorWithoutChangingWireShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        LocalDate date = LocalDate.of(2026, 4, 17);

        String json = mapper.writeValueAsString(minimalResponse(date, 10, 10, 0, 0));

        assertThat(json).contains("\"overallSla\":{\"deadlineTime\":\"2026-04-17T15:45:00.000Z\",\"breached\":false}");
    }

    private RegionalBatchStatusResponse minimalResponse(
            LocalDate date, int total, int completed, int running, int failed) {
        return new RegionalBatchStatusResponse(
                date, "Fri 17 Apr 2026",
                new SlaIndicator(Instant.parse("2026-04-17T15:45:00Z"), false),
                null, null,
                total, completed, running, failed,
                List.of(), List.of()
        );
    }
}
