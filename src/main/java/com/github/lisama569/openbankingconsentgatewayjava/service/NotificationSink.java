package com.github.lisama569.openbankingconsentgatewayjava.service;

import com.github.lisama569.openbankingconsentgatewayjava.domain.ConsentEvent;

/** 通知投递目标（本地模拟）。 */
public interface NotificationSink {
    void deliver(ConsentEvent event) throws DeliveryException;

    /** 投递失败，可与限流区分。 */
    class DeliveryException extends Exception {
        public DeliveryException(String message) {
            super(message);
        }
    }
}
