package com.github.lisama569.openbankingconsentgatewayjava.store;

import com.github.lisama569.openbankingconsentgatewayjava.domain.DeliveryRecord;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 投递记录本地存储。 */
@Component
public class DeliveryStore {
    private final ConcurrentHashMap<String, DeliveryRecord> records = new ConcurrentHashMap<>();

    public DeliveryRecord saveIfAbsent(DeliveryRecord record) {
        return records.putIfAbsent(record.getEventId(), record) == null ? record : records.get(record.getEventId());
    }

    public Optional<DeliveryRecord> findByEventId(String eventId) {
        return Optional.ofNullable(records.get(eventId));
    }
}
