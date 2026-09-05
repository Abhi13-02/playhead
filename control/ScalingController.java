import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-scaling controller (FR-8, NFR-6, DIFF-2). Reads the tentpole calendar and raises capacity
 * before an event starts, unattended.
 *
 * <p>Runs on the host rather than in a container, because its whole job is to drive
 * {@code docker compose}. Putting it inside the thing it scales would mean mounting the Docker
 * socket into a replica the controller itself might later stop.
 *
 * <pre>java control/ScalingController.java</pre>
 *
 * <h2>Why this is not autoscaling</h2>
 * A reactive autoscaler pays four delays in series before new capacity helps: the metric lags
 * reality, the scrape samples that metric only every so often, a stabilisation window waits to
 * confirm the spike is real, and only then does a replica start booting. Against a ramp that
 * reaches peak in 60 seconds, that chain is the whole event. This controller skips the first three
 * outright — it is not detecting anything, it already knows the start time — and pays only the
 * boot:
 *
 * <pre>lead = poll interval + measured container start + safety margin</pre>
 *
 * <p>Every term is a deliberate choice or a measurement. CONTAINER_START_SECONDS is the measured
 * one: time from the scale command to a replica passing its healthcheck, recorded in
 * BENCHMARKS.md rather than guessed. Each is overridable with -D so the lead time can be
 * re-derived on different hardware instead of inherited from this laptop.
 *
 * <h2>Brain and hand</h2>
 * {@link #desiredReplicas} and the lead-time arithmetic are the brain: pure decisions with no idea
 * an orchestrator exists. {@link #scaleTo} is the hand, and the only method that knows about
 * Compose. Moving this to ECS or an EC2 Auto Scaling group replaces that one method and nothing
 * else.
 */
public class ScalingController {

    /** Sustained requests/sec one replica serves under mixed traffic (BENCHMARKS.md, 2026-09-04). */
    static final int PER_INSTANCE_RPS = Integer.getInteger("perInstanceRps", 7000);

    /**
     * Measured, not guessed (NFR-6): time from the scale command to a new replica passing its
     * healthcheck, over three trials on 2026-09-04 — 9,377 ms / 8,982 ms / 8,736 ms. Rounded up
     * from the worst of the three. Re-measure on other hardware; this is a property of the machine,
     * not a constant.
     */
    static final int CONTAINER_START_SECONDS = Integer.getInteger("containerStartSeconds", 10);

    static final int POLL_INTERVAL_SECONDS = Integer.getInteger("pollIntervalSeconds", 10);
    static final int SAFETY_MARGIN_SECONDS = Integer.getInteger("safetyMarginSeconds", 10);

    /** Replicas when nothing is scheduled, and the ceiling one laptop can actually host. */
    static final int BASELINE_REPLICAS = Integer.getInteger("baselineReplicas", 1);
    static final int MAX_REPLICAS = Integer.getInteger("maxReplicas", 4);

    /** How long after its start an event is treated as still running. */
    static final int EVENT_DURATION_SECONDS = Integer.getInteger("eventDurationSeconds", 180);

    static final String SCHEDULE_URL =
            System.getProperty("scheduleUrl", "http://localhost:8080/v1/events/upcoming");
    static final String COMPOSE_SERVICE = System.getProperty("composeService", "app");

    /**
     * Compose files the scale command runs against, comma-separated, in overlay order.
     *
     * <p>Not cosmetic. {@code docker compose} defaults to {@code docker-compose.yml} alone, so a
     * controller that omits {@code -f} scales the *base* definition — and every replica it starts
     * comes up without the {@code docker-compose.prod.yml} overlay's CPU cap and resized
     * {@code PLAYHEAD_ADMISSION} (D-035). The caps would be silently discarded at precisely the
     * moment the fleet grows, which is the one moment they matter.
     *
     * <p>Defaults to the base file so benchmark runs are unaffected. For the capped deployment:
     * {@code -DcomposeFiles=docker-compose.yml,docker-compose.prod.yml}
     */
    static final String COMPOSE_FILES =
            System.getProperty("composeFiles", "docker-compose.yml");

    static final int LEAD_SECONDS =
            POLL_INTERVAL_SECONDS + CONTAINER_START_SECONDS + SAFETY_MARGIN_SECONDS;

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static int currentReplicas = BASELINE_REPLICAS;

    /** NFR-12: capacity bought early is capacity paid for while idle. Counted, not hidden. */
    private static double idleInstanceSeconds = 0;
    private static Instant lastTick = Instant.now();

    public static void main(String[] args) throws Exception {
        log("controller up - lead time " + LEAD_SECONDS + "s (poll " + POLL_INTERVAL_SECONDS
                + "s + measured start " + CONTAINER_START_SECONDS + "s + margin "
                + SAFETY_MARGIN_SECONDS + "s)");
        log("one replica = " + PER_INSTANCE_RPS + " rps (measured); baseline " + BASELINE_REPLICAS
                + ", max " + MAX_REPLICAS);

        while (true) {
            try {
                tick();
            } catch (Exception e) {
                // A controller that dies on one bad poll is worse than one that retries: the
                // event is still coming either way.
                log("poll failed (" + e + ") - retrying next tick");
            }
            Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
        }
    }

    private static void tick() throws Exception {
        Instant now = Instant.now();
        List<Event> events = fetchUpcoming();

        int desired = desiredReplicas(events, now);
        accrueIdleCost(now, events);

        if (desired != currentReplicas) {
            log("scaling " + currentReplicas + " -> " + desired);
            scaleTo(desired);
            currentReplicas = desired;
        }
    }

    /**
     * The brain. Returns the highest replica count demanded by any event inside its pre-scale
     * window or currently running, and baseline if there is none. Knows nothing about Docker.
     */
    static int desiredReplicas(List<Event> events, Instant now) {
        int desired = BASELINE_REPLICAS;
        for (Event e : events) {
            Instant scaleUpAt = e.startsAt().minusSeconds(LEAD_SECONDS);
            Instant scaleDownAt = e.startsAt().plusSeconds(EVENT_DURATION_SECONDS);
            boolean active = !now.isBefore(scaleUpAt) && now.isBefore(scaleDownAt);
            if (active) {
                int needed = (int) Math.ceil((double) e.expectedPeakRps() / PER_INSTANCE_RPS);
                desired = Math.max(desired, Math.min(needed, MAX_REPLICAS));
            }
        }
        return desired;
    }

    /** The hand. The only method that knows the orchestrator is Compose. */
    private static void scaleTo(int replicas) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose"));
        for (String file : COMPOSE_FILES.split(",")) {
            cmd.add("-f");
            cmd.add(file.strip());
        }
        cmd.addAll(List.of("up", "-d", "--no-recreate",
                "--scale", COMPOSE_SERVICE + "=" + replicas));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            log("scale command failed (exit " + exit + "): " + output.strip());
        }
    }

    /**
     * Instance-seconds held above baseline while no event is actually running yet - the cost of
     * being early. Reported so the trade-off is stated rather than assumed away.
     */
    private static void accrueIdleCost(Instant now, List<Event> events) {
        double elapsed = Duration.between(lastTick, now).toMillis() / 1000.0;
        lastTick = now;

        boolean eventRunning = events.stream().anyMatch(e ->
                !now.isBefore(e.startsAt())
                        && now.isBefore(e.startsAt().plusSeconds(EVENT_DURATION_SECONDS)));

        if (!eventRunning && currentReplicas > BASELINE_REPLICAS) {
            idleInstanceSeconds += (currentReplicas - BASELINE_REPLICAS) * elapsed;
            log(String.format("pre-scaled and waiting - idle cost so far %.1f instance-seconds",
                    idleInstanceSeconds));
        }
    }

    private static List<Event> fetchUpcoming() throws Exception {
        // lookbackSeconds is essential, not a refinement: without it the schedule stops returning
        // an event the moment it starts, the controller sees an empty list, and it scales back to
        // baseline just as the surge lands. Asking for the event-duration window keeps a running
        // event visible for as long as capacity should be held for it.
        String url = SCHEDULE_URL + (SCHEDULE_URL.contains("?") ? "&" : "?")
                + "lookbackSeconds=" + EVENT_DURATION_SECONDS;

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("schedule returned " + response.statusCode());
        }
        return parse(response.body());
    }

    /**
     * The response is a small array of known shape, so it is read with a regex rather than by
     * adding a JSON dependency to a file meant to run as a single source file with nothing
     * installed. A real control plane would use a parser.
     */
    static List<Event> parse(String json) {
        Pattern p = Pattern.compile(
                "\"startsAt\"\\s*:\\s*\"([^\"]+)\".*?\"expectedPeakRps\"\\s*:\\s*(\\d+)",
                Pattern.DOTALL);
        Matcher m = p.matcher(json);
        List<Event> events = new ArrayList<>();
        while (m.find()) {
            events.add(new Event(Instant.parse(m.group(1)), Integer.parseInt(m.group(2))));
        }
        return events;
    }

    private static void log(String message) {
        System.out.println("[" + Instant.now() + "] " + message);
    }

    record Event(Instant startsAt, int expectedPeakRps) {
    }
}
