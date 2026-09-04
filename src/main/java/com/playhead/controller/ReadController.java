package com.playhead.controller;

import com.playhead.domain.PlaybackState;
import com.playhead.service.AdmissionControl;
import com.playhead.service.PlaybackReadService;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the read path (FR-3, FR-4, FR-5).
 *
 * <p>Thin by design: it maps requests to {@link PlaybackReadService} and turns the results into
 * status codes. The caching decisions and SQL live in the service, not here.
 *
 * <p>Both endpoints sit behind {@link AdmissionControl} (FR-9). They are the two tiers that get
 * shed first, so that playback writes — the only traffic whose loss is unrecoverable — keep their
 * capacity during a surge.
 */
@RestController
public class ReadController {

    private final PlaybackReadService readService;
    private final AdmissionControl admissionControl;

    public ReadController(PlaybackReadService readService, AdmissionControl admissionControl) {
        this.readService = readService;
        this.admissionControl = admissionControl;
    }

    @GetMapping("/v1/playback/resume/{titleId}")
    public ResponseEntity<PlaybackState> resume(
            @PathVariable String titleId,
            @RequestHeader("X-Profile-Id") String profileId) {

        if (!admissionControl.tryAdmit(AdmissionControl.Tier.RESUME_READ)) {
            return ResponseEntity.status(429).header("Retry-After", "1").build();
        }

        return readService.resume(profileId, titleId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/v1/playback/continue-watching")
    public ResponseEntity<List<PlaybackState>> continueWatching(
            @RequestHeader("X-Profile-Id") String profileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (!admissionControl.tryAdmit(AdmissionControl.Tier.BROWSE_READ)) {
            return ResponseEntity.status(429).header("Retry-After", "1").build();
        }

        return ResponseEntity.ok(readService.continueWatching(profileId, page, size));
    }
}
