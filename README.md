# Open Banking Consent Gateway（本地模拟）

基于 Spring Boot 3 / Java 21 的开放银行授权与事件通知网关。全部状态保存在本地内存，
不依赖任何外部服务、真实凭据或付费组件，可在本地独立完成构建、运行与验证。

## 授权模型

```
合作伙伴客户端 (PartnerClient)
  └─ 凭据 (ClientCredential)：ACTIVE / GRACE / RETIRED
访问令牌 (AccessToken)：clientId + scopes + 过期时间 + 撤销标记
用户授权 (Consent)：PENDING_CONFIRMATION → AUTHORIZED → (REVOKED | EXPIRED)
通知事件 (NotificationEvent)：按 consentId 单调序号排序
```

授权生命周期：

- `createConsent` 创建后进入 `PENDING_CONFIRMATION`，需用户确认（`authorize`）才生效；
- 范围变更（`changeScopes`）会生成新版本（version+1）并**回到待确认状态**，必须重新确认；
- `REVOKE` / `EXPIRED` 为终态，任何操作不会造成状态回退；
- 过期为惰性判定：到期后的下一次访问将授权转为 `EXPIRED` 并发布一次过期事件。

幂等与并发语义：

- `createConsent` 以 `idempotencyKey`（按 clientId 维度）去重，重复或并发提交返回**同一条**授权记录；
- `authorize` / `revoke` 可重复调用，结果与单次调用一致，事件只发布一次；
- 初始凭据只签发一次，重复调用返回已有凭据（明文不可恢复，返回 `null`）。

## 令牌与凭据轮换的兼容规则

凭据轮换（`POST /api/partners/{clientId}/credentials/rotate`）：

- 新凭据立即生效（`ACTIVE`）；
- 旧凭据进入 `GRACE` 宽限期（`gracePeriodSeconds` 可配，默认 300s），宽限期内**新旧凭据均可认证**；
- 宽限期结束后旧凭据转为 `RETIRED`，认证返回 `CREDENTIAL_RETIRED`。

认证与令牌校验的拒绝原因可区分（`TokenRejectionReason`）：

| 原因 | 含义 |
|---|---|
| `UNKNOWN_CLIENT` | 客户端不存在 |
| `CLIENT_SUSPENDED` | 客户端被暂停 |
| `BAD_CREDENTIAL` | 密钥不匹配 |
| `CREDENTIAL_RETIRED` | 凭据已过宽限期被废止 |
| `TOKEN_NOT_FOUND` | 令牌不存在 |
| `TOKEN_EXPIRED` | 令牌已过期 |
| `TOKEN_REVOKED` | 令牌已撤销 |
| `SCOPE_NOT_GRANTED` | 令牌范围不覆盖所需范围（越权） |

## 通知投递与限流语义

- 事件按 `consentId` 维度分配**单调递增序号**，同授权下严格有序；
- 事件以 `(consentId, type, consentVersion)` 幂等去重，重复状态变更不会产生重复事件，
  重复投递同一事件也不会重复送达；
- 投递结果（`DeliveryOutcome`）区分为：`DELIVERED` / `RETRY_SCHEDULED` / `FAILED` / `RATE_LIMITED`；
- 重试：`notification.max-attempts`（默认 3）次尝试内失败返回 `RETRY_SCHEDULED`，耗尽置 `FAILED`；
- 限流：每客户端令牌桶（`notification.rate-limit.capacity` / `refill-per-second`），
  配额不足返回 `RATE_LIMITED`，**不计入重试次数、不标记失败**，与投递失败严格区分。

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/partners` | 注册客户端并签发初始凭据 |
| POST | `/api/partners/{clientId}/credentials/rotate` | 轮换凭据（可传 `gracePeriodSeconds`） |
| POST | `/api/tokens` | 认证并签发令牌（`clientId`/`secret`/`scopes`/`ttlSeconds`） |
| POST | `/api/tokens/validate` | 校验令牌与所需范围 |
| POST | `/api/tokens/{tokenId}/revoke` | 吊销令牌 |
| POST | `/api/consents` | 创建授权（`idempotencyKey` 幂等） |
| POST | `/api/consents/{id}/authorize` | 用户确认 |
| POST | `/api/consents/{id}/scopes` | 范围变更（需重新确认） |
| POST | `/api/consents/{id}/revoke` | 撤销授权 |
| GET  | `/api/consents/{id}` | 查询授权状态 |
| GET  | `/api/notifications/consents/{consentId}` | 查询授权事件（按序号升序） |
| POST | `/api/notifications/{eventId}/deliver` | 触发一次投递尝试 |

## 本地验证

```bash
# 运行全部测试（令牌过期/撤销/越权、凭据轮换宽限期、并发幂等、通知重试与限流）
mvn test

# 本地启动（随机端口，见启动日志）
mvn spring-boot:run
```

测试覆盖：

- `TokenServiceTest`：过期、撤销、越权、未知令牌的拒绝原因区分；
- `PartnerServiceTest`：凭据轮换宽限期语义、认证拒绝原因区分、初始凭据只发一次；
- `ConsentServiceTest`：幂等创建、16 线程并发重复提交去重、撤销/过期不回退、
  范围变更需重新确认、事件不重复；
- `NotificationServiceTest`：重试耗尽置 FAILED、限流与失败可区分且不计入重试、
  事件有序不重复送达；
- `GatewayIntegrationTest`：接入 → 令牌 → 授权 → 通知 → 撤销后各入口状态一致。

单测日志（`[test]` / `[consent]` / `[token]` / `[notify]` 前缀）会打印关键输入、
状态判定与投递结果，可直接从 `mvn test` 输出中核对。
