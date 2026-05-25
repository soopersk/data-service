package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.RunPerformanceData;
import com.company.observability.dto.response.RunPerformanceData.RunDataPoint;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.domain.SlaEvaluationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AnalyticsCacheService service;
    private ObjectMapper objectMapper;

    // Simple response type for getFromCache JSON round-trip tests
    record SimpleResponse(String name) {}

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new AnalyticsCacheService(redisTemplate, objectMapper, new SimpleMeterRegistry());
        // lenient: used by eviction tests only — getFromCache/putInCache tests don't need opsForSet from setUp
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void onRunStarted_evictsRunPerfAndExecutionsKeysByPrefix_forBothIdAndNameIndexes() {
        // run has calculatorId="calc-1", calculatorName="Calculator" (different → both indexes evicted)
        CalculatorRun run = run("calc-1", "tenant-a");
        String idIndex   = "obs:analytics:index:calc-1";
        String nameIndex = "obs:analytics:index:Calculator";
        Set<String> idKeys = new LinkedHashSet<>(List.of(
                "obs:analytics:run-perf:calc-1:DAILY:30",
                "obs:analytics:runtime:calc-1:DAILY:30"
        ));
        Set<String> nameKeys = new LinkedHashSet<>(List.of(
                "obs:analytics:executions:Calculator:DAILY:30:all"
        ));

        when(setOperations.members(idIndex)).thenReturn(idKeys);
        when(setOperations.members(nameIndex)).thenReturn(nameKeys);

        service.onRunStarted(new RunStartedEvent(run));

        // run-perf prefix evicted from id-index
        verify(redisTemplate).delete(List.of("obs:analytics:run-perf:calc-1:DAILY:30"));
        // executions prefix evicted from name-index
        verify(redisTemplate).delete(List.of("obs:analytics:executions:Calculator:DAILY:30:all"));
    }

    @Test
    void onRunCompleted_evictsAllKeys_fromBothIdIndexAndNameIndex() {
        CalculatorRun run = run("calc-1", "tenant-a");
        String idIndex   = "obs:analytics:index:calc-1";
        String nameIndex = "obs:analytics:index:Calculator";
        Set<String> idKeys = new LinkedHashSet<>(List.of(
                "obs:analytics:run-perf:calc-1:DAILY:30",
                "obs:analytics:runtime:calc-1:DAILY:30"
        ));
        Set<String> nameKeys = new LinkedHashSet<>(List.of(
                "obs:analytics:executions:Calculator:DAILY:30:all"
        ));

        when(setOperations.members(idIndex)).thenReturn(idKeys);
        when(setOperations.members(nameIndex)).thenReturn(nameKeys);

        service.onRunCompleted(new RunCompletedEvent(run));

        // id-index: all keys + the index itself deleted
        verify(redisTemplate).delete(List.of(
                "obs:analytics:run-perf:calc-1:DAILY:30",
                "obs:analytics:runtime:calc-1:DAILY:30",
                idIndex
        ));
        // name-index: all keys + the index itself deleted
        verify(redisTemplate).delete(List.of(
                "obs:analytics:executions:Calculator:DAILY:30:all",
                nameIndex
        ));
    }

    @Test
    void onSlaBreached_evictsAllKeys_fromBothIdIndexAndNameIndex() {
        CalculatorRun run = run("calc-1", "tenant-a");
        String idIndex   = "obs:analytics:index:calc-1";
        String nameIndex = "obs:analytics:index:Calculator";
        Set<String> idKeys = new LinkedHashSet<>(List.of("obs:analytics:runtime:calc-1:30"));
        Set<String> nameKeys = new LinkedHashSet<>(List.of("obs:analytics:executions:Calculator:DAILY:30:all"));

        when(setOperations.members(idIndex)).thenReturn(idKeys);
        when(setOperations.members(nameIndex)).thenReturn(nameKeys);

        service.onSlaBreached(new SlaBreachedEvent(run, new SlaEvaluationResult(true, "b", "HIGH")));

        verify(redisTemplate).delete(List.of("obs:analytics:runtime:calc-1:30", idIndex));
        verify(redisTemplate).delete(List.of("obs:analytics:executions:Calculator:DAILY:30:all", nameIndex));
    }

    // ---------------------------------------------------------------
    // putInCache — write-through to Redis (stores JSON string)
    // ---------------------------------------------------------------

    @Test
    void putInCache_storesJsonAndTracksKeyInIndex() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        SimpleResponse response = new SimpleResponse("payload");
        service.putInCache("runtime", "calc-1", 30, response);

        String expectedKey   = "obs:analytics:runtime:calc-1:30";
        String expectedIndex = "obs:analytics:index:calc-1";
        String expectedJson  = objectMapper.writeValueAsString(response);

        verify(valueOperations).set(expectedKey, expectedJson, Duration.ofMinutes(5));
        verify(setOperations).add(expectedIndex, expectedKey);
        verify(redisTemplate).expire(expectedIndex, Duration.ofHours(1));
    }

    // ---------------------------------------------------------------
    // getFromCache — read paths (deserialize JSON to typed response)
    // ---------------------------------------------------------------

    @Test
    void getFromCache_cacheHit_deserializesToTypedResponse() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("obs:analytics:runtime:calc-1:30"))
                .thenReturn(objectMapper.writeValueAsString(new SimpleResponse("hello")));

        SimpleResponse result = service.getFromCache("runtime", "calc-1", 30, SimpleResponse.class);

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("hello");
    }

    @Test
    void getFromCache_cacheMiss_returnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        SimpleResponse result = service.getFromCache("runtime", "calc-1", 30, SimpleResponse.class);

        assertThat(result).isNull();
    }

    @Test
    void getFromCache_malformedJson_swallowedAndReturnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("{not valid json");

        SimpleResponse result = service.getFromCache("runtime", "calc-1", 30, SimpleResponse.class);

        assertThat(result).isNull();
    }

    // ---------------------------------------------------------------
    // Executions round-trip — the regression this fix addresses.
    // A record with a populated List<record> field must survive put→get.
    // ---------------------------------------------------------------

    @Test
    void executionsRoundTrip_recordWithPopulatedRunsList_survivesSerialization() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        RunDataPoint dp = new RunDataPoint(
                "run-1", LocalDate.of(2026, 5, 1),
                Instant.parse("2026-05-01T04:00:00Z"), Instant.parse("2026-05-01T05:00:00Z"),
                3_600_000L, "SUCCESS", false, "ON_TIME",
                null, Instant.parse("2026-05-01T04:00:00Z"), Instant.parse("2026-05-01T06:00:00Z"),
                "1", 3_600_000L);
        RunPerformanceData response = new RunPerformanceData(
                "Calc", "Calc", "DAILY", 30, 3_600_000L, 1, 0, 1, 0, 0,
                List.of(dp), Instant.parse("2026-05-01T04:00:00Z"), Instant.parse("2026-05-01T06:00:00Z"));

        String key = "obs:analytics:executions:Calc:DAILY:30:all";

        // Capture the JSON the service writes, then feed it back on read — a true round-trip.
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        service.putInCache("executions", "Calc", "DAILY", 30, null, response);
        verify(valueOperations).set(eq(key), jsonCaptor.capture(), eq(Duration.ofMinutes(5)));

        when(valueOperations.get(key)).thenReturn(jsonCaptor.getValue());
        RunPerformanceData result =
                service.getFromCache("executions", "Calc", "DAILY", 30, null, RunPerformanceData.class);

        assertThat(result).isNotNull();
        assertThat(result.runs()).hasSize(1);
        assertThat(result.runs().get(0).runId()).isEqualTo("run-1");
        assertThat(result.runs().get(0).status()).isEqualTo("SUCCESS");
        assertThat(result.runs().get(0).startTime()).isEqualTo(Instant.parse("2026-05-01T04:00:00Z"));
        assertThat(result.totalRuns()).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Helpers — eviction tests
    // ---------------------------------------------------------------

    private CalculatorRun run(String calculatorId, String tenantId) {
        return CalculatorRun.builder()
                .runId("run-1")
                .calculatorId(calculatorId)
                .calculatorName("Calculator")
                .tenantId(tenantId)
                .frequency(Frequency.DAILY)
                .reportingDate(LocalDate.of(2026, 4, 5))
                .startTime(Instant.parse("2026-04-05T04:00:00Z"))
                .status(RunStatus.RUNNING)
                .build();
    }
}

