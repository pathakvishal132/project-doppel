package com.azentio.doppel.brand;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.azentio.doppel.config.CacheConfig;
import com.azentio.doppel.matching.TextNormalizer;

@Service
public class BrandService {

    private static final Logger log = LoggerFactory.getLogger(BrandService.class);

    private final BrandRepository brandRepository;
    private final RegistryVersion registryVersion;
    private final TransactionTemplate transactionTemplate;

    public BrandService(BrandRepository brandRepository, RegistryVersion registryVersion,
            TransactionTemplate transactionTemplate) {
        this.brandRepository = brandRepository;
        this.registryVersion = registryVersion;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Brand registry for the given registry version, served from the in-memory "brands" cache. Keying on the
     * version means a list loaded before a registry change can never be returned for a newer version.
     */
    @Cacheable(value = CacheConfig.BRANDS_CACHE, key = "#version", sync = true) // one loader, others wait
    @Transactional(readOnly = true)
    public List<Brand> findAll(long version) {
        log.debug("Loading brand registry from database");
        return List.copyOf(brandRepository.findAllByOrderByIdAsc());
    }

    @Transactional(readOnly = true)
    public List<BrandResponse> listBrands() {
        return brandRepository.findAllByOrderByIdAsc().stream().map(BrandResponse::from).toList();
    }

    /**
     * Adds a brand and bumps the registry version in ONE transaction. The next screening reads the new version,
     * so it misses both the brand-list cache and the Redis screening cache and is scored with the new brand.
     * The in-memory brand cache is cleared afterwards only to free memory held by the old version.
     */
    @CacheEvict(value = CacheConfig.BRANDS_CACHE, allEntries = true)
    public BrandResponse create(CreateBrandRequest request) {
        String name = request.name().trim();
        if (brandRepository.existsByNameIgnoreCase(name)) {
            throw new BrandConflictException("Brand '" + name + "' already exists");
        }

        Set<String> tokens = new LinkedHashSet<>();
        for (String token : request.tokens()) {
            String normalized = TextNormalizer.compact(token);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Token '" + token + "' has no letters or digits");
            }
            tokens.add(normalized);
        }

        Set<String> vpas = new LinkedHashSet<>();
        request.officialVpas().forEach(v -> vpas.add(v.trim().toLowerCase(Locale.ROOT)));
        List<String> taken = brandRepository.findAllOfficialVpas().stream()
                .map(v -> v.toLowerCase(Locale.ROOT)).filter(vpas::contains).toList();
        if (!taken.isEmpty()) {
            throw new BrandConflictException("Official VPA already registered to another brand: " + taken);
        }

        try {
            Brand saved = transactionTemplate.execute(status -> {
                Brand brand = brandRepository.saveAndFlush(new Brand(name, tokens, vpas));
                registryVersion.bump();
                return brand;
            });
            log.info("Brand created id={} name={} - registry version bumped", saved.getId(), saved.getName());
            return BrandResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // concurrent create with the same name / VPA lost the race against the unique constraints
            throw new BrandConflictException("Brand '" + name + "' or one of its official VPAs already exists");
        }
    }
}
