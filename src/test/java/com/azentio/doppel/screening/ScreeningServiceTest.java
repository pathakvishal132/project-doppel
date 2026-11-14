package com.azentio.doppel.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.azentio.doppel.TestFixtures;
import com.azentio.doppel.alert.Alert;
import com.azentio.doppel.alert.AlertRepository;
import com.azentio.doppel.alert.AlertStatus;
import com.azentio.doppel.brand.BrandService;
import com.azentio.doppel.brand.RegistryVersion;
import com.azentio.doppel.campaign.InMemoryCampaignTracker;
import com.azentio.doppel.common.AccountProtector;
import com.azentio.doppel.matching.Decision;
import com.azentio.doppel.matching.MatchingService;

@ExtendWith(MockitoExtension.class)
class ScreeningServiceTest {

    private static final String ACCOUNT = "123456789012";
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:05Z");

    @Mock
    private ScreeningRepository screeningRepository;
    @Mock
    private AlertRepository alertRepository;
    @Mock
    private BrandService brandService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private RegistryVersion registryVersion;

    private ScreeningService service;

    @BeforeEach
    void setUp() {
        var props = TestFixtures.properties();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ScreeningService(screeningRepository, alertRepository, brandService, registryVersion,
                new MatchingService(props), new InMemoryScreeningCache(clock), new InMemoryCampaignTracker(),
                new AccountProtector(props), props, new TransactionTemplate(transactionManager), clock);
    }

    private static ScreeningRequest request(String requestId, String vpa, String displayName) {
        return new ScreeningRequest(requestId, vpa, displayName, ACCOUNT, Instant.parse("2026-11-14T12:00:00Z"));
    }

    private void stubPersistence() {
        when(brandService.findAll(0L)).thenReturn(TestFixtures.mandatoryBrands());
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(screeningRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void blockDecisionStoresScreeningAndOpenAlertWithoutFullAccount() {
        stubPersistence();
        when(screeningRepository.findByRequestId("req-001")).thenReturn(Optional.empty());

        ScreeningResponse response = service.screen(
                request("req-001", "zentrabank.refund@okaxis", "Zentra Bank Refunds"));

        assertThat(response.decision()).isEqualTo(Decision.BLOCK);
        assertThat(response.score()).isEqualTo(70);
        assertThat(response.matchedBrandName()).isEqualTo("Zentra Bank");
        assertThat(response.maskedAccount()).isEqualTo("XXXXXXXX9012");
        assertThat(response.toString()).doesNotContain(ACCOUNT);

        ArgumentCaptor<ScreeningRecord> screening = ArgumentCaptor.forClass(ScreeningRecord.class);
        verify(screeningRepository).saveAndFlush(screening.capture());
        ScreeningRecord saved = screening.getValue();
        assertThat(saved.getMaskedAccount()).isEqualTo("XXXXXXXX9012");
        assertThat(saved.getRegistrantRef()).hasSize(64).doesNotContain(ACCOUNT);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);

        ArgumentCaptor<Alert> alert = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).saveAndFlush(alert.capture());
        Alert a = alert.getValue();
        assertThat(a.getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(a.getDecision()).isEqualTo(Decision.BLOCK);
        assertThat(a.getBrandId()).isEqualTo(1L);
        assertThat(a.getReasons()).isEqualTo("BRAND_EXACT:zentrabank;LURE_KEYWORD:refund");
        assertThat(a.getMaskedAccount()).isEqualTo("XXXXXXXX9012");
        assertThat(a.getRegistrantRef()).isEqualTo(saved.getRegistrantRef());
    }

    @Test
    void allowDecisionDoesNotCreateAlert() {
        stubPersistence();
        when(screeningRepository.findByRequestId("req-002")).thenReturn(Optional.empty());

        ScreeningResponse response = service.screen(request("req-002", "ramesh.kumar@okicici", "Ramesh Kumar"));

        assertThat(response.decision()).isEqualTo(Decision.ALLOW);
        verify(screeningRepository).saveAndFlush(any());
        verify(alertRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateRequestIdReturnsOriginalWithoutRescreeningOrSaving() {
        ScreeningRecord original = new ScreeningRecord("req-001", "zentrabank.refund@okaxis", "Zentra Bank Refunds",
                "XXXXXXXX9012", "ref", 1L, "Zentra Bank", Decision.BLOCK, 70, "BRAND_EXACT:zentrabank",
                Instant.parse("2026-11-14T12:00:00Z"), NOW, false);
        when(screeningRepository.findByRequestId("req-001")).thenReturn(Optional.of(original));

        // different payload, same requestId -> original response
        ScreeningResponse response = service.screen(request("req-001", "ramesh.kumar@okicici", "Ramesh"));

        assertThat(response.decision()).isEqualTo(Decision.BLOCK);
        assertThat(response.vpa()).isEqualTo("zentrabank.refund@okaxis");
        verify(screeningRepository, never()).saveAndFlush(any());
        verifyNoInteractions(alertRepository, brandService, registryVersion);
    }

    @Test
    void concurrentDuplicateHittingUniqueConstraintReturnsStoredResult() {
        when(brandService.findAll(0L)).thenReturn(TestFixtures.mandatoryBrands());
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ScreeningRecord winner = new ScreeningRecord("req-009", "sh0pkart@ybl", "Shop", "XXXXXXXX9012", "ref",
                2L, "ShopKart", Decision.REVIEW, 60, "HOMOGLYPH:shopkart", Instant.parse("2026-11-14T12:00:00Z"), NOW, false);
        when(screeningRepository.findByRequestId("req-009")).thenReturn(Optional.empty(), Optional.of(winner));
        when(screeningRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));

        ScreeningResponse response = service.screen(request("req-009", "sh0pkart@ybl", "Shop"));

        assertThat(response.decision()).isEqualTo(Decision.REVIEW);
        verify(alertRepository, never()).saveAndFlush(any());
    }
}
