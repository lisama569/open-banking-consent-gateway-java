package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 默认本地投递实现：仅记录日志，不依赖任何外部服务。 */
@Component
public class LocalNotificationSink implements NotificationSink {
    private static final Logger log = LoggerFactory.getLogger(LocalNotificationSink.class);

    @Override
    public void deliver(ConsentEvent event) {
        log.info("deliver eventId={} consentId={} type={} sequence={} result=ok",
                event.getEventId(), event.getConsentId(), event.getType(), event.getSequence());
    }
}
