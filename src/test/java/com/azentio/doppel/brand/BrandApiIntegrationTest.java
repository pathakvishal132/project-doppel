package com.azentio.doppel.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.azentio.doppel.config.CacheConfig;

@SpringBootTest
@AutoConfigureMockMvc
class BrandApiIntegrationTest {

    private static final String VORTEXA = """
            {"name":"Vortexa Telecom","tokens":["Vortexa","vortexa-tel"],"officialVpas":["Vortexa.Pay@AXIS"]}
            """;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CacheManager cacheManager;

    private int requestCounter;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // other test classes share the in-memory DB: remove everything this class creates
        jdbc.update("DELETE FROM alert");
        jdbc.update("DELETE FROM screening");
        jdbc.update("DELETE FROM brand_token WHERE brand_id >= 100");
        jdbc.update("DELETE FROM brand_vpa WHERE brand_id >= 100");
        jdbc.update("DELETE FROM brand WHERE id >= 100");
        cacheManager.getCache(CacheConfig.BRANDS_CACHE).clear();
    }

    private ResultActions createBrand(String json) throws Exception {
        return mvc.perform(post("/api/v1/brands").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions screen(String vpa, String displayName) throws Exception {
        String body = """
                {"requestId":"brand-test-%d","vpa":"%s","displayName":"%s","registrantAccount":"123456789012",
                 "timestamp":"2026-11-14T12:00:00Z"}
                """.formatted(++requestCounter, vpa, displayName);
        return mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private Object cachedRegistry() {
        return cacheManager.getCache(CacheConfig.BRANDS_CACHE).get(SimpleKey.EMPTY);
    }

    @Test
    void createsBrandWith201AndNormalisedValues() throws Exception {
        createBrand(VORTEXA)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/brands/")))
                .andExpect(jsonPath("$.name").value("Vortexa Telecom"))
                .andExpect(jsonPath("$.matchingTokens", containsInAnyOrder("vortexa", "vortexatel")))
                .andExpect(jsonPath("$.officialVpas", containsInAnyOrder("vortexa.pay@axis")));

        Integer id = jdbc.queryForObject("SELECT id FROM brand WHERE name='Vortexa Telecom'", Integer.class);
        assertThat(id).isGreaterThanOrEqualTo(100);

        mvc.perform(get("/api/v1/brands")).andExpect(jsonPath("$[*].name", hasItem("Vortexa Telecom")));
    }

    @Test
    void nextScreeningUsesNewBrandWithoutRestart() throws Exception {
        screen("vortexa.kyc@ybl", "Vortexa KYC").andExpect(jsonPath("$.decision").value("ALLOW"));
        assertThat(cachedRegistry()).as("registry cached after first screening").isNotNull();

        createBrand(VORTEXA).andExpect(status().isCreated());
        assertThat(cachedRegistry()).as("cache evicted after create").isNull();

        screen("vortexa.kyc@ybl", "Vortexa KYC")
                .andExpect(jsonPath("$.decision").value("BLOCK"))
                .andExpect(jsonPath("$.score").value(70))
                .andExpect(jsonPath("$.matchedBrandName").value("Vortexa Telecom"));
        screen("vortexa.pay@axis", "Vortexa")
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.reasons[0]").value("OFFICIAL_VPA"));
    }

    @Test
    void screeningIsServedFromCacheNotDatabase() throws Exception {
        screen("ab@ybl", "Warm cache"); // loads the registry into the cache

        // insert directly, bypassing the API and therefore its cache eviction
        jdbc.update("INSERT INTO brand (id, name) VALUES (500, 'Hidden Brand')");
        jdbc.update("INSERT INTO brand_token (brand_id, token) VALUES (500, 'hiddenbrand')");

        screen("hiddenbrand.kyc@ybl", "x").andExpect(jsonPath("$.decision").value("ALLOW")); // stale cache wins

        cacheManager.getCache(CacheConfig.BRANDS_CACHE).clear();
        screen("hiddenbrand.kyc@ybl", "x").andExpect(jsonPath("$.decision").value("BLOCK"));
    }

    @Test
    void duplicateNameIsRejectedWith409CaseInsensitively() throws Exception {
        createBrand(VORTEXA).andExpect(status().isCreated());
        createBrand("""
                {"name":"  vortexa TELECOM ","tokens":["other"],"officialVpas":[]}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CONFLICT"));
        createBrand("""
                {"name":"Zentra Bank","tokens":["zentra"],"officialVpas":[]}
                """).andExpect(status().isConflict());
    }

    @Test
    void officialVpaOwnedByAnotherBrandIsRejectedWith409() throws Exception {
        createBrand("""
                {"name":"Copycat","tokens":["copycat"],"officialVpas":["ZentraBank@zbk"]}
                """).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand WHERE name='Copycat'", Integer.class)).isZero();
    }

    @Test
    void invalidBrandReturns400WithFieldErrors() throws Exception {
        createBrand("""
                {"name":"","tokens":[],"officialVpas":["not-a-vpa"]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("tokens")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("officialVpas[0]")));

        createBrand("""
                {"name":"X","tokens":["  "]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("tokens[0]")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("officialVpas")));

        createBrand("""
                {"name":"Symbols","tokens":["!!!"],"officialVpas":[]}
                """).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand WHERE id >= 100", Integer.class)).isZero();
    }
}
