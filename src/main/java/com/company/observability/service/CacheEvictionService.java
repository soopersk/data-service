// File: src/main/java/com/company/observability/service/CacheEvictionService.java
package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
@Slf4j
@RequiredArgsConstructor
public class CacheEvictionService {
    
    private final CacheManager cacheManager;
    
    @EventListener
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        evictCachesForRun(event.getRun());
    }
    
    @EventListener
    @Async
    public void onRunStarted(RunStartedEvent event) {
        evictCachesForRun(event.getRun());
    }
    
    @EventListener
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        evictCachesForRun(event.getRun());
    }
    
    private void evictCachesForRun(CalculatorRun run) {
        String calculatorId = run.getCalculatorId();
        String tenantId = run.getTenantId();
        String frequency = run.getFrequency();
        
        String cacheName = "recentRuns:" + frequency;
        Cache recentRunsCache = cacheManager.getCache(cacheName);
        
        if (recentRunsCache != null) {
            for (int limit : Arrays.asList(5, 10, 20, 50)) {
                String key = calculatorId + "-" + tenantId + "-" + limit;
                recentRunsCache.evict(key);
            }
            log.debug("Evicted {} cache for calculator {}", cacheName, calculatorId);
        }
        
        Cache batchCache = cacheManager.getCache("batchRecentRuns");
        if (batchCache != null) {
            batchCache.clear();
            log.debug("Cleared batch cache due to update in calculator {}", calculatorId);
        }
        
        Cache avgRuntimeCache = cacheManager.getCache("avgRuntime");
        if (avgRuntimeCache != null) {
            String key = calculatorId + "-" + tenantId;
            avgRuntimeCache.evict(key);
            log.debug("Evicted average runtime cache for calculator {}", calculatorId);
        }
    }
}