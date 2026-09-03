package com.playhead.controller;

import com.playhead.domain.PlaybackState;
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
 */
@RestController
public class ReadController {

    private final PlaybackReadService readService;

    public ReadController(PlaybackReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/v1/playback/resume/{titleId}")
    public ResponseEntity<PlaybackState> resume(
            @PathVariable String titleId,
            @RequestHeader("X-Profile-Id") String profileId) {

        return readService.resume(profileId, titleId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/v1/playback/continue-watching")
    public List<PlaybackState> continueWatching(
            @RequestHeader("X-Profile-Id") String profileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return readService.continueWatching(profileId, page, size);
    }
}
