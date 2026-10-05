# Open Banking Consent Gateway（本地模拟）

基于 Spring Boot 3 / Java 21 的开放银行授权与事件通知网关。全部状态保存在本地内存，
不依赖任何外部服务、真实凭据或付费组件，可在本地独立完成构建、运行与验证。

## 授权模型

```
合作伙伴客户端 (PartnerClient)
  └─ 凭据 (ClientCredential)：ACTIVE / GRACE / RETIRED
访问令牌 (AccessToken)：clientId + scopes + 过期时间 + 撤销标记
授权绑定凭证 (ConsentAccessToken)：绑定 (consentId, consentVersion)，随授权状态失效
用户授权 (Consent)：PENDING_CONFIRMATION → AUTHORIZED → (REVOKED | EXPIRED)
通知事件 (NotificationEvent)：按 consentId 单调序号排序，严格按序投递
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

## 授权绑定凭证（consent-bound token）

合作伙伴可针对一条**已被用户确认**的授权换取访问凭证
（`POST /api/tokens/consent-exchange`，参数 `consentId`/`scopes`/`ttlSeconds`）：

- 仅 `AUTHORIZED` 状态的授权可换取；未确认返回 `CONSENT_NOT_AUTHORIZED`；
- 凭证范围不得超过授权确认时的范围（缺省取授权全量范围），超出返回 `SCOPE_NOT_GRANTED`；
- 凭证有效期不超过授权自身的到期时间（取两者较小者）；
- 同一授权同一版本**重复或并发换取返回同一份凭证**（按 `consentId|version` 幂等），
  不会换出多份，也不会改变授权本身的状态与版本；
- 授权被撤销、自然到期，或范围被改动（版本变化、需重新确认）后，
  之前基于它换出的凭证**立即失效**，校验时分别返回可区分原因：

| 原因 | 含义 |
|---|---|
| `CONSENT_NOT_AUTHORIZED` | 授权尚未被用户确认，不能换取凭证 |
| `CONSENT_REVOKED` | 授权已被撤销，绑定凭证失效 |
| `CONSENT_EXPIRED` | 授权已自然到期，绑定凭证失效 |
| `CONSENT_SCOPE_CHANGED` | 授权范围已变更（版本已推进或回到待确认），旧版本凭证失效，需用户重新确认后重新换取 |

范围变更后即使重新确认，旧版本凭证也**不会复活**（版本号已不同），需重新换取新版本凭证。

**兼容范围**：不绑定授权的旧令牌（`POST /api/tokens` 签发的 `tok-` 开头令牌）行为完全不变，
仍由原有逻辑校验，返回 `TOKEN_EXPIRED`/`TOKEN_REVOKED`/`SCOPE_NOT_GRANTED` 等原有原因；
`POST /api/tokens/validate` 对两类令牌自动路由：授权绑定凭证（`ctok-` 开头）走授权状态判定，
旧令牌走原有判定，调用方无需区分。

## 有序投递与积压容量

同一授权下的通知投递由系统保证先后顺序，不依赖调用方按顺序触发：

- 前一条事件未送达时，后一条即使被触发也返回 `BLOCKED_BY_ORDER`，不会越序送达；
  被挡不消耗重试次数，也不消耗限流配额；
- `FAILED`（重试耗尽）的前序事件同样挡住后序，补投成功后后序自动放行（严格按序，不跳过）；
- 重复投递或并发触发同一批通知：已送达的不会重复送达，顺序不会被打乱；
- 投递进度对外可见：`GET /api/notifications/consents/{consentId}/progress` 返回
  `lastDeliveredSequence`（已送达到的序号）、`nextDeliverableEventId`（下一条可投递）、
  `blockedEventIds`（因顺序未轮到被挡住的事件）、`undeliveredCount`（积压占用）。

积压容量上限：

- 每条授权下**未送达**事件数量有上限，由 `notification.backlog.max-undelivered-per-consent`
  配置（默认 50）；
- 达到上限后，新事件以独立原因 `BACKLOG_FULL` 被挡下（服务层抛出
  `ConsentBacklogFullException`，API 返回 429 + `{"error":"BACKLOG_FULL"}`），
  与投递失败（`FAILED`）、限流（`RATE_LIMITED`）明确区分；
- 被挡下的变更**不会部分生效**：授权状态、版本与已接收事件的顺序和状态保持不变；
  投递腾出席位后同一变更可重试，事件序号保持连续；
- 幂等去重优先于容量检查：积压满时重复提交同一事件仍返回去重结果，不会被误挡；
- 授权到期是系统级状态迁移，不被积压上限阻断（积压满时仅丢弃过期事件并告警，
  状态迁移仍生效，以保证绑定凭证能正确判定 `CONSENT_EXPIRED`）。

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
| POST | `/api/tokens/consent-exchange` | 针对已确认授权换取授权绑定凭证（`consentId`/`scopes`/`ttlSeconds`） |
| POST | `/api/tokens/validate` | 校验令牌与所需范围（授权绑定凭证自动走授权状态判定） |
| POST | `/api/tokens/{tokenId}/revoke` | 吊销令牌 |
| POST | `/api/consents` | 创建授权（`idempotencyKey` 幂等） |
| POST | `/api/consents/{id}/authorize` | 用户确认 |
| POST | `/api/consents/{id}/scopes` | 范围变更（需重新确认） |
| POST | `/api/consents/{id}/revoke` | 撤销授权 |
| GET  | `/api/consents/{id}` | 查询授权状态 |
| GET  | `/api/notifications/consents/{consentId}` | 查询授权事件（按序号升序） |
| GET  | `/api/notifications/consents/{consentId}/progress` | 查询投递进度（已送达序号/下一条可投/被挡事件/积压占用） |
| POST | `/api/notifications/{eventId}/deliver` | 触发一次投递尝试 |

## 本地验证

```bash
# 运行全部测试（令牌过期/撤销/越权、凭据轮换宽限期、并发幂等、通知重试与限流、
# 授权绑定凭证失效判定、有序投递、积压容量上限）
mvn test

# 只跑本轮新增用例
mvn test -Dtest=ConsentTokenServiceTest,NotificationOrderingTest,NotificationBacklogTest

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
- `ConsentTokenServiceTest`：授权换凭证的范围/有效期约束，撤销、到期、范围变更
  三类失效原因区分，重新确认后旧凭证不复活，重复与 16 线程并发换取只出一份凭证；
- `NotificationOrderingTest`：乱序触发被 `BLOCKED_BY_ORDER` 挡住且不消耗重试与限流配额、
  投递进度可见、FAILED 前序补投后放行、8 线程并发乱序触发仍按序不重复送达；
- `NotificationBacklogTest`：积压容量边界与独立原因 `BACKLOG_FULL`、积压满时授权变更
  被挡且状态不受损、幂等去重优先于容量检查、4 授权 × 16 线程并发发布与投递的
  容量/序号/顺序一致性；
- `GatewayIntegrationTest`：接入 → 令牌 → 授权 → 通知 → 撤销后各入口状态一致。

单测日志（`[test]` / `[consent]` / `[token]` / `[consent-token]` / `[notify]` 前缀）
会打印关键输入、状态判定与投递结果，可直接从 `mvn test` 输出中核对。

手工验证授权绑定凭证（服务启动后）：

```bash
# 1. 创建并确认授权（idempotencyKey 任意）
curl -X POST localhost:$PORT/api/consents -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"k1","clientId":"client-a","userId":"u1","scopes":["accounts:read"],"ttlSeconds":86400}'
curl -X POST localhost:$PORT/api/consents/{consentId}/authorize
# 2. 换取授权绑定凭证
curl -X POST localhost:$PORT/api/tokens/consent-exchange -H 'Content-Type: application/json' \
  -d '{"consentId":"{consentId}","ttlSeconds":3600}'
# 3. 校验凭证；撤销授权后再次校验可看到 reason=CONSENT_REVOKED
curl -X POST localhost:$PORT/api/tokens/validate -H 'Content-Type: application/json' \
  -d '{"tokenId":"{tokenId}","requiredScopes":["accounts:read"]}'
# 4. 查看某授权的投递进度
curl localhost:$PORT/api/notifications/consents/{consentId}/progress
```
