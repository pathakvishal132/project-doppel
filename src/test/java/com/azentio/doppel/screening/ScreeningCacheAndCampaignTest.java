package com.azentio.doppel.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.azentio.doppel.campaign.InMemoryCampaignTracker;
import com.jayway.jsonpath.JsonPath;

/** Task 2 behaviour with the in-memory store (offline, no Docker). The Redis variant is RedisIntegrationTest. */
@SpringBootTest
@AutoConfigureMockMvc
class ScreeningCacheAndCampaignTest {

    private static final Instant T0 = Instant.parse("2026-11-14T12:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InMemoryScreeningCache screeningCache;
    @Autowired
    private InMemoryCampaignTracker campaignTracker;

    private int counter;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM alert");
        jdbc.update("DELETE FROM screening");
        jdbc.update("DELETE FROM brand_token WHERE brand_id >= 100");
        jdbc.update("DELETE FROM brand_vpa WHERE brand_id >= 100");
        jdbc.update("DELETE FROM brand WHERE id >= 100");
        jdbc.update("UPDATE brand_registry_version SET version = version + 1 WHERE id = 1");
        screeningCache.clear();
        campaignTracker.clear();
    }

    private ResultActions screen(String requestId, String vpa, String name, String account, Instant ts)
            throws Exception {
        String body = """
                {"requestId":"%s","vpa":"%s","displayName":"%s","registrantAccount":"%s","timestamp":"%s"}
                """.formatted(requestId, vpa, name, account, ts);
        return mvc.perform(post("/api/v1/payee-screenings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private ResultActions screen(String vpa, String name) throws Exception {
        return screen("c-" + (++counter), vpa, name, "123456789012", T0);
    }

    @Test
    void secondScreeningOfSameHandleIsServedFromCacheWithSameDecision() throws Exception {
        screen("zentrabank.refund@okaxis", "Zentra Bank Refunds")
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.decision").value("BLOCK"));
        // key is the lower-cased VPA + display name
        screen("ZentraBank.Refund@OKAXIS", "Zentra Bank Refunds")
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.decision").value("BLOCK"))
                .andExpect(jsonPath("$.score").value(70));
        // different display name -> different key
        screen("zentrabank.refund@okaxis", "Zentra Bank").andExpect(jsonPath("$.cached").value(false));
    }

    @Test
    void replayReturnsIdenticalBodyIncludingCachedFlag() throws Exception {
        String first = screen("r-1", "shopkrat@ybl", "Shop Krat", "123456789012", T0)
                .andReturn().getResponse().getContentAsString();
        String replay = screen("r-1", "shopkrat@ybl", "Shop Krat", "123456789012", T0)
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first).contains("\"cached\":false");
    }

    @Test
    void newBrandIsUsedByNextScreeningDespiteCachedResult() throws Exception {
        screen("quickloan.help@ybl", "Quick Loan Help")
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.cached").value(false));
        screen("quickloan.help@ybl", "Quick Loan Help")
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.cached").value(true)); // the ALLOW result is now cached

        mvc.perform(post("/api/v1/brands").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"QuickLoan Finance","tokens":["quickloan"],"officialVpas":["quickloan@hdfc"]}
                """)).andExpect(status().isCreated());

        screen("quickloan.help@ybl", "Quick Loan Help")
                .andExpect(jsonPath("$.decision").value("REVIEW"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.matchedBrandName").value("QuickLoan Finance"))
                .andExpect(jsonPath("$.reasons", hasItem("BRAND_EXACT:quickloan")));
    }

    @Test
    void thirdDistinctFlaggedVpaFromOneRegistrantAddsCampaignBurst() throws Exception {
        String acc = "111122223333";
        screen("b1", "shopkrat@ybl", "Store", acc, T0).andExpect(jsonPath("$.score").value(40));
        screen("b2", "shpkart@ybl", "Store", acc, T0.plusSeconds(60)).andExpect(jsonPath("$.score").value(40));
        screen("b3", "shopkartt.pay@ybl", "Store", acc, T0.plusSeconds(120))
                .andExpect(jsonPath("$.score").value(80)) // 50 + 30: REVIEW -> BLOCK
                .andExpect(jsonPath("$.decision").value("BLOCK"))
                .andExpect(jsonPath("$.reasons[1]", startsWith("CAMPAIGN_BURST:3 distinct")));

        // another registrant is unaffected
        screen("b4", "shopkrat.x@ybl", "Store", "999988887777", T0.plusSeconds(120))
                .andExpect(jsonPath("$.reasons", not(hasItem(startsWith("CAMPAIGN_BURST")))));
        // ALLOW screenings are not flagged and never count
        screen("b5", "ramesh.kumar@ybl", "Ramesh", "999988887777", T0.plusSeconds(130));
        screen("b6", "anita.rao@ybl", "Anita", "999988887777", T0.plusSeconds(140));
        screen("b7", "shopkrat.y@ybl", "Store", "999988887777", T0.plusSeconds(150))
                .andExpect(jsonPath("$.reasons", not(hasItem(startsWith("CAMPAIGN_BURST")))));
    }

    @Test
    void flaggedScreeningOlderThanWindowNoLongerCounts() throws Exception {
        String acc = "444455556666";
        screen("w1", "shopkrat@ybl", "Store", acc, T0);
        screen("w2", "shpkart@ybl", "Store", acc, T0.plusSeconds(61 * 60));
        // w1 is 62 min older than this request -> only w2 and w3 count
        screen("w3", "shopkartt.pay@ybl", "Store", acc, T0.plusSeconds(62 * 60))
                .andExpect(jsonPath("$.reasons", not(hasItem(startsWith("CAMPAIGN_BURST")))));

        String acc2 = "777788889999";
        screen("w4", "shopkrat@ybl", "Store", acc2, T0);
        screen("w5", "shpkart@ybl", "Store", acc2, T0.plusSeconds(30 * 60));
        // w4 is exactly 60 min older -> still inside the window
        screen("w6", "shopkartt.pay@ybl", "Store", acc2, T0.plusSeconds(60 * 60))
                .andExpect(jsonPath("$.reasons", hasItem(startsWith("CAMPAIGN_BURST:3"))));
    }

    @Test
    void replayedRequestIdDoesNotCountAgain() throws Exception {
        String acc = "121212121212";
        screen("p1", "shopkrat@ybl", "Store", acc, T0);
        screen("p1", "shopkrat@ybl", "Store", acc, T0);   // replay
        screen("p2", "shpkart@ybl", "Store", acc, T0);
        screen("p2", "shpkart@ybl", "Store", acc, T0);    // replay
        screen("p3", "shopkrat@ybl", "Store", acc, T0)    // same VPA, new requestId: still 2 distinct
                .andExpect(jsonPath("$.reasons", not(hasItem(startsWith("CAMPAIGN_BURST")))));

        String third = screen("p4", "shopkartt.pay@ybl", "Store", acc, T0)
                .andReturn().getResponse().getContentAsString();
        List<String> reasons = JsonPath.read(third, "$.reasons");
        assertThat(reasons).contains("CAMPAIGN_BURST:3 distinct flagged VPAs in 60 min");
    }
}
