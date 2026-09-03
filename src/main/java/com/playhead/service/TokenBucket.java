package com.playhead.service;

/**
 * A per-tier rate limiter. Holds a capped number of tokens, refilled continuously over time;
 * consuming a token is how a request is admitted, an empty bucket is how it gets shed.
 */
public class TokenBucket {

    private final long capacity;
    private final double refillTokensPerSecond;

    private double availableTokens;
    private long lastRefillTimeNanos;

    public TokenBucket(long capacity, double refillTokensPerSecond) {
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.availableTokens = capacity;
        this.lastRefillTimeNanos = System.nanoTime();
    }

    public synchronized boolean tryConsume() {
        refill();
        if (availableTokens >= 1) {
            availableTokens -= 1;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillTimeNanos) / 1_000_000_000.0;
        double newTokens = elapsedSeconds * refillTokensPerSecond;
        if (newTokens > 0) {
            availableTokens = Math.min(capacity, availableTokens + newTokens);
            lastRefillTimeNanos = now;
        }
    }
}
