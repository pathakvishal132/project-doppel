package com.azentio.doppel.brand;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandService {

    private final BrandRepository brandRepository;

    public BrandService(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    @Transactional(readOnly = true)
    public List<Brand> findAll() {
        return brandRepository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public List<BrandResponse> listBrands() {
        return findAll().stream().map(BrandResponse::from).toList();
    }
}
