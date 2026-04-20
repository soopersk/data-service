package com.company.observability.service.projection;

import com.company.observability.cache.DashboardCacheService;
import com.company.observability.dto.response.CalculatorDashboardResponse;
import com.company.observability.service.DashboardService;
import com.company.observability.service.DashboardService.DashboardResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @BeforeEach
    void setUp() {
        projection = new DashboardProjection(dashboardService, dashboardCacheService);
    }

    @Test
    void getCalculatorDashboard_cacheHit_returnsCachedResponseWithoutCallingService() {
        CalculatorDashboardResponse cached = minimalResponse(DATE);
        when(dashboardCacheService.getStatusResponse("tenant-1", DATE, "DAILY", 1)).thenReturn(cached);

        CalculatorDashboardResponse result = projection.getCalculatorDashboard("tenant-1", DATE, "DAILY", 1);

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(dashboardService);
    }

    @Test
    void getCalculatorDashboard_cacheMiss_buildsAndCachesResult() {
        when(dashboardCacheService.getStatusResponse("tenant-1", DATE, "DAILY", 1)).thenReturn(null);

        DashboardResult domainResult = new DashboardResult(DATE, "DAILY", 1, List.of());
        when(dashboardService.buildDashboard("tenant-1", DATE, "DAILY", 1)).thenReturn(domainResult);

        CalculatorDashboardResponse response = projection.getCalculatorDashboard("tenant-1", DATE, "DAILY", 1);

        assertThat(response).isNotNull();
        assertThat(response.reportingDate()).isEqualTo(DATE);
        assertThat(response.frequency()).isEqualTo("DAILY");
        assertThat(response.runNumber()).isEqualTo(1);
        assertThat(response.sections()).isEmpty();
        verify(dashboardCacheService).putStatusResponse(eq("tenant-1"), eq(DATE), eq("DAILY"), eq(1), any());
    }

    @Test
    void getCalculatorDashboard_run2_usesCorrectCacheKey() {
        when(dashboardCacheService.getStatusResponse("tenant-1", DATE, "DAILY", 2)).thenReturn(null);

        DashboardResult domainResult = new DashboardResult(DATE, "DAILY", 2, List.of());
        when(dashboardService.buildDashboard("tenant-1", DATE, "DAILY", 2)).thenReturn(domainResult);

        projection.getCalculatorDashboard("tenant-1", DATE, "DAILY", 2);

        verify(dashboardCacheService).getStatusResponse("tenant-1", DATE, "DAILY", 2);
        verify(dashboardCacheService).putStatusResponse(eq("tenant-1"), eq(DATE), eq("DAILY"), eq(2), any());
        // Run 1 cache is never touched
        verify(dashboardCacheService, never()).getStatusResponse("tenant-1", DATE, "DAILY", 1);
    }

    private CalculatorDashboardResponse minimalResponse(LocalDate date) {
        return new CalculatorDashboardResponse(date, "Fri 17 Apr 2026", "DAILY", 1, List.of());
    }
}
