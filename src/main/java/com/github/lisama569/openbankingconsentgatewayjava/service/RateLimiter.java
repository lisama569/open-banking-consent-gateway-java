package com.github.lisama569.openbankingconsentgatewayjava.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按客户端的令牌桶限流器，容量与补充速率可配置。
 */
@Component
public class RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile double capacity;
    private volatile double refillPerSecond;

    @Autowired
    public RateLimiter(@Value("${notification.rate-limit.capacity:10}") double capacity,
                       @Value("${notification.rate-limit.refill-per-second:5}") double refillPerSecond) {
        this(Clock.systemUTC(), capacity, refillPerSecond);
    }

    public RateLimiter(Clock clock, double capacity, double refillPerSecond) {
        this.clock = clock;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    public void configure(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    /** 尝试为 clientId 获取一个配额，false 表示被限流。 */
    public boolean tryAcquire(String clientId) {
        Bucket bucket = buckets.computeIfAbsent(clientId, k -> new Bucket(capacity, clock.millis() * 1_000_000L));
        synchronized (bucket) {
            long nowNanos = clock.millis() * 1_000_000L;
            double elapsedSeconds = (nowNanos - bucket.lastRefillNanos) / 1_000_000_000.0;
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            bucket.lastRefillNanos = nowNanos;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
