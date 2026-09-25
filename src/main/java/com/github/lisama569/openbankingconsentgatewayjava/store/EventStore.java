package com.github.lisama569.openbankingconsentgatewayjava.store;

import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 授权事件本地存储，按 consentId 维护有序序列。 */
@Component
public class EventStore {
    private final ConcurrentHashMap<String, ConsentEvent> events = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ConsentEvent>> byConsent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public long nextSequence(String consentId) {
        return sequences.computeIfAbsent(consentId, k -> new AtomicLong(0)).incrementAndGet();
    }

    public ConsentEvent save(ConsentEvent event) {
        events.put(event.getEventId(), event);
        byConsent.computeIfAbsent(event.getConsentId(), k -> new CopyOnWriteArrayList<>()).add(event);
        return event;
    }

    public Optional<ConsentEvent> findById(String eventId) {
        return Optional.ofNullable(events.get(eventId));
    }

    public List<ConsentEvent> findByConsentId(String consentId) {
        return byConsent.getOrDefault(consentId, List.of());
    }
}
