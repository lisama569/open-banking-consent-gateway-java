# Open Banking Consent Gateway（本地模拟）

基于 Spring Boot 3 / Java 21 的开放银行授权与事件通知网关。全部状态保存在本地内存，
不依赖任何外部服务、真实凭据或付费组件，可在本地独立完成构建、运行与验证。

## 授权模型

```
合作伙伴客户端 (PartnerClient)
  └─ 凭据 (ClientCredential)：ACTIVE / GRACE / RETIRED
访问令牌 (AccessToken)：clientId + scopes + 过期时间 + 撤销标记
  ├─ 旧入口令牌：consentId=null，不绑定授权
  └─ 授权绑定凭证：consentId + consentVersion（授权撤销/到期/版本变更即失效）
用户授权 (Consent)：PENDING_CONFIRMATION → AUTHORIZED → (REVOKED | EXPIRED)
通知事件 (NotificationEvent)：按 consentId 单调序号，系统保证授权内严格按序送达
投递结果：DELIVERED / RETRY_SCHEDULED / FAILED / RATE_LIMITED / BLOCKED_BY_ORDER
发布拒绝：BACKLOG_FULL（积压达 notification.backlog-capacity）
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

## 授权绑定凭证（本轮新增）

在“凭据 → 通用令牌”之外，新增围绕**一条具体用户授权**换取访问凭证的能力：

```
POST /api/tokens/consent-exchange
  { clientId, secret, consentId, scopes?, ttlSeconds? }
```

语义：

- 换取前提：`clientId/secret` 认证通过，授权存在且处于 `AUTHORIZED`，且请求的 `scopes`
  **不超过**该授权当前已确认范围（`scopes` 留空表示取授权全部范围）；
- 凭证在换取时固化 `consentId + consentVersion + scopes`，响应中带 `consentBound=true`；
- **同一授权的同一确认版本**重复或并发换取，稳定返回**同一份**凭证，不换出多份、
  不改变授权状态、不发布事件；范围变更产生新版本后，需要用户重新确认，再换出的是**新凭证**；
- 绑定凭证在每次校验时实时检查授权，下列变化使其**立即失效**（即使凭证自身尚未到期），
  且原因彼此可区分：

| 失效场景 | 拒绝原因 |
|---|---|
| 授权被用户撤销 | `CONSENT_REVOKED` |
| 授权自然到期 | `CONSENT_EXPIRED` |
| 授权范围已改动（版本不一致，需用户重新确认；重新确认前/后旧凭证都失效） | `CONSENT_SCOPE_RECONFIRMATION_REQUIRED` |

换取阶段的拒绝原因：`CONSENT_NOT_FOUND` / `CONSENT_NOT_AUTHORIZED` /
`CONSENT_SCOPE_EXCEEDED`（请求范围超出授权范围；区别于校验期的 `SCOPE_NOT_GRANTED`）。

**与旧能力的兼容范围**：旧入口 `POST /api/tokens` 签发的令牌不绑定任何授权
（`consentBound=false`，无 `consentId/consentVersion`），其生命周期只受自身过期/吊销与
`SCOPE_NOT_GRANTED` 约束，授权被撤销/到期/范围变更**不会**影响这类旧令牌；旧入口与
`/api/tokens/validate`、`/api/tokens/{id}/revoke` 的行为保持不变。绑定凭证的授权层检查
优先于凭证自身过期判断，因此“授权到期”和“凭证自身到期”（`TOKEN_EXPIRED`）不会被混淆。

## 授权内有序投递（本轮新增）

系统自身保证**同一 `consentId` 下严格按序号送达**，不依赖调用方按顺序触发：

- 触发某事件投递时，若存在序号更小且尚未送达（`PENDING/FAILED`）的前序事件，本次直接返回
  `BLOCKED_BY_ORDER`：**不消耗限流配额、不计入尝试次数、不改变事件状态**；
- 前序一旦送达，后序在下次触发时即可继续；已送达事件重复/并发触发幂等返回 `DELIVERED`，
  不会重复送达，顺序也不会被打乱；
- 投递进度可通过 `GET /api/notifications/consents/{consentId}` 观察：
  - `deliveredUpToSequence`：已连续送达的水位（从序号 1 起连续 DELIVERED 的最大值）；
  - `pendingCount` / `backlogCapacity`：当前积压深度与上限；
  - 每个事件的 `blockedBySequence`：挡住它的最小前序序号（`-1` 表示未被挡）。

`BLOCKED_BY_ORDER` 与 `RATE_LIMITED`（限流，不计重试）、`RETRY_SCHEDULED/FAILED`
（投递失败，计重试）是三类互不混淆的结果；顺序护栏先于限流检查生效。

## 通知积压容量（本轮新增）

- 每条授权下**未送达事件**数量有本地可配上限：`notification.backlog-capacity`（默认 100）；
- 达到上限后，新的状态变更事件以独立原因 `BACKLOG_FULL` 被拒绝（HTTP 503）：
  不分配序号、不入队、不改变已接收事件的顺序与状态，也不改变授权状态；
  授权的确认/撤销/范围变更在会发布事件但容量已满时被**原子挡下**（状态不前移、不回退）；
- 惰性到期属于系统事实：即使积压已满，授权仍会转为 `EXPIRED`，过期事件暂不入队并记录告警，
  待积压释放后可补发，不影响失效判定；
- 送掉任意前序事件释放容量后，被挡事件可正常接收并接续序号；
- `BACKLOG_FULL`（事件未被接收）与 `RATE_LIMITED`（已接收、本次配额不足）、
  `FAILED`（已接收、投递失败）严格区分；多条授权高并发投递时，各自按本授权序号有序、
  不重复、不回退。

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
| `CONSENT_NOT_FOUND` | 换取/校验时绑定的授权不存在（或不属于该客户端） |
| `CONSENT_NOT_AUTHORIZED` | 换取时授权尚未被用户确认 |
| `CONSENT_REVOKED` | 绑定授权已撤销，凭证立即失效 |
| `CONSENT_EXPIRED` | 绑定授权已到期，凭证立即失效 |
| `CONSENT_SCOPE_RECONFIRMATION_REQUIRED` | 授权范围已变更（版本不一致），需重新确认并重新换凭证 |
| `CONSENT_SCOPE_EXCEEDED` | 换取时请求范围超过授权已确认范围 |

通知侧的结果/拒绝原因：`DELIVERED` / `RETRY_SCHEDULED` / `FAILED` / `RATE_LIMITED` /
`BLOCKED_BY_ORDER`，以及发布阶段的 `BACKLOG_FULL`（HTTP 503，独立于投递失败与限流）。

## 通知投递与限流语义

- 事件按 `consentId` 维度分配**单调递增序号**，同授权下严格有序；系统自行保证投递顺序，
  乱序触发时后序事件返回 `BLOCKED_BY_ORDER`，不会越过未送达的前序；
- 事件以 `(consentId, type, consentVersion)` 幂等去重，重复状态变更不会产生重复事件，
  重复投递同一事件也不会重复送达；
- 投递结果（`DeliveryOutcome`）区分为：`DELIVERED` / `RETRY_SCHEDULED` / `FAILED` /
  `RATE_LIMITED` / `BLOCKED_BY_ORDER`；
- 重试：`notification.max-attempts`（默认 3）次尝试内失败返回 `RETRY_SCHEDULED`，耗尽置 `FAILED`；
- 限流：每客户端令牌桶（`notification.rate-limit.capacity` / `refill-per-second`），
  配额不足返回 `RATE_LIMITED`，**不计入重试次数、不标记失败**，与投递失败严格区分；
- 积压：每条授权未送达事件达到 `notification.backlog-capacity`（默认 100）后，
  新事件以 `BACKLOG_FULL` 被拒绝（HTTP 503），详见上文“通知积压容量”。

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/partners` | 注册客户端并签发初始凭据 |
| POST | `/api/partners/{clientId}/credentials/rotate` | 轮换凭据（可传 `gracePeriodSeconds`） |
| POST | `/api/tokens` | 认证并签发令牌（旧入口，令牌不绑定授权） |
| POST | `/api/tokens/consent-exchange` | 以已确认授权换绑定凭证（幂等、范围受限、授权失效即失效） |
| POST | `/api/tokens/validate` | 校验令牌与所需范围（绑定凭证同时校验授权状态/版本） |
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
# 运行全部测试（令牌过期/撤销/越权、凭据轮换宽限期、并发幂等、
# 授权绑定凭证三类失效、通知顺序护栏、积压容量边界与并发、重试与限流）
mvn test

# 只跑本轮新增能力的单测
mvn -Dtest=ConsentBoundTokenServiceTest,OrderedDeliveryAndBacklogTest,GatewayIntegrationTest test

# 本地启动（随机端口，见启动日志）
mvn spring-boot:run
```

启动后可用如下顺序手工验证授权绑定凭证（`$PORT` 为启动日志中的端口）：

```bash
# 1. 注册客户端并拿初始凭据（见 /api/partners 返回的 plainSecret）
# 2. 创建授权并由用户确认
curl -s -XPOST localhost:$PORT/api/consents -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"k1","clientId":"client-a","userId":"user-1","scopes":["accounts:read"]}'
curl -s -XPOST localhost:$PORT/api/consents/{consentId}/authorize
# 3. 用授权换绑定凭证（重复执行返回同一 tokenId）
curl -s -XPOST localhost:$PORT/api/tokens/consent-exchange -H 'Content-Type: application/json' \
  -d '{"clientId":"client-a","secret":"...","consentId":"{consentId}","scopes":["accounts:read"]}'
# 4. 校验 -> valid=true；撤销授权后再校验 -> reason=CONSENT_REVOKED
curl -s -XPOST localhost:$PORT/api/tokens/validate -H 'Content-Type: application/json' \
  -d '{"tokenId":"...","requiredScopes":["accounts:read"]}'
# 5. 观察投递水位/积压；乱序投递后序事件返回 BLOCKED_BY_ORDER
curl -s localhost:$PORT/api/notifications/consents/{consentId}
```

测试覆盖：

- `TokenServiceTest`：过期、撤销、越权、未知令牌的拒绝原因区分；
- `PartnerServiceTest`：凭据轮换宽限期语义、认证拒绝原因区分、初始凭据只发一次；
- `ConsentServiceTest`：幂等创建、16 线程并发重复提交去重、撤销/过期不回退、
  范围变更需重新确认、事件不重复、**积压满时状态变更被原子挡下且不污染已接收事件**；
- `ConsentBoundTokenServiceTest`（本轮新增）：授权换凭证在撤销、到期、范围变更
  （重新确认前后）三种情况下的失效判定与原因区分、超范围/未确认换取拒绝、
  16 线程并发换取只出一份且授权状态不变、旧令牌不受授权生命周期影响；
- `NotificationServiceTest`：重试耗尽置 FAILED、限流与失败可区分且不计入重试、
  事件有序不重复送达；
- `OrderedDeliveryAndBacklogTest`（本轮新增）：乱序触发严格按授权内顺序送达、
  `BLOCKED_BY_ORDER` 阻挡位置与水位可见、重复/并发投递不重复不乱序、
  积压容量边界（恰好上限、释放后可接续）、被拒不入列不占序号、
  8 授权 ×6 事件 ×24 线程高并发下各自有序不重复、顺序/限流原因不混淆；
- `GatewayIntegrationTest`：接入 → 令牌 → 授权 → 通知 → 撤销后各入口状态一致；
  授权换凭证 → 范围变更失效 → 乱序投递按序送达的串联验证。

单测日志（`[test]` / `[consent]` / `[token]` / `[notify]` 前缀）会打印关键输入、
状态判定与投递结果，可直接从 `mvn test` 输出中核对。
