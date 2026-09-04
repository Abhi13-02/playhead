package com.playhead.controller;

import com.playhead.domain.ScheduledEvent;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * The tentpole calendar (FR-7): register an upcoming event, and read back what is coming.
 *
 * <p>Deliberately outside {@link com.playhead.service.AdmissionControl}. Every other endpoint sits
 * behind a priority tier because it carries viewer traffic that spikes; this one is called a
 * handful of times by an operator and once every polling interval by the scaling controller. Rate
 * limiting it would risk shedding the very request that tells the system to grow — the control
 * plane must not be throttled by the surge it exists to absorb.
 *
 * <p>Reads and writes {@code scheduled_event} through {@link JdbcClient}, the same way
 * {@code FoldConsumer} talks to Postgres (D-020 — no ORM).
 */
@RestController
public class EventScheduleController {

    private static final String INSERT = """
            insert into scheduled_event (name, starts_at, expected_peak_rps, title_ids)
            values (?, ?, ?, ?)
            returning id
            """;

    private static final String SELECT_UPCOMING = """
            select id, name, starts_at, expected_peak_rps, title_ids
            from scheduled_event
            where starts_at >= ?
            order by starts_at
            """;

    private final JdbcClient jdbcClient;

    public EventScheduleController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostMapping("/v1/events")
    public ResponseEntity<ScheduledEvent> register(@Valid @RequestBody ScheduledEvent event) {
        Long id = jdbcClient.sql(INSERT)
                .param(event.name())
                .param(java.sql.Timestamp.from(event.startsAt()))
                .param(event.expectedPeakRps())
                .param(event.titleIds() == null ? "" : String.join(",", event.titleIds()))
                .query(Long.class)
                .single();

        ScheduledEvent saved = new ScheduledEvent(
                id, event.name(), event.startsAt(), event.expectedPeakRps(), event.titleIds());

        return ResponseEntity.created(URI.create("/v1/events/" + id)).body(saved);
    }

    /**
     * Events starting from now onward, soonest first. The scaling controller polls this;
     * {@code withinSeconds} lets it ask only for the window it can still act on.
     *
     * <p>{@code lookbackSeconds} also returns events that started that recently — which the
     * controller needs and a human caller does not, hence the default of 0. Without it an event
     * disappears from this list the instant it begins, and a controller that sizes capacity from
     * this list therefore scales *down* exactly as the surge arrives. That is not hypothetical:
     * the first unattended run did precisely that, dropping 3 replicas to 1 nine seconds after
     * the event started (ENGINEERING_LOG.md).
     */
    @GetMapping("/v1/events/upcoming")
    public List<ScheduledEvent> upcoming(
            @RequestParam(required = false) Integer withinSeconds,
            @RequestParam(defaultValue = "0") int lookbackSeconds) {

        Instant now = Instant.now();
        List<ScheduledEvent> events = jdbcClient.sql(SELECT_UPCOMING)
                .param(java.sql.Timestamp.from(now.minusSeconds(lookbackSeconds)))
                .query((rs, rowNum) -> new ScheduledEvent(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getInt("expected_peak_rps"),
                        splitTitleIds(rs.getString("title_ids"))))
                .list();

        if (withinSeconds == null) {
            return events;
        }
        Instant cutoff = now.plusSeconds(withinSeconds);
        return events.stream().filter(e -> !e.startsAt().isAfter(cutoff)).toList();
    }

    private static List<String> splitTitleIds(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return List.of(stored.split(","));
    }
}
