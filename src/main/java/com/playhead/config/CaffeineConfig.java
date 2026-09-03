package com.playhead.config;

import com.playhead.domain.PlaybackState;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

/**
 * The in-process (local) cache tier that sits in front of Redis on the read path.
 *
 * <p>Two independent caches, one per read shape. Both use a short time-to-live: a local cache is
 * per-instance and has no cross-instance invalidation, so an entry must simply expire rather than
 * be corrected. Two seconds keeps every read inside the NFR-4 staleness bound while still
 * absorbing bursts of repeat requests (client retries, a browse session re-hitting
 * continue-watching) without a Redis round trip.
 *
 * <p>{@code recordStats()} is enabled and each cache is registered with Micrometer, so hit and
 * miss counts are readable at runtime from {@code /actuator/metrics/cache.gets}. The value of this
 * tier is judged by that hit rate, not assumed.
 */
@Configuration
public class CaffeineConfig {

    private static final Duration LOCAL_TTL = Duration.ofSeconds(2);

    @Bean
    public Cache<String, PlaybackState> resumeCache(MeterRegistry meterRegistry) {
        return CaffeineCacheMetrics.monitor(meterRegistry, build(), "resume");
    }

    @Bean
    public Cache<String, List<PlaybackState>> continueWatchingCache(MeterRegistry meterRegistry) {
        return CaffeineCacheMetrics.monitor(meterRegistry, build(), "continue-watching");
    }

    private static <K, V> Cache<K, V> build() {
        return Caffeine.newBuilder()
                .expireAfterWrite(LOCAL_TTL)
                .maximumSize(10_000)
                .recordStats()
                .build();
    }
}
