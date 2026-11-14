package com.azentio.doppel.screening;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

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
import com.azentio.doppel.config.DoppelProperties;
import com.azentio.doppel.matching.Decision;
import com.azentio.doppel.matching.MatchingService;

@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private final ScreeningRepository screeningRepository;
    private final AlertRepository alertRepository;
    private final BrandService brandService;
    private final RegistryVersion registryVersion;
    private final MatchingService matchingService;
    private final ScreeningCache screeningCache;
    private final AccountProtector accountProtector;
    private final DoppelProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ScreeningService(ScreeningRepository screeningRepository, AlertRepository alertRepository,
            BrandService brandService, RegistryVersion registryVersion, MatchingService matchingService,
            ScreeningCache screeningCache, AccountProtector accountProtector,
            DoppelProperties properties, TransactionTemplate transactionTemplate, Clock clock) {
        this.screeningRepository = screeningRepository;
        this.alertRepository = alertRepository;
        this.brandService = brandService;
        this.registryVersion = registryVersion;
        this.matchingService = matchingService;
        this.screeningCache = screeningCache;
        this.accountProtector = accountProtector;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Screens a payee.
     * <ol>
     *   <li>Repeated requestId → original stored response (no re-scoring, no new alert).</li>
     *   <li>Brand matching → from the screening cache when possible (key includes the registry version).</li>
     *   <li>Decision, persistence and alerting → on every request.</li>
     * </ol>
     */
    public ScreeningResponse screen(ScreeningRequest request) {
        var existing = screeningRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            log.info("Duplicate requestId={} - returning original result", request.requestId());
            return ScreeningResponse.from(existing.get());
        }

        // 1. Brand matching (cacheable part)
        long version = registryVersion.current();
        String cacheKey = cacheKey(version, request.vpa(), request.displayName());
        Optional<CachedMatch> hit = screeningCache.get(cacheKey);
        CachedMatch match = hit.orElseGet(() -> {
            CachedMatch computed = CachedMatch.from(
                    matchingService.match(request.vpa(), request.displayName(), brandService.findAll(version)));
            screeningCache.put(cacheKey, computed, properties.screeningCacheTtl());
            return computed;
        });

        // 2. Decision + persistence (never cached)
        String registrantRef = accountProtector.hmac(request.registrantAccount());
        int score = match.baseScore();
        Decision decision = matchingService.decide(score);
        Scored scored = new Scored(match, score, decision, match.reasons(), hit.isPresent(), registrantRef);
        try {
            ScreeningRecord saved = transactionTemplate.execute(status -> persist(request, scored));
            log.info("Screened requestId={} decision={} score={} brandId={} cached={}",
                    saved.getRequestId(), saved.getDecision(), saved.getScore(), saved.getMatchedBrandId(),
                    saved.isCached());
            return ScreeningResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request with the same requestId won the race: return its result.
            return screeningRepository.findByRequestId(request.requestId())
                    .map(ScreeningResponse::from)
                    .orElseThrow(() -> e);
        }
    }

    private record Scored(CachedMatch match, int score, Decision decision, java.util.List<String> reasons, boolean cached,
            String registrantRef) {
    }

    private ScreeningRecord persist(ScreeningRequest request, Scored s) {
        String masked = accountProtector.mask(request.registrantAccount());
        Instant now = clock.instant();
        String reasons = Reasons.join(s.reasons());

        ScreeningRecord record = screeningRepository.saveAndFlush(new ScreeningRecord(
                request.requestId(), request.vpa(), request.displayName(), masked, s.registrantRef(),
                s.match().brandId(), s.match().brandName(), s.decision(), s.score(), reasons,
                request.timestamp(), now, s.cached()));

        if (s.decision() != Decision.ALLOW) {
            alertRepository.saveAndFlush(new Alert(request.requestId(), request.vpa(), request.displayName(),
                    s.match().brandId(), s.match().brandName(), s.decision(), s.score(), reasons,
                    masked, s.registrantRef(), AlertStatus.OPEN, now));
        }
        return record;
    }

    /**
     * doppel:screen:v{registryVersion}:{sha256(lower-cased VPA + '\n' + displayName)}. The display name is hashed,
     * so keys have a fixed size and contain no user text.
     */
    static String cacheKey(long registryVersion, String vpa, String displayName) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest((vpa.toLowerCase(Locale.ROOT) + "\n" + displayName)
                    .getBytes(StandardCharsets.UTF_8));
            return "doppel:screen:v" + registryVersion + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
