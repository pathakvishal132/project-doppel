package com.azentio.doppel.screening;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.azentio.doppel.alert.Alert;
import com.azentio.doppel.alert.AlertRepository;
import com.azentio.doppel.alert.AlertStatus;
import com.azentio.doppel.brand.BrandService;
import com.azentio.doppel.brand.RegistryVersion;
import com.azentio.doppel.common.AccountProtector;
import com.azentio.doppel.matching.Decision;
import com.azentio.doppel.matching.MatchResult;
import com.azentio.doppel.matching.MatchingService;

@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private final ScreeningRepository screeningRepository;
    private final AlertRepository alertRepository;
    private final BrandService brandService;
    private final RegistryVersion registryVersion;
    private final MatchingService matchingService;
    private final AccountProtector accountProtector;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ScreeningService(ScreeningRepository screeningRepository, AlertRepository alertRepository,
            BrandService brandService, RegistryVersion registryVersion, MatchingService matchingService,
            AccountProtector accountProtector, TransactionTemplate transactionTemplate, Clock clock) {
        this.screeningRepository = screeningRepository;
        this.alertRepository = alertRepository;
        this.brandService = brandService;
        this.registryVersion = registryVersion;
        this.matchingService = matchingService;
        this.accountProtector = accountProtector;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Screens a payee. Idempotent on requestId: a repeated requestId returns the originally stored
     * response and creates no new screening or alert (also enforced by the unique DB constraint).
     */
    public ScreeningResponse screen(ScreeningRequest request) {
        var existing = screeningRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            log.info("Duplicate requestId={} - returning original result", request.requestId());
            return ScreeningResponse.from(existing.get());
        }

        long version = registryVersion.current();
        MatchResult result = matchingService.match(request.vpa(), request.displayName(), brandService.findAll(version));

        try {
            ScreeningRecord saved = transactionTemplate.execute(status -> persist(request, result));
            log.info("Screened requestId={} decision={} score={} brandId={}",
                    saved.getRequestId(), saved.getDecision(), saved.getScore(), saved.getMatchedBrandId());
            return ScreeningResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request with the same requestId won the race: return its result.
            return screeningRepository.findByRequestId(request.requestId())
                    .map(ScreeningResponse::from)
                    .orElseThrow(() -> e);
        }
    }

    private ScreeningRecord persist(ScreeningRequest request, MatchResult result) {
        String masked = accountProtector.mask(request.registrantAccount());
        String registrantRef = accountProtector.hmac(request.registrantAccount());
        Instant now = clock.instant();
        String reasons = Reasons.join(result.reasons());

        ScreeningRecord record = screeningRepository.saveAndFlush(new ScreeningRecord(
                request.requestId(), request.vpa(), request.displayName(), masked, registrantRef,
                result.brandId(), result.brandName(), result.decision(), result.score(), reasons,
                request.timestamp(), now));

        if (result.decision() != Decision.ALLOW) {
            alertRepository.saveAndFlush(new Alert(request.requestId(), request.vpa(), request.displayName(),
                    result.brandId(), result.brandName(), result.decision(), result.score(), reasons,
                    masked, registrantRef, AlertStatus.OPEN, now));
        }
        return record;
    }
}
