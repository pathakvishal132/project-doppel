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

import com.azentio.doppel.config.CacheConfig;
import com.azentio.doppel.matching.TextNormalizer;

@Service
public class BrandService {

    private static final Logger log = LoggerFactory.getLogger(BrandService.class);

    private final BrandRepository brandRepository;

    public BrandService(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    /**
     * Brand registry used by every screening. Served from the in-memory "brands" cache; the database is
     * only read on the first call and after a brand is added.
     */
    @Cacheable(CacheConfig.BRANDS_CACHE)
    @Transactional(readOnly = true)
    public List<Brand> findAll() {
        log.debug("Loading brand registry from database");
        return List.copyOf(brandRepository.findAllByOrderByIdAsc());
    }

    @Transactional(readOnly = true)
    public List<BrandResponse> listBrands() {
        return brandRepository.findAllByOrderByIdAsc().stream().map(BrandResponse::from).toList();
    }

    /**
     * Adds a brand. Deliberately NOT @Transactional: the insert commits inside saveAndFlush, and the cache is
     * evicted only after this method returns, i.e. after the commit. Evicting before the commit could let a
     * concurrent screening re-cache the old registry.
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
            Brand saved = brandRepository.saveAndFlush(new Brand(name, tokens, vpas));
            log.info("Brand created id={} name={} - brand cache evicted", saved.getId(), saved.getName());
            return BrandResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // concurrent create with the same name / VPA lost the race against the unique constraints
            throw new BrandConflictException("Brand '" + name + "' or one of its official VPAs already exists");
        }
    }
}
