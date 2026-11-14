package com.azentio.doppel.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.azentio.doppel.common.AccountProtector;
import com.jayway.jsonpath.JsonPath;

/**
 * Task 2 against real Redis ("integration" profile, doppel.store=redis).
 * Redis comes from Testcontainers; set DOPPEL_TEST_REDIS_HOST[/PORT] to use an existing Redis instead.
 * Skipped when neither Docker nor an external Redis is available, so `mvn test` stays offline.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@EnabledIf("redisAvailable")
class RedisIntegrationTest {

    private static final String EXTERNAL_HOST = System.getenv("DOPPEL_TEST_REDIS_HOST");
    private static final Instant T0 = Instant.parse("2026-11-14T12:00:00Z");
    private static GenericContainer<?> redisContainer;

    static boolean redisAvailable() {
        return EXTERNAL_HOST != null || DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        if (EXTERNAL_HOST != null) {
            String port = System.getenv().getOrDefault("DOPPEL_TEST_REDIS_PORT", "6379");
            registry.add("spring.data.redis.host", () -> EXTERNAL_HOST);
            registry.add("spring.data.redis.port", () -> port);
        } else {
            redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            redisContainer.start(); // stopped by Testcontainers' Ryuk when the JVM exits
            registry.add("spring.data.redis.host", redisContainer::getHost);
            registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private AccountProtector accountProtector;
    @Autowired
    private ScreeningCache screeningCache;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM alert");
        jdbc.update("DELETE FROM screening");
        jdbc.update("DELETE FROM brand_token WHERE brand_id >= 100");
        jdbc.update("DELETE FROM brand_vpa WHERE brand_id >= 100");
        jdbc.update("DELETE FROM brand WHERE id >= 100");
        jdbc.update("UPDATE brand_registry_version SET version = version + 1 WHERE id = 1");
        Set<String> keys = redis.keys("doppel:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String screen(String requestId, String vpa, String name, String account, Instant ts) throws Exception {
        String body = """
                {"requestId":"%s","vpa":"%s","displayName":"%s","registrantAccount":"%s","timestamp":"%s"}
                """.formatted(requestId, vpa, name, account, ts);
        return mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static boolean hasBurst(String json) {
        List<String> reasons = JsonPath.read(json, "$.reasons");
        return reasons.stream().anyMatch(r -> r.startsWith(ScreeningService.CAMPAIGN_BURST));
    }

    @Test
    void usesRedisImplementation() {
        assertThat(screeningCache).isInstanceOf(RedisScreeningCache.class);
    }

    @Test
    void cachedFalseThenTrueWithSameDecisionAndTtl() throws Exception {
        String first = screen("k1", "zentrabank.refund@okaxis", "Zentra Bank Refunds", "123456789012", T0);
        String second = screen("k2", "zentrabank.refund@okaxis", "Zentra Bank Refunds", "123456789012", T0);
        assertThat((Boolean) JsonPath.read(first, "$.cached")).isFalse();
        assertThat((Boolean) JsonPath.read(second, "$.cached")).isTrue();
        assertThat((String) JsonPath.read(second, "$.decision")).isEqualTo(JsonPath.read(first, "$.decision"));

        Set<String> keys = redis.keys("doppel:screen:*");
        assertThat(keys).hasSize(1);
        long ttlSeconds = redis.getExpire(keys.iterator().next(), TimeUnit.SECONDS);
        assertThat(ttlSeconds).isBetween(24 * 3600L - 60, 24 * 3600L);
    }

    @Test
    void newBrandMakesCachedAllowGoAway() throws Exception {
        screen("q1", "quickloan.help@ybl", "Quick Loan Help", "123456789012", T0);
        String cachedAllow = screen("q2", "quickloan.help@ybl", "Quick Loan Help", "123456789012", T0);
        assertThat((String) JsonPath.read(cachedAllow, "$.decision")).isEqualTo("ALLOW");
        assertThat((Boolean) JsonPath.read(cachedAllow, "$.cached")).isTrue();

        mvc.perform(post("/api/v1/brands").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"QuickLoan Finance","tokens":["quickloan"],"officialVpas":[]}
                """)).andExpect(status().isCreated());

        String after = screen("q3", "quickloan.help@ybl", "Quick Loan Help", "123456789012", T0);
        assertThat((String) JsonPath.read(after, "$.decision")).isEqualTo("REVIEW");
        assertThat((Boolean) JsonPath.read(after, "$.cached")).isFalse();
        List<String> reasons = JsonPath.read(after, "$.reasons");
        assertThat(reasons).contains("BRAND_EXACT:quickloan");
    }

    @Test
    void twentyConcurrentDistinctFlaggedVpasGiveExactlyEighteenBursts() throws Exception {
        String account = "555566667777";
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    // distinct flagged VPAs (BRAND_EXACT 50), timestamps spread over 19 minutes of one window
                    return screen("burst-" + idx, "zentrabank.agent" + idx + "@ybl", "Agent " + idx, account,
                            T0.plusSeconds(idx * 60L));
                }));
            }
            start.countDown();
            int bursts = 0;
            for (Future<String> f : futures) {
                if (hasBurst(f.get(60, TimeUnit.SECONDS))) {
                    bursts++;
                }
            }
            assertThat(bursts).isEqualTo(18);
        } finally {
            pool.shutdownNow();
        }

        String key = "doppel:campaign:" + accountProtector.hmac(account);
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(20L);
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).as("idle registrant key expires").isBetween(1L, 3600L);

        // re-screening one of the VPAs (new requestId) does not change the distinct count
        String again = screen("burst-again", "zentrabank.agent7@ybl", "Agent 7", account, T0.plusSeconds(1200));
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(20L);
        assertThat(hasBurst(again)).isTrue();
        List<String> reasons = JsonPath.read(again, "$.reasons");
        assertThat(reasons).contains("CAMPAIGN_BURST:20 distinct flagged VPAs in 60 min");

        // replaying a requestId does not count again either
        screen("burst-3", "zentrabank.agent3@ybl", "Agent 3", account, T0.plusSeconds(180));
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(20L);
    }

    @Test
    void flaggedScreeningOlderThanSixtyMinutesNoLongerCounts() throws Exception {
        String account = "888877776666";
        screen("o1", "shopkrat@ybl", "Store", account, T0);
        screen("o2", "shpkart@ybl", "Store", account, T0.plusSeconds(61 * 60));
        String third = screen("o3", "shopkartt.pay@ybl", "Store", account, T0.plusSeconds(62 * 60));
        assertThat(hasBurst(third)).isFalse();
        // the expired entry was pruned by the Lua script
        assertThat(redis.opsForZSet().zCard("doppel:campaign:" + accountProtector.hmac(account))).isEqualTo(2L);
    }
}
