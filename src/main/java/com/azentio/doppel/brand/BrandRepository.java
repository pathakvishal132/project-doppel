package com.azentio.doppel.brand;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    List<Brand> findAllByOrderByIdAsc();

    boolean existsByNameIgnoreCase(String name);

    @Query(value = "SELECT vpa FROM brand_vpa", nativeQuery = true)
    List<String> findAllOfficialVpas();
}
