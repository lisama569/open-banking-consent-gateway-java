package com.github.lisama569.openbankingconsentgatewayjava.service;

/**
 * 通知事件被系统拒绝接收（区别于投递失败与限流：事件根本没有进入队列）。
 * 目前唯一原因：该授权下未送达事件已达到积压容量上限 BACKLOG_FULL。
 */
public class NotificationRejectedException extends RuntimeException {

    public enum Reason { BACKLOG_FULL }

    private final Reason reason;

    public NotificationRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
