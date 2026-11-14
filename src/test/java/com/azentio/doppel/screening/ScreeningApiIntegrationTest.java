package com.azentio.doppel.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ScreeningApiIntegrationTest {

    private static final String ACCOUNT = "987654321098";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InMemoryScreeningCache screeningCache;

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM alert");
        jdbc.update("DELETE FROM screening");
        screeningCache.clear();
    }

    private static String body(String requestId, String vpa, String displayName, String account) {
        return """
                {"requestId":"%s","vpa":"%s","displayName":"%s","registrantAccount":"%s","timestamp":"2026-11-14T12:00:00Z"}
                """.formatted(requestId, vpa, displayName, account);
    }

    private String screen(String requestId, String vpa, String displayName) throws Exception {
        return mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(body(requestId, vpa, displayName, ACCOUNT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void listsAtLeastFifteenBrandsIncludingMandatoryOnes() throws Exception {
        mvc.perform(get("/api/v1/brands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(15))))
                .andExpect(jsonPath("$[0].name").value("Zentra Bank"))
                .andExpect(jsonPath("$[0].matchingTokens", containsInAnyOrder("zentrabank", "zentra")))
                .andExpect(jsonPath("$[0].officialVpas", containsInAnyOrder("zentrabank@zbk", "zentra.support@zbk")))
                .andExpect(jsonPath("$[1].name").value("ShopKart"))
                .andExpect(jsonPath("$[1].matchingTokens", containsInAnyOrder("shopkart")))
                .andExpect(jsonPath("$[1].officialVpas", containsInAnyOrder("shopkart@kotak", "shopkart.refunds@kotak")))
                .andExpect(jsonPath("$[2].name").value("Electra Power"))
                .andExpect(jsonPath("$[2].matchingTokens", containsInAnyOrder("electrapower", "electra")))
                .andExpect(jsonPath("$[2].officialVpas", containsInAnyOrder("electrapower.bills@axis")));
    }

    @Test
    void assignmentExampleIsBlockedAndCreatesOpenAlert() throws Exception {
        mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-001", "zentrabank.refund@okaxis", "Zentra Bank Refunds", ACCOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-001"))
                .andExpect(jsonPath("$.decision").value("BLOCK"))
                .andExpect(jsonPath("$.score").value(70))
                .andExpect(jsonPath("$.matchedBrandId").value(1))
                .andExpect(jsonPath("$.matchedBrandName").value("Zentra Bank"))
                .andExpect(jsonPath("$.reasons", hasItem("BRAND_EXACT:zentrabank")))
                .andExpect(jsonPath("$.maskedAccount").value("XXXXXXXX1098"));

        mvc.perform(get("/api/v1/alerts").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].requestId").value("req-001"))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].decision").value("BLOCK"))
                .andExpect(jsonPath("$.content[0].maskedAccount").value("XXXXXXXX1098"));
    }

    @Test
    void officialHandleIsAllowedAndCreatesNoAlert() throws Exception {
        String response = screen("req-off", "shopkart.refunds@kotak", "ShopKart Refunds");
        assertThat(response).contains("\"decision\":\"ALLOW\"").contains("\"score\":0").contains("OFFICIAL_VPA");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screening", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert", Integer.class)).isZero();
    }

    @Test
    void duplicateRequestIdReturnsOriginalResponseAndNoDuplicateAlert() throws Exception {
        String first = screen("req-dup", "sh0pkart@ybl", "Shop Kart");
        String second = screen("req-dup", "ramesh.kumar@okicici", "Ramesh Kumar"); // different payload
        String third = screen("req-dup", "sh0pkart@ybl", "Shop Kart");

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screening WHERE request_id='req-dup'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert WHERE request_id='req-dup'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void invalidRequestReturns400WithFieldErrorsAndNoAccountEcho() throws Exception {
        String bad = """
                {"requestId":"","vpa":"not a vpa","displayName":"","registrantAccount":"12345abc","timestamp":null}
                """;
        String response = mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("requestId")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("vpa")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("displayName")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("registrantAccount")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("timestamp")))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("12345abc");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screening", Integer.class)).isZero();
    }

    @Test
    void validationBoundaries() throws Exception {
        Map<String, String> invalid = Map.of(
                "vpa", body("r1", "a@b", "Name", ACCOUNT),                        // PSP too short
                "displayName", body("r2", "ab@ybl", "x".repeat(101), ACCOUNT),
                "registrantAccount", body("r3", "ab@ybl", "Name", "12345678"));    // 8 digits
        for (var e : invalid.entrySet()) {
            mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON).content(e.getValue()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(e.getKey()));
        }
        for (String ok : List.of(body("ok1", "ab@ybl", "N", "123456789"),
                body("ok2", "a.b-c_d@okaxis", "x".repeat(100), "123456789012345678"))) {
            mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON).content(ok))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void alertsSupportFiltersPaginationAndNewestFirst() throws Exception {
        screen("a1", "zentrabank.kyc@ybl", "Zentra KYC");     // brand 1, BLOCK
        screen("a2", "shopkrat@ybl", "Store");                // brand 2, REVIEW
        screen("a3", "z3ntrabank@ybl", "Bank");               // brand 1, REVIEW
        screen("a4", "ramesh.kumar@okicici", "Ramesh Kumar"); // ALLOW, no alert

        mvc.perform(get("/api/v1/alerts").param("status", "OPEN").param("brandId", "").param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].requestId").value("a3"))
                .andExpect(jsonPath("$.content[1].requestId").value("a2"))
                .andExpect(jsonPath("$.content[2].requestId").value("a1"));

        mvc.perform(get("/api/v1/alerts").param("brandId", "1"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].requestId", containsInAnyOrder("a1", "a3")));

        mvc.perform(get("/api/v1/alerts").param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].requestId").value("a1"));

        mvc.perform(get("/api/v1/alerts").param("status", "CLOSED"))
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(get("/api/v1/alerts").param("status", "BOGUS")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alerts").param("size", "1000")).andExpect(status().isBadRequest());
    }

    @Test
    void fullAccountNumberNeverLeaksToResponsesDatabaseOrLogs(CapturedOutput output) throws Exception {
        String screening = screen("leak-1", "zentrabank.refund@okaxis", "Zentra Bank Refunds");
        String alerts = mvc.perform(get("/api/v1/alerts")).andReturn().getResponse().getContentAsString();

        assertThat(screening).doesNotContain(ACCOUNT);
        assertThat(alerts).doesNotContain(ACCOUNT).contains("XXXXXXXX1098");

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT * FROM screening"));
        rows.addAll(jdbc.queryForList("SELECT * FROM alert"));
        assertThat(rows).isNotEmpty();
        rows.forEach(row -> row.values().forEach(v -> assertThat(String.valueOf(v)).doesNotContain(ACCOUNT)));

        assertThat(output.getAll()).doesNotContain(ACCOUNT);
    }
    @Test
    void maxLengthVpaAcceptedByRegexIsStored() throws Exception {
        String vpa = "a".repeat(256) + "@" + "b".repeat(64); // 321 chars: regex maximum
        screen("max-vpa", vpa, "Name");
        mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(body("max-vpa-2", "a".repeat(257) + "@ybl", "Name", ACCOUNT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("vpa"));
        assertThat(jdbc.queryForObject("SELECT vpa FROM screening WHERE request_id='max-vpa'", String.class))
                .isEqualTo(vpa);
    }

    @Test
    void timestampWithOffsetAndNumericAccountAreAccepted() throws Exception {
        String json = """
                {"requestId":"tz-1","vpa":"ab@ybl","displayName":"N","registrantAccount":987654321098,
                 "timestamp":"2026-11-14T17:30:00+05:30"}
                """;
        mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedAccount").value("XXXXXXXX1098"));
        assertThat(jdbc.queryForObject("SELECT request_timestamp FROM screening WHERE request_id='tz-1'",
                java.time.OffsetDateTime.class).toInstant()).isEqualTo(java.time.Instant.parse("2026-11-14T12:00:00Z"));
    }

    @Test
    void invalidAccountIsNotLoggedOrEchoed(CapturedOutput output) throws Exception {
        String badAccount = "99887766554433x";
        String response = mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(body("bad-acc", "ab@ybl", "N", badAccount)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("99887766554433");
        assertThat(output.getAll()).doesNotContain("99887766554433");
    }

    @Test
    void concurrentDuplicateRequestsCreateExactlyOneScreeningAndAlert() throws Exception {
        int threads = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return screen("race-1", "sh0pkart.refund@ybl", "ShopKart");
                }));
            }
            start.countDown();
            java.util.Set<String> bodies = new java.util.HashSet<>();
            for (var f : futures) {
                bodies.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(bodies).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screening WHERE request_id='race-1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert WHERE request_id='race-1'", Integer.class))
                .isEqualTo(1);
    }
}
