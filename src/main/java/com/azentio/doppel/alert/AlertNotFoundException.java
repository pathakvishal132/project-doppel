package com.azentio.doppel.alert;

public class AlertNotFoundException extends RuntimeException {

    public AlertNotFoundException(Long id) {
        super("Alert " + id + " not found");
    }
}
