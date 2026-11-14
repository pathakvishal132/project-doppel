package com.azentio.doppel.brand;

import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "brand")
public class Brand {

    @Id
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "brand_token", joinColumns = @JoinColumn(name = "brand_id"))
    @Column(name = "token")
    private Set<String> tokens = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "brand_vpa", joinColumns = @JoinColumn(name = "brand_id"))
    @Column(name = "vpa")
    private Set<String> officialVpas = new LinkedHashSet<>();

    protected Brand() {
    }

    public Brand(Long id, String name, Set<String> tokens, Set<String> officialVpas) {
        this.id = id;
        this.name = name;
        this.tokens = new LinkedHashSet<>(tokens);
        this.officialVpas = new LinkedHashSet<>(officialVpas);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<String> getTokens() {
        return tokens;
    }

    public Set<String> getOfficialVpas() {
        return officialVpas;
    }
}
