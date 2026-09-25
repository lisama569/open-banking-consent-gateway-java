# Open Banking Consent Gateway（本地可复现实现）

基于 Spring Boot 3 / Java 21 的开放银行授权与事件通知网关。全部状态保存在本地内存存储中，
不依赖任何外部服务、真实凭据或付费组件，验证过程可完全在本地完成。

## 授权模型

```
PartnerClient 1─n Credential        合作伙伴客户端与其凭据（ACTIVE / GRACE / RETIRED）
Consent (clientId, userId, scopes, status, version, expiresAt)
        1─n AccessToken             由授权签发的访问令牌，范围不得超出授权范围
        1─n ConsentEvent            授权事件（GRANTED / SCOPE_CHANGED / REVOKED / EXPIRED），
                                    同一 consentId 下 sequence 单调递增
ConsentEvent 1─1 DeliveryRecord     投递记录（PENDING / DELIVERED / FAILED / RATE_LIMITED）
```

授权状态机：`ACTIVE → REVOKED`（用户撤销）、`ACTIVE → EXPIRED`（到期自动判定）。
状态只前进不回退；撤销与过期都会级联撤销该授权下的全部令牌，保证授权与令牌状态一致。

## 令牌与凭据轮换的兼容规则

### 令牌校验（`TokenService.validate`）

按固定顺序判定，拒绝原因可区分：

| 拒绝原因 | 含义 |
|---|---|
| `UNKNOWN_TOKEN` | 令牌不存在，或不属于该客户端（不泄露令牌归属） |
| `TOKEN_REVOKED` | 令牌已被撤销（含授权撤销/过期/范围变更导致的级联撤销） |
| `TOKEN_EXPIRED` | 令牌自身已过期 |
| `CONSENT_REVOKED` | 令牌有效但所属授权已被撤销 |
| `CONSENT_EXPIRED` | 令牌有效但所属授权已过期 |
| `INSUFFICIENT_SCOPE` | 令牌范围不覆盖请求所需范围（越权） |

### 凭据轮换（`ClientService.rotateCredential`）

- 轮换后新凭据立即 `ACTIVE`，上一代凭据进入 `GRACE`；
- `GRACE` 凭据在过渡期（`gateway.credential-grace-seconds`）内仍可认证，过渡期结束自动失效；
- 再次轮换时，处于 `GRACE` 的凭据直接 `RETIRED`，任何时刻至多一代凭据处于过渡期；
- `RETIRED` 凭据永久不可用。即：**过渡期内新旧凭据均可用，过渡期外仅最新凭据可用**。

## 幂等与并发语义

- 授权提交携带 `(clientId, userId, idempotencyKey)` 幂等键：重复或并发提交返回同一授权，
  只产生一条 `GRANTED` 事件与一次通知，状态不回退；
- 范围变更必须显式确认（`confirmed=true`）；范围相同的变更请求是幂等无操作；
  变更成功后版本号递增、旧令牌撤销、发出 `SCOPE_CHANGED` 事件；
- 撤销与过期判定均为幂等：重复调用不产生重复事件。

## 通知投递与限流语义

- 事件按 `consentId` 维度串行投递：前序事件未送达时，后续事件等待，保证有序且不重复；
- 每个事件对应唯一 `DeliveryRecord`，已 `DELIVERED` 的事件重试为 no-op；
- 投递结果可区分：`DELIVERED`（成功）、`FAILED`（投递目标失败，可重试）、
  `RATE_LIMITED`（限流，不消耗尝试次数，窗口过后可重试）；
- 限流为每客户端固定窗口配额：`gateway.delivery-rate-limit` 次 /
  `gateway.delivery-rate-window-seconds` 秒；
- 失败重试上限：`gateway.delivery-max-retries`，超过后重试请求被拒绝并保留 `FAILED` 状态。

## HTTP 入口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/clients` | 合作伙伴接入（幂等），返回首个凭据 |
| POST | `/api/clients/{clientId}/credentials/rotate` | 凭据轮换 |
| POST | `/api/clients/authenticate` | 凭据认证 |
| POST | `/api/consents` | 授予授权（幂等键） |
| POST | `/api/consents/{id}/scopes` | 范围变更（需确认） |
| POST | `/api/consents/{id}/revoke` | 撤销授权 |
| POST | `/api/tokens` | 签发令牌 |
| POST | `/api/tokens/validate` | 校验令牌（返回可区分拒绝原因） |
| GET  | `/api/notifications/{eventId}` | 查询投递记录 |
| POST | `/api/notifications/{eventId}/retry` | 重试投递 |

## 本地验证

```bash
# 运行全部测试（授权撤销与过期、并发重复提交、通知重试与限流）
mvn test

# 本地启动（内存存储，端口随机，见启动日志）
mvn spring-boot:run
```

测试日志会打印关键输入、状态判定与投递结果，例如：

```
grant clientId=client-1 userId=user-1 idempotencyKey=key-race result=created consentId=con-...
validate tokenId=tok-... clientId=client-1 result=reject reason=TOKEN_REVOKED
attempt eventId=evt-... clientId=client-1 attempts=2 result=delivered
attempt eventId=evt-... clientId=client-1 result=rate-limited
```
