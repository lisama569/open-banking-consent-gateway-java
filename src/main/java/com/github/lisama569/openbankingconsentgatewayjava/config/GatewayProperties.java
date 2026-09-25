package com.github.lisama569.openbankingconsentgatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 网关可配置项。 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {
    /** 令牌有效期（秒）。 */
    private long tokenTtlSeconds = 3600;
    /** 授权默认有效期（秒）。 */
    private long consentTtlSeconds = 86400;
    /** 凭据轮换过渡期（秒），过渡期内新旧凭据均可用。 */
    private long credentialGraceSeconds = 300;
    /** 通知投递限流：每个窗口最大投递次数。 */
    private int deliveryRateLimit = 10;
    /** 限流窗口长度（秒）。 */
    private long deliveryRateWindowSeconds = 1;
    /** 投递失败最大重试次数。 */
    private int deliveryMaxRetries = 3;

    public long getTokenTtlSeconds() { return tokenTtlSeconds; }
    public void setTokenTtlSeconds(long tokenTtlSeconds) { this.tokenTtlSeconds = tokenTtlSeconds; }
    public long getConsentTtlSeconds() { return consentTtlSeconds; }
    public void setConsentTtlSeconds(long consentTtlSeconds) { this.consentTtlSeconds = consentTtlSeconds; }
    public long getCredentialGraceSeconds() { return credentialGraceSeconds; }
    public void setCredentialGraceSeconds(long credentialGraceSeconds) { this.credentialGraceSeconds = credentialGraceSeconds; }
    public int getDeliveryRateLimit() { return deliveryRateLimit; }
    public void setDeliveryRateLimit(int deliveryRateLimit) { this.deliveryRateLimit = deliveryRateLimit; }
    public long getDeliveryRateWindowSeconds() { return deliveryRateWindowSeconds; }
    public void setDeliveryRateWindowSeconds(long deliveryRateWindowSeconds) { this.deliveryRateWindowSeconds = deliveryRateWindowSeconds; }
    public int getDeliveryMaxRetries() { return deliveryMaxRetries; }
    public void setDeliveryMaxRetries(int deliveryMaxRetries) { this.deliveryMaxRetries = deliveryMaxRetries; }
}
