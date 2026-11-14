package com.azentio.doppel.alert;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {

    static final int MAX_PAGE_SIZE = 100;

    private final AlertRepository alertRepository;

    public AlertService(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AlertResponse> search(AlertStatus status, Long brandId, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // Newest first; id breaks ties between alerts created in the same instant.
        Sort newestFirst = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return PageResponse.from(alertRepository.search(status, brandId, PageRequest.of(page, size, newestFirst))
                .map(AlertResponse::from));
    }
}
