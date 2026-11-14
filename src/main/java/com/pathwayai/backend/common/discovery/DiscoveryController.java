package com.pathwayai.backend.common.discovery;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, additive: lets a client explain why the catalogue is empty or partial. No other resource changes. */
@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {

    private final DiscoveryStatusService status;

    public DiscoveryController(DiscoveryStatusService status) {
        this.status = status;
    }

    @GetMapping("/status")
    public DiscoveryStatusService.Status status() {
        return status.status();
    }
}
