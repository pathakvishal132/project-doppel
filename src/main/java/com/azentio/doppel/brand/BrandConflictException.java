package com.azentio.doppel.brand;

/** Mapped to HTTP 409. */
public class BrandConflictException extends RuntimeException {

    public BrandConflictException(String message) {
        super(message);
    }
}
