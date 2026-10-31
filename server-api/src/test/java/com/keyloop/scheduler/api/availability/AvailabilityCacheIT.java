package com.keyloop.scheduler.api.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.keyloop.scheduler.api.support.Fixtures;
import com.keyloop.scheduler.api.support.IntegrationTest;
import com.keyloop.scheduler.api.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Versioned cache-aside of {@code GET /availability} on real PostgreSQL 18 + Redis 8
 * (spec-availability-ops CAP-1, CAP-2, CAP-3, CAP-5). The repository is a spy, not a
 * mock: it runs the real SQL and only counts the calls. Lock-wait is 2 s here, so a
 * waiter in CAP-3 always gets the owner's result even on a slow CI machine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "scheduler.availability.cache.lock-wait=2s")
@Import(AvailabilityCacheIT.ClockConfig.class)
class AvailabilityCacheIT extends IntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2030, 1, 7);
    private static final Instant DAY_BEFORE = Instant.parse("2030-01-06T12:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(DAY_BEFORE);
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RedisScript<Long> availabilityLockReleaseScript;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JsonMapper json;
    @MockitoSpyBean
    private AvailabilityRepository repository;

    private final HttpClient http = HttpClient.newHttpClient();
    private Fixtures fixtures;
    private UUID dealer;
    private UUID service;
    private UUID vehicleType;
    private UUID bay;
    private UUID tech;

    @BeforeEach
    void seed() {
        clock.set(DAY_BEFORE);
        fixtures = new Fixtures(jdbc);
        // New dealership per test: its own Redis keys, no cleanup needed
        dealer = fixtures.dealership("UTC", "08:00", "17:00");
        vehicleType = fixtures.vehicleType();
        service = fixtures.serviceType(60);
        bay = fixtures.bay(dealer, vehicleType);
        tech = fixtures.technician(dealer, service, vehicleType);
        clearInvocations(repository);
    }

    @Test
    void cap1SecondReadWithinTtlIsServedFromRedis() throws Exception {
        String first = get();
        clearInvocations(repository);
        // Changed behind the cache's back (no version bump): only a cache hit can still offer 09:00
        book("09:00", "10:00");

        String second = get();

        assertThat(second).isEqualTo(first);
        assertThat(starts(second)).contains("09:00");
        verify(repository, never()).findFittingBayIds(any(), any());
        verify(repository, never()).findQualifiedTechnicianIds(any(), any(), any());
        verify(repository, never()).findBookings(any(), any(), any(), any(), any());
    }

    @Test
    void cachedValueIsTheFullDayWithTtlPlusPadding() throws Exception {
        get();

        String key = AvailabilityCache.slotsKey(dealer, DAY, 0, service, vehicleType);
        JsonNode cached = json.readTree(redis.opsForValue().get(key));
        assertThat(cached).hasSize(33);
        assertThat(cached.get(0).get("start").asString()).isEqualTo("2030-01-07T08:00:00Z");
        // ttl 30 s + padding 0..30 s
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(25_000L, 60_000L);
    }

    @Test
    void cap2VersionBumpHidesTheTakenSlotOnTheNextRead() throws Exception {
        assertThat(starts(get())).contains("09:00");
        book("09:00", "10:00");

        // What the relay does for the AppointmentBooked event of that day (scheduler-batch OutboxRelayIT)
        redis.opsForValue().increment(AvailabilityCache.versionKey(dealer, DAY));

        assertThat(starts(get())).contains("08:00", "10:00").doesNotContain("08:15", "09:00", "09:45");
        assertThat(redis.hasKey(AvailabilityCache.slotsKey(dealer, DAY, 1, service, vehicleType))).isTrue();
    }

    @Test
    void cap3TwentyConcurrentMissesCostOneComputation() throws Exception {
        int users = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(users)) {
            for (int i = 0; i < users; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return get();
                }));
            }
            start.countDown();
            List<String> bodies = new ArrayList<>();
            for (Future<String> result : results) {
                bodies.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(bodies).hasSize(users).allSatisfy(body -> assertThat(body).isEqualTo(bodies.getFirst()));
            assertThat(starts(bodies.getFirst())).hasSize(33);
        }
        verify(repository, times(1)).findFittingBayIds(dealer, vehicleType);
    }

    @Test
    void waiterThatTimesOutComputesWithoutWritingTheCache() throws Exception {
        String lock = AvailabilityCache.lockKey(dealer, DAY, 0, service, vehicleType);
        redis.opsForValue().set(lock, "another-request", Duration.ofSeconds(30));

        long began = System.nanoTime();
        String body = get();

        assertThat(Duration.ofNanos(System.nanoTime() - began)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(starts(body)).hasSize(33);
        verify(repository, times(1)).findFittingBayIds(dealer, vehicleType);
        assertThat(redis.hasKey(AvailabilityCache.slotsKey(dealer, DAY, 0, service, vehicleType))).isFalse();
        // Never released by a request that does not own it
        assertThat(redis.opsForValue().get(lock)).isEqualTo("another-request");
    }

    @Test
    void lockOfAnOldVersionDoesNotBlockTheNewOne() throws Exception {
        redis.opsForValue().set(AvailabilityCache.lockKey(dealer, DAY, 0, service, vehicleType), "slow-v0-rebuild",
                Duration.ofSeconds(30));
        redis.opsForValue().increment(AvailabilityCache.versionKey(dealer, DAY));

        long began = System.nanoTime();
        get();

        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(2));
        assertThat(redis.hasKey(AvailabilityCache.slotsKey(dealer, DAY, 1, service, vehicleType))).isTrue();
        assertThat(redis.hasKey(AvailabilityCache.lockKey(dealer, DAY, 1, service, vehicleType))).isFalse();
    }

    @Test
    void luaReleaseDeletesOnlyTheOwnersLock() {
        String lock = AvailabilityCache.lockKey(dealer, DAY, 0, service, vehicleType);
        redis.opsForValue().set(lock, "b7", Duration.ofSeconds(30));

        // A's lock expired and B took it: A's release must leave B's lock alone
        assertThat(redis.execute(availabilityLockReleaseScript, List.of(lock), "a1")).isZero();
        assertThat(redis.opsForValue().get(lock)).isEqualTo("b7");

        assertThat(redis.execute(availabilityLockReleaseScript, List.of(lock), "b7")).isEqualTo(1L);
        assertThat(redis.hasKey(lock)).isFalse();
    }

    @Test
    void cap5SlotThatStartedIsNeverServedFromCache() throws Exception {
        clock.set(Instant.parse(DAY + "T07:00:00Z"));
        assertThat(starts(get()).getFirst()).isEqualTo("08:00");
        clearInvocations(repository);

        clock.set(Instant.parse(DAY + "T10:05:00Z"));
        List<String> starts = starts(get());

        verify(repository, never()).findFittingBayIds(any(), any());
        assertThat(starts.getFirst()).isEqualTo("10:15");
        assertThat(starts).doesNotContain("08:00", "10:00");
    }

    private void book(String from, String to) {
        fixtures.appointment(dealer, service, vehicleType, bay, tech,
                OffsetDateTime.parse(DAY + "T" + from + ":00Z"), OffsetDateTime.parse(DAY + "T" + to + ":00Z"),
                "CONFIRMED");
    }

    private String get() throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/api/v1/availability?dealershipId=" + dealer
                + "&serviceTypeId=" + service + "&vehicleTypeId=" + vehicleType + "&from=" + DAY + "&to=" + DAY);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private List<String> starts(String body) {
        List<String> starts = new ArrayList<>();
        for (JsonNode slot : json.readTree(body).get("days").get(0).get("slots")) {
            starts.add(OffsetDateTime.parse(slot.get("start").asString()).toLocalTime().toString());
        }
        return starts;
    }
}
