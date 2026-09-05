package com.playhead.controller;

import com.playhead.service.AdmissionControl;
import com.playhead.service.PopularityService;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for browse traffic that is not part of the playback contract (FR-1 through
 * FR-10) — see {@link PopularityService} for why this feature exists and what it is not (D-034).
 *
 * <p>Sits behind the same {@code BROWSE_READ} tier as {@code continue-watching} (D-034): under
 * overload this is exactly the traffic {@link AdmissionControl} sheds first, since losing it costs
 * nothing a viewer can notice, unlike a dropped playback write.
 */
@RestController
public class BrowseController {

    private final PopularityService popularityService;
    private final AdmissionControl admissionControl;

    public BrowseController(PopularityService popularityService, AdmissionControl admissionControl) {
        this.popularityService = popularityService;
        this.admissionControl = admissionControl;
    }

    @GetMapping("/v1/browse/popular-now")
    public ResponseEntity<List<String>> popularNow() {
        if (!admissionControl.tryAdmit(AdmissionControl.Tier.BROWSE_READ)) {
            return ResponseEntity.status(429).header("Retry-After", "1").build();
        }

        return ResponseEntity.ok(popularityService.topTitles());
    }
}
