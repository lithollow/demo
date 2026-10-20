# AI 客服系统 REST 与状态契约

版本：`api-contract-v1`

适用实现：`/root/projects/demo` 当前 Spring Boot 3.5.16 + LangChain4j 1.22.0 + Java 21 代码

契约性质：本文描述**修复后的目标语义**。当前实现与目标语义的差异集中在 §15；未特别标注的字段名、路径和枚举以现有代码为准。

## 1. 文档状态与范围

### 1.1 已覆盖

- 聊天入口：`POST /api/chat`
- 会话读取：`GET /api/conversations/{conversationId}`
- 审计读取：`GET /api/audit`
- 人工工单列表与回复：`GET /api/handoffs`、`POST /api/handoffs/{ticketId}/messages`
- 管理员评测：`POST /api/admin/evaluations/run`
- 四个工具：`ORDER_LOOKUP`、`TICKET_LOOKUP`、`REFUND_CREATE`、`HUMAN_HANDOFF`
- 会话状态机、待办动作、退款/工单状态、幂等与权限边界

### 1.2 不覆盖

- 前端页面、登录页、SSO/OAuth2、CORS 配置；
- 真实支付/退款通道、消息队列、分布式锁；
- 多租户隔离、跨区域部署、生产数据库压测；
- 真实 LLM 服务的在线评测；LLM 模式只保证“可注入假模型 + 失败回退”的契约。

## 2. 基础约定

| 项目 | 约定 |
| --- | --- |
| Base URL | `http://localhost:${PORT:8080}`，默认端口 8080 |
| 媒体类型 | 请求和响应均为 `application/json; charset=UTF-8` |
| 时间 | `LocalDateTime` 使用 ISO-8601 本地时间；错误和评测的 `Instant` 使用 UTC ISO-8601 |
| 金额 | JSON number，十进制，最多 2 位小数；内部使用 `BigDecimal`；币种当前固定 `CNY` |
| 空字段 | 全局 Jackson 配置为 `non_null`，值为 `null` 的响应字段**省略**而不是输出 `null` |
| 请求体 | 未声明字段忽略；未知枚举值按 400 处理 |
| 追踪 | 响应头始终带 `X-Trace-Id`；错误体和成功体都带 `traceId` |

### 2.1 公共请求头

| 请求头 | 必填 | 格式 | 语义 |
| --- | --- | --- | --- |
| `X-User-Id` | 是 | 1..32，已存在且 `active=true` 的用户 ID | 演示身份；缺失/未知/停用返回 401 `UNAUTHORIZED` |
| `X-Trace-Id` | 否 | `[A-Za-z0-9._:-]{1,64}` | 合法则沿用；非法或缺失则由服务端生成 UUID |
| `Idempotency-Key` | 否 | `[A-Za-z0-9._:-]{1,120}` | `/api/chat` 可选；目标契约中人工回复也支持；缺失时聊天使用随机 UUID，因而不保证重试去重 |
| `Content-Type` | POST 必填 | `application/json` | 非 JSON 返回 400 `VALIDATION_ERROR` |

### 2.2 ID 约定

| 对象 | 格式 | 示例 |
| --- | --- | --- |
| 会话 | `conv-` + 32 位十六进制 | `conv-4f6c...` |
| 退款 | `RFD-` + 12 位大写字母数字 | `RFD-1A2B3C4D5E6F` |
| 人工工单 | `TCK-` + 8 位大写字母数字 | `TCK-9F8E7D6C` |
| 订单 | `ORD-` + 至少 3 位数字 | `ORD-1001` |
| 追踪 | UUID 或调用方提供的安全字符串 | `01a1537c-...` |

## 3. 身份、角色与权限矩阵

### 3.1 角色

| 角色 | 含义 | 关键边界 |
| --- | --- | --- |
| `CUSTOMER` | 终端客户 | 只能访问自己的会话、订单、工单；不能读审计、不能跑评测、不能回复人工工单 |
| `AGENT` | 人工客服 | 只能处理分配给自己的 `HANDOFF` 工单；可读被分配会话；不能读全量审计、不能跑评测 |
| `ADMIN` | 管理员 | 可读审计、跑评测、查看全部人工工单、回复任意工单、查看任意会话/订单/工单 |

### 3.2 权限矩阵

“own” 表示资源所有者，“assigned” 表示工单负责人或订单关联工单的负责人。

| 能力 | CUSTOMER | AGENT | ADMIN |
| --- | --- | --- | --- |
| `POST /api/chat` | 允许（own 会话；受工具权限约束） | 仅能以自身身份新建/续聊自己的会话，不能以客户身份续聊 | 同 AGENT；客户会话由管理员通过会话读取端点查看 |
| `GET /api/conversations/{id}` | 仅 own | 仅 assigned | 全部 |
| `GET /api/audit` | 拒绝 403 | 拒绝 403 | 允许 |
| `GET /api/handoffs` | 拒绝 403 | 仅 assigned | 全部 |
| `POST /api/handoffs/{ticketId}/messages` | 拒绝 403 | 仅 assigned | 允许 |
| `POST /api/admin/evaluations/run` | 拒绝 403 | 拒绝 403 | 允许 |
| `ORDER_LOOKUP` | 仅 own | 仅 assigned | 全部 |
| `TICKET_LOOKUP` | 仅 own | 仅 assigned | 全部 |
| `REFUND_CREATE` | 仅 own | 仅 assigned | 全部 |
| `HUMAN_HANDOFF` | 允许为 own 会话创建 | 仅通过自身会话触发；assigned 会话通过 `/api/handoffs` 回复 | 允许 |

### 3.3 安全说明

`X-User-Id` 是演示身份，不是认证机制；调用方可以伪造任意已存在用户。生产部署必须在网关或应用层增加签名/令牌校验，并把本契约中的“演示身份”替换为认证主体。该限制对应 `docs/plan.md` 的 G10，不视为当前演示的阻断项，但不得在 README 中宣称“生产可用的权限系统”。

## 4. `POST /api/chat`

### 4.1 请求

```http
POST /api/chat
X-User-Id: cust-alice
Idempotency-Key: order-001
Content-Type: application/json

{
  "conversationId": null,
  "message": "我的订单 ORD-1001 到哪了？"
}
```

| 字段 | 类型 | 必填 | 约束 | 语义 |
| --- | --- | --- | --- | --- |
| `conversationId` | string | 否 | ≤64；必须是当前用户拥有的会话；`null`/省略表示新建 | 续聊时传入上一轮响应中的 `conversationId` |
| `message` | string | 是 | trim 后 1..2000；不能全空白 | 用户原始消息 |

### 4.2 成功响应 `200`

```json
{
  "conversationId": "conv-4f6c...",
  "traceId": "01a1537c-...",
  "status": "ACTIVE",
  "intent": "ORDER_STATUS",
  "confidence": 0.94,
  "plan": ["LOOKUP_ORDER:ORDER_LOOKUP"],
  "reply": "订单 ORD-1001 当前状态为 SHIPPED，可退金额 299.00 CNY。",
  "requiresHuman": false,
  "tools": [
    {
      "tool": "ORDER_LOOKUP",
      "success": true,
      "summary": "订单查询成功"
    }
  ],
  "validation": {
    "passed": true,
    "issues": []
  }
}
```

| 字段 | 类型 | 可空 | 语义 |
| --- | --- | --- | --- |
| `conversationId` | string | 否 | 新建或续聊的会话 ID |
| `traceId` | string | 否 | 本次请求的追踪 ID；幂等重放时返回首次响应体中的值 |
| `status` | enum `ConversationStatus` | 否 | 本次处理后的会话状态 |
| `intent` | enum `Intent` | 否 | 识别出的意图 |
| `confidence` | number | 否 | `[0,1]`，规则/LLM 结果已夹取 |
| `plan` | string[] | 否 | 每项为 `ACTION` 或 `ACTION:TOOL`；例如 `CLARIFY`、`LOOKUP_ORDER:ORDER_LOOKUP` |
| `reply` | string | 否 | 面向用户的回复；目标契约要求与工具事实一致 |
| `pendingAction` | string | 是 | 下一步待收集动作；非空时通常 `status=WAITING_FOR_INPUT` |
| `requiresHuman` | boolean | 否 | 是否已转人工或需要人工；`WAITING_HUMAN` 时为 `true` |
| `handoffTicketId` | string | 是 | 人工工单 ID；仅人工兜底成功时出现 |
| `refundId` | string | 是 | 退款单 ID；仅退款提交成功时出现 |
| `tools` | `ToolTrace[]` | 否 | 本次实际执行的工具轨迹，按执行顺序；未调用工具时为 `[]` |
| `validation` | `ValidationResult` | 否 | 结果校验结果；目标契约要求失败时给出明确 `issues` |

`ToolTrace`：

| 字段 | 类型 | 可空 | 语义 |
| --- | --- | --- | --- |
| `tool` | enum `ToolName` | 否 | 工具名 |
| `success` | boolean | 否 | 工具是否成功 |
| `errorCode` | string | 是 | 失败时的平台错误码，例如 `FORBIDDEN`、`NOT_FOUND`、`REFUND_EXCEEDS_LIMIT` |
| `summary` | string | 是 | 工具返回的简短说明，不包含退款原因等敏感原文 |

`ValidationResult`：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `passed` | boolean | 是否通过 |
| `issues` | string[] | 失败原因；空数组表示通过 |

目标契约要求 `issues` 至少覆盖：`EMPTY_REPLY`、`GROUND_TRUTH_MISMATCH`、`ORDER_TOOL_RESULT_MISSING`、`ORDER_TOOL_<CODE>`、`ORDER_STATUS_MISSING`、`TICKET_TOOL_RESULT_MISSING`、`TICKET_TOOL_<CODE>`、`TICKET_DATA_INCOMPLETE`、`REFUND_TOOL_RESULT_MISSING`、`REFUND_TOOL_<CODE>`、`REFUND_ID_MISSING`、`HANDOFF_TOOL_RESULT_MISSING`、`HANDOFF_TOOL_<CODE>`、`HANDOFF_TICKET_MISSING`。

### 4.3 会话与状态副作用

一次成功的 `/api/chat` 在同一个事务内：

1. 为新会话插入 `conversation`，或对已有会话执行 `SELECT ... FOR UPDATE`；
2. 追加一条 `USER` 消息；
3. 更新 `conversation` 的 `status`、`intent`、`confidence`、`state_json`、`last_question`、`message_count`、`version`；
4. 追加一条 `ASSISTANT` 消息；
5. 每个工具调用写入 `tool_invocation`（输入脱敏、输出摘要/数据）；
6. 写入 `audit_log` 的 `CHAT_COMPLETED`；
7. 写入 `chat_request` 幂等记录。

目标契约要求：上述业务副作用与幂等记录必须原子提交；并发同键请求不得产生两次退款或两个工单。

### 4.4 错误

| HTTP | code | 触发条件 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 消息空/超长、`conversationId` 超长、`Idempotency-Key` 格式非法、JSON 不可解析 |
| 401 | `UNAUTHORIZED` | 缺少 `X-User-Id`、用户不存在或已停用 |
| 403 | `FORBIDDEN` | 会话不属于当前用户；订单/工单的工具级拒绝见 §7，HTTP 仍为 200，并在 `tools[].errorCode` 中返回 `FORBIDDEN` |
| 404 | `NOT_FOUND` | 指定的会话不存在 |
| 409 | `IDEMPOTENCY_CONFLICT` | 同一用户同一键对应不同请求哈希 |
| 409 | `CONFLICT` | 并发唯一键冲突或其他可重试冲突 |
| 409 | `CONVERSATION_NOT_ACTIVE` | 目标契约：会话处于 `WAITING_HUMAN` / `COMPLETED` / `FAILED`，拒绝继续 AI 工具链 |
| 500 | `INTERNAL_ERROR` | 未预期异常；不得泄露内部消息 |

### 4.5 结果校验不变量

目标契约要求至少核对：

- `ORDER_STATUS`：回复中的订单号等于工具 `orderId`，回复中的状态等于工具 `status`；
- `TICKET_STATUS`：回复中的工单号等于工具 `ticketId`，状态等于工具 `status`；
- `REFUND_SUBMIT`：回复中的退款单号等于工具 `refundId`，金额等于工具 `amount`；
- 人工兜底：回复中的工单号等于工具 `ticketId`；
- 任何回复不得出现 `null` 占位符、未渲染模板或他人订单号/金额；
- 校验失败时，不得把未执行/失败的工具结果包装成成功回复。

## 5. `GET /api/conversations/{conversationId}`

### 5.1 请求

```http
GET /api/conversations/conv-4f6c...
X-User-Id: cust-alice
```

权限：`CUSTOMER` 仅 own；`AGENT` 仅 assigned；`ADMIN` 全部。无权限返回 403；不存在返回 404。

### 5.2 响应 `200`

| 字段 | 类型 | 可空 | 语义 |
| --- | --- | --- | --- |
| `id` | string | 否 | 会话 ID |
| `userId` | string | 否 | 会话所有者 |
| `status` | enum | 否 | 会话状态 |
| `intent` | enum | 是 | 最近一次意图；新会话可能省略 |
| `confidence` | number | 是 | 最近一次置信度 |
| `pendingAction` | string | 是 | 当前待收集动作 |
| `lastQuestion` | string | 是 | 最近一次等待输入的问题 |
| `slots` | object | 否 | 当前槽位；键为字符串，值可为字符串/数字/布尔 |
| `messageCount` | integer | 否 | 已记录的消息数 |
| `version` | integer | 否 | 乐观锁版本 |
| `createdAt` | datetime | 否 | 创建时间 |
| `updatedAt` | datetime | 否 | 更新时间 |
| `messages` | `MessageView[]` | 否 | 按 `id` 升序的消息列表 |

`MessageView`：`role`（`USER`/`ASSISTANT`/`HUMAN_AGENT`）、`content`、`createdAt`。

## 6. 会话状态机与数据不变量

### 6.1 `ConversationStatus`

| 状态 | 含义 | 允许的下一状态 | AI 是否可执行工具 |
| --- | --- | --- | --- |
| `ACTIVE` | 空闲/已处理完一轮 | `ACTIVE`、`WAITING_FOR_INPUT`、`WAITING_HUMAN`、`COMPLETED`、`FAILED` | 是 |
| `WAITING_FOR_INPUT` | 等待槽位 | `ACTIVE`、`WAITING_FOR_INPUT`、`WAITING_HUMAN`、`FAILED` | 是，仅继续当前 `desiredIntent` |
| `WAITING_HUMAN` | 已转人工，等待客服 | `COMPLETED`、`FAILED` | 目标契约：**否** |
| `COMPLETED` | 当前流程完成 | 仅新建会话 | 目标契约：**否** |
| `FAILED` | 不可恢复失败 | 仅新建会话或管理员补偿 | 目标契约：**否** |

目标契约要求：

- `WAITING_HUMAN` 下调用 `/api/chat` 返回 409 `CONVERSATION_NOT_ACTIVE`，不执行任何 `ORDER_LOOKUP`、`TICKET_LOOKUP`、`REFUND_CREATE`、`HUMAN_HANDOFF`；
- `COMPLETED` / `FAILED` 同理；用户需要新会话时由客户端不传 `conversationId`；
- 不允许订单/工单/取消/通用分支把非 `ACTIVE` 会话静默改回 `ACTIVE`；
- 客服回复并 `resolve=true` 时，工单置 `RESOLVED`，会话置 `COMPLETED`；`resolve=false` 时工单置 `PROCESSING`，会话保持 `WAITING_HUMAN`。

### 6.2 `pendingAction`

| 值 | 触发 | 期望输入 | 下一动作 |
| --- | --- | --- | --- |
| `COLLECT_ORDER_ID` | 订单查询缺订单号；退款缺订单号 | `ORD-1001` 或“订单 1001” | 订单查询或退款槽位继续 |
| `COLLECT_TICKET_ID` | 工单查询未命中 | `TCK-3001` | 重新查询工单 |
| `COLLECT_REFUND_AMOUNT` | 退款缺金额 | “10 元”/“10” | 继续收集原因或确认 |
| `COLLECT_REFUND_REASON` | 退款缺原因 | 任意非空文本 | 继续确认 |
| `CONFIRM_REFUND` | 退款信息齐全 | “确认”/“提交”/“取消” | 提交退款或取消 |

目标契约要求：

- 第一次无法识别时保留原 `pendingAction` 并重问，不清空槽位；
- 连续两次无法识别才进入 `WAITING_HUMAN`；识别到任一有效槽位后未知计数归零；
- `CANCEL_ACTION` 清空 `pendingAction`、`refundAmount`、`refundReason`、`refundConfirmed`、`desiredIntent`，会话回 `ACTIVE`；
- `COLLECT_TICKET_ID` 必须与其它待办动作一样被 `providedPendingSlot` 识别，不能只依赖 `TCK-` 正则兜底。

### 6.3 退款状态机

| 阶段 | 触发 | 副作用 | 结果 |
| --- | --- | --- | --- |
| `REFUND_DRAFT` | 订单号 + 金额 + 原因齐全 | 只读 `ORDER_LOOKUP`，校验金额 ≤ 可退金额 | `WAITING_FOR_INPUT` + `CONFIRM_REFUND` |
| 确认 | 用户回复确认 | 无 | 进入 `REFUND_SUBMIT` |
| `REFUND_SUBMIT` | 计划执行 `REFUND_CREATE` | 在同一事务内锁定订单行、校验累计额度、扣减可退余额、插入退款 | `SUBMITTED` 或 `PENDING_REVIEW` |
| 完成 | 退款成功且校验通过 | 记录 `refundId` | `COMPLETED` |
| 大额 | 金额 ≥ `refund-review-threshold`（默认 500.00） | 创建退款后转人工审核 | `WAITING_HUMAN` |

目标契约要求：`refundable_amount` 是订单级累计余额，不是单会话额度；退款成功后原子扣减，任何会话的累计退款不得超过初始可退金额；退款校验失败不得静默丢弃已创建的退款记录。

### 6.4 人工工单状态机

| 状态 | 含义 | 允许的下一状态 |
| --- | --- | --- |
| `OPEN` | 新建/待接单 | `PROCESSING`、`RESOLVED` |
| `PROCESSING` | 客服处理中 | `RESOLVED` |
| `RESOLVED` | 已解决 | 目标契约：不可再次回复/重新打开 |

工单类别当前包括 `HANDOFF`、`DELIVERY`、`PAYMENT`；优先级包括 `LOW`、`NORMAL`、`HIGH`。目标契约要求数据库 `CHECK` 约束与应用枚举一致。

## 7. 工具契约

所有工具由 `ToolExecutionService.execute(tool, arguments, actor, conversationId, traceId, idempotencyKey)` 统一调度。每次调用都必须记录 `tool_invocation`，并写审计（成功/拒绝）。工具级权限失败不改变 `/api/chat` 的 HTTP 状态：响应仍为 200，失败通过 `tools[].success=false`、`tools[].errorCode=FORBIDDEN` 和 `validation.passed=false` 表达；只有在进入工具前就拒绝会话访问时才返回 HTTP 403。

### 7.1 `ORDER_LOOKUP`

| 项 | 契约 |
| --- | --- |
| 输入 | `{ "orderId": "ORD-1001" }`，必填 |
| 输出 `data` | `orderId`、`status`、`totalAmount`、`refundableAmount`、`currency` |
| 权限 | 订单所有者、关联工单负责人、管理员 |
| 错误 | `VALIDATION_ERROR`（缺 ID）、`NOT_FOUND`、`FORBIDDEN`、`INTERNAL_ERROR` |
| 副作用 | 只读；写 `ORDER_READ` 审计和工具调用记录 |

### 7.2 `TICKET_LOOKUP`

| 项 | 契约 |
| --- | --- |
| 输入 | `{ "ticketId": "TCK-3001" }` 可选；省略或空时查当前用户最近工单 |
| 输出 `data` | `ticketId`、`status`、`category`、`priority`、可选 `orderId`、可选 `assignedAgentId` |
| 权限 | 工单所有者、工单负责人、管理员 |
| 错误 | `NOT_FOUND`、`FORBIDDEN`、`INTERNAL_ERROR` |
| 副作用 | 只读；写 `TICKET_READ` 审计和工具调用记录 |

### 7.3 `REFUND_CREATE`

| 项 | 契约 |
| --- | --- |
| 输入 | `orderId`、`refundAmount`（>0，最多 2 位小数）、`refundReason`（1..300） |
| 输出 `data` | `refundId`、`orderId`、`amount`、`status`、`reused` |
| 状态 | `<500.00` 为 `SUBMITTED`；`>=500.00` 为 `PENDING_REVIEW` |
| 权限 | 订单所有者、关联工单负责人、管理员 |
| 错误 | `VALIDATION_ERROR`、`NOT_FOUND`、`FORBIDDEN`、`REFUND_EXCEEDS_LIMIT`、`INTERNAL_ERROR` |
| 幂等 | 同一 `(conversationId, orderId)` 的重复请求返回原记录，`reused=true`；跨会话累计不得超过订单余额 |
| 副作用 | 锁定订单行、扣减 `refundable_amount`、插入 `refund_request`、写 `REFUND_SUBMIT` 审计和工具调用记录 |

### 7.4 `HUMAN_HANDOFF`

| 项 | 契约 |
| --- | --- |
| 输入 | `reason`（1..160 存储摘要）、可选 `orderId` |
| 输出 `data` | `ticketId`、`status`、`assignedAgentId`（无可用客服时为 `UNASSIGNED`）、`reused` |
| 权限 | 当前会话所有者；客服/管理员按权限矩阵 |
| 错误 | `VALIDATION_ERROR`、`INTERNAL_ERROR` |
| 幂等 | 同一会话最多一个 `category='HANDOFF'` 的未关闭工单；重复请求返回同一 `ticketId` |
| 副作用 | 选择当前 `OPEN`/`PROCESSING` 工单最少的 `AGENT`；插入 `OPEN`/`HIGH` 工单；写 `HUMAN_HANDOFF` 审计和工具调用记录 |

### 7.5 工具调用记录

`tool_invocation` 字段：`conversation_id`、`trace_id`、`tool_name`、`input_json`、`output_json`、`success`、`error_code`、`latency_ms`、`created_at`。目标契约要求：

- `input_json` 不得出现退款原因原文；当前使用 `refundReasonHash`；
- `output_json` 只保留摘要和业务字段，不记录完整用户消息；
- 工具异常不得让调用方看到堆栈；对外只返回 `INTERNAL_ERROR` 和安全消息；
- 工具调用记录与业务副作用处于同一事务；若无法保证，必须引入补偿/对账。

## 8. 幂等与并发

### 8.1 聊天幂等

- 作用域：`(userId, Idempotency-Key)`；
- 请求哈希：`SHA-256((conversationId 或空串) + "\n" + 去首尾空白后的 message)`；
- 同键同哈希：返回首次存储的完整 `ChatResponse`，不重复执行工具、不重复写消息；
- 同键不同哈希：409 `IDEMPOTENCY_CONFLICT`；
- 未提供键：服务端生成随机 UUID，仅保证本次请求唯一，不保证客户端重试去重；
- 并发同键：目标契约要求数据库唯一约束 + 捕获冲突后读取并返回首次响应；不得让一个请求成功、另一个以 409 `CONFLICT` 结束；
- 重放响应体中的 `traceId` 是首次请求的值；响应头 `X-Trace-Id` 是当前请求的值。若客户端需要“当前追踪”，应在日志中同时记录两者。

### 8.2 退款幂等

- 聊天幂等保证同一请求只执行一次退款；
- 退款业务去重按 `(conversationId, orderId)`；
- 订单级累计额度由退款事务锁定 `customer_order` 行并扣减 `refundable_amount`；
- 重复请求返回原 `refundId`、原金额、`reused=true`，不得新增退款行；
- 若退款已进入 `PENDING_REVIEW`，重复请求仍返回原记录，不重复创建审核工单。

### 8.3 人工工单幂等

- `support_ticket` 对 `(conversation_id, category='HANDOFF')` 建立唯一约束或等价的部分唯一索引；
- 重复/并发 `HUMAN_HANDOFF` 返回同一 `ticketId`，`reused=true`；
- 客服回复目标契约支持 `Idempotency-Key`，作用域 `(ticketId, actorId, key)`；重复提交返回同一工单状态，不重复插入 `HUMAN_AGENT` 消息。

### 8.4 事务与锁

- `/api/chat` 使用单事务；会话更新使用 `version` 乐观锁；
- 已有会话在续聊时使用 `SELECT ... FOR UPDATE`；
- 退款必须锁定订单行（或使用 `UPDATE ... WHERE refundable_amount >= ?` 的原子条件）；
- 人工工单唯一性由数据库约束兜底，不能只靠先查后插；
- 审计的 `DENIED` 记录使用独立事务提交，确保拒绝原因不因业务回滚丢失。

## 9. 审计契约

### 9.1 `GET /api/audit`

请求：

```http
GET /api/audit?resourceId=ORD-1001&limit=100
X-User-Id: admin
```

| 参数 | 类型 | 必填 | 约束 | 语义 |
| --- | --- | --- | --- | --- |
| `resourceId` | string | 否 | ≤64 | 按资源 ID 过滤；省略则返回最近记录 |
| `limit` | integer | 否 | 默认 100，服务端夹取 1..500 | 返回条数 |

权限：仅 `ADMIN`；客户/客服返回 403 `FORBIDDEN`。

响应：`AuditLog[]`，按 `id` 降序。

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `id` | long | 审计记录 ID |
| `traceId` | string | 请求追踪 ID |
| `actorId` | string | 操作者 |
| `actorRole` | enum | `CUSTOMER`/`AGENT`/`ADMIN` |
| `action` | string | 动作名，见下表 |
| `resourceType` | string | `ORDER`/`TICKET`/`REFUND`/`CONVERSATION`/`ADMIN_RESOURCE`/`EVALUATION` |
| `resourceId` | string | 资源 ID，可空 |
| `outcome` | string | `ALLOWED` 或 `DENIED` |
| `detailJson` | string | JSON 字符串；不是嵌套对象 |
| `createdAt` | datetime | 创建时间 |

### 9.2 动作与结果

| action | 触发 | outcome |
| --- | --- | --- |
| `CHAT_COMPLETED` | 一轮聊天完成 | `ALLOWED` |
| `ORDER_READ` | 订单查询成功 | `ALLOWED`/`DENIED` |
| `TICKET_READ` | 工单查询成功 | `ALLOWED`/`DENIED` |
| `REFUND_SUBMIT` | 退款创建成功 | `ALLOWED` |
| `HUMAN_HANDOFF` | 人工工单创建成功 | `ALLOWED` |
| `CONVERSATION_READ` | 读取会话 | `ALLOWED`/`DENIED` |
| `CONVERSATION_CHAT` | 对他人会话发起聊天 | `DENIED` |
| `AUDIT_READ` | 读取审计 | `ALLOWED`/`DENIED` |
| `HANDOFF_LIST` | 读取人工工单列表 | `ALLOWED`/`DENIED` |
| `HANDOFF_REPLY` | 客服回复工单 | `ALLOWED`/`DENIED` |
| `EVALUATION_RUN` | 运行评测 | `ALLOWED`/`DENIED` |

审计中的 `detailJson` 不得包含退款原因原文、完整消息正文或未脱敏的敏感字段；目标契约要求只记录原因哈希、资源 ID、状态、金额等最小必要信息。

## 10. 人工工单 API

### 10.1 `GET /api/handoffs`

```http
GET /api/handoffs?status=OPEN
X-User-Id: agent-lin
```

| 参数 | 类型 | 必填 | 语义 |
| --- | --- | --- | --- |
| `status` | string | 否 | `OPEN`/`PROCESSING`/`RESOLVED`；非法值目标契约返回 400 `VALIDATION_ERROR` |

权限：`AGENT` 仅返回 `assigned_agent_id = 当前用户` 的 `HANDOFF` 工单；`ADMIN` 返回全部；`CUSTOMER` 403。

响应：`SupportTicket[]`，按 `created_at` 降序。

`SupportTicket` 字段：`id`、`userId`、`orderId`、`conversationId`、`category`、`subject`、`status`、`priority`、`assignedAgentId`、`createdAt`、`updatedAt`。

### 10.2 `POST /api/handoffs/{ticketId}/messages`

```http
POST /api/handoffs/TCK-9F8E7D6C/messages
X-User-Id: agent-lin
Idempotency-Key: reply-001
Content-Type: application/json

{
  "message": "已为您处理完成",
  "resolve": true
}
```

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `message` | string | 是 | trim 后 1..2000 |
| `resolve` | boolean | 是 | `true` 解决并关闭会话；`false` 保持处理中 |

权限：工单负责人或管理员；客户/未分配客服 403。

目标契约：

- 仅允许 `category='HANDOFF'` 且状态为 `OPEN`/`PROCESSING` 的工单；`RESOLVED` 再回复返回 409 `CONFLICT`；
- `resolve=true`：工单 `RESOLVED`，会话 `COMPLETED`，追加 `HUMAN_AGENT` 消息，`message_count+1`；
- `resolve=false`：工单 `PROCESSING`，会话保持 `WAITING_HUMAN`，追加消息；
- 同一 `Idempotency-Key` 重放返回同一结果，不重复追加消息。

响应：更新后的 `SupportTicket`。

## 11. 评测契约

### 11.1 `POST /api/admin/evaluations/run`

```http
POST /api/admin/evaluations/run
X-User-Id: admin
```

请求体：无。权限：仅 `ADMIN`；其他角色 403。

响应 `200`：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `runId` | string | 本次评测 ID，`eval-` + 32 位十六进制 |
| `startedAt` | Instant | 开始时间 |
| `finishedAt` | Instant | 结束时间 |
| `total` | int | 用例总数，目标为 5 |
| `passed` | int | 通过数 |
| `passRate` | double | `passed/total` |
| `llmMode` | string | `RULES_ONLY` 或 `LLM_HYBRID` |
| `cases` | `EvaluationCaseResult[]` | 逐用例结果 |

`EvaluationCaseResult`：`name`、`passed`、`expected`、`actual`、`traceId`、`detail`。

目标用例：

1. `order-status`：`ORDER_STATUS` + `ORDER_LOOKUP` + 校验通过；
2. `ticket-status`：`TICKET_STATUS` + `TICKET_LOOKUP` + 校验通过；
3. `explicit-handoff`：`HUMAN_HANDOFF` + 工单 + `WAITING_HUMAN`；
4. `unknown-fallback`：两次未知意图 → `HANDOFF` + `WAITING_HUMAN`；
5. `refund-confirmation`：收集 → 确认 → `REFUND_CREATE` + 校验通过。

目标契约要求评测隔离：不得改变真实订单的 `refundable_amount`，不得为真实用户留下未关闭的人工工单。实现可以采用独立测试用户/租户、事务回滚或内存数据库。若暂时无法隔离，必须在响应和文档中明确标注“会写业务数据”，但不作为默认推荐。

## 12. 错误码

| code | HTTP | 语义 |
| --- | --- | --- |
| `UNAUTHORIZED` | 401 | 缺少/未知/停用身份 |
| `FORBIDDEN` | 403 | 已认证但无权限 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `VALIDATION_ERROR` | 400 | 参数/JSON/枚举/类型不合法 |
| `CONFLICT` | 409 | 唯一键冲突、状态冲突、不可重试的并发冲突 |
| `IDEMPOTENCY_CONFLICT` | 409 | 同一幂等键对应不同请求 |
| `CONVERSATION_NOT_ACTIVE` | 409 | 目标契约新增：会话不允许继续 AI 处理 |
| `INTERNAL_ERROR` | 500 | 未预期异常；不得泄露堆栈 |

错误响应体：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "message: 不能为空",
  "traceId": "01a1537c-...",
  "timestamp": "2026-10-19T09:30:00Z"
}
```

目标契约要求 `MethodArgumentTypeMismatchException`、未知路径、不支持的方法返回 4xx；当前通用 `Exception` 兜底会把这些情况映射为 500（见 §15 G15）。

## 13. LLM 模式边界

### 13.1 配置

| 配置 | 默认 | 语义 |
| --- | --- | --- |
| `AI_LLM_ENABLED` / `ai.llm.enabled` | `false` | `false` 时完全离线，使用规则分类器 |
| `OPENAI_API_KEY` | 空 | `enabled=true` 且为空时启动失败，避免静默降级 |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI 兼容端点 |
| `OPENAI_MODEL` | `gpt-4.1-mini` | 模型名 |
| `ai.llm.timeout` | `20s` | 单次分类超时 |

### 13.2 行为边界

- 规则分类置信度 `>= 0.65` 或有 `pendingAction` 时，不调用 LLM；
- LLM 仅在规则置信度不足时参与**意图和槽位**分类；
- LLM 输出必须是 JSON 对象：`intent`、`confidence`、`orderId`、`ticketId`、`refundAmount`、`refundReason`、`requiresHuman`；
- LLM 不得直接执行工具、不得决定权限、不得绕过 `AccessControlService`；
- LLM 异常、超时、非法 JSON、非法枚举、非法金额时必须回退规则结果，不把异常抛给用户；
- `requiresHuman=true` 只能作为人工兜底信号，不能替代 `HUMAN_HANDOFF` 工具的权限和状态机；
- 所有 LLM 槽位在进入 `TaskPlanner` 前必须经过长度、枚举、金额精度和必填校验；
- 测试必须使用假 `ChatModel`，不得依赖真实网络；真实模型评测只作为可选的手工验证。

### 13.3 模式标识

`EvaluationReport.llmMode` 在 `AI_LLM_ENABLED=false` 时为 `RULES_ONLY`，为 `true` 时为 `LLM_HYBRID`。`ChatResponse` 不单独返回模式；模式由配置和评测报告体现。

## 14. 八个链路环节映射

| 环节 | 入口/组件 | 契约锚点 | 验收命令/测试方向 |
| --- | --- | --- | --- |
| 意图识别 | `/api/chat` → `HybridIntentClassifier` | §4、§13 | 规则模式、LLM 假模型、非法 JSON 回退 |
| 任务规划 | `TaskPlanner` → `Plan`/`PlanStep` | §4、§6、§7 | 缺槽位、确认、取消、未知兜底 |
| 工具调用 | `ToolExecutionService` | §7、§8 | 四个工具的成功/失败/权限/幂等 |
| 状态记忆 | `ConversationState` + `conversation` | §5、§6 | 续聊、槽位保留、版本冲突 |
| 结果校验 | `ResultValidator` | §4.5、§8 | 事实一致/不一致、补偿 |
| 人工兜底 | `HUMAN_HANDOFF` + `HandoffService` | §6、§7.4、§10 | 单工单、分配、解决、状态阻断 |
| 权限审计 | `AccessControlService` + `audit_log` | §3、§9 | 客户/客服/管理员矩阵、DENIED |
| 评测 | `EvaluationService` | §11、§13 | 5 用例、隔离、llmMode |

## 15. 当前实现与目标契约的差异

以下差异与 `docs/plan.md` §8 的 G 编号一一对应。它们是 backend 修复卡的输入，不代表当前实现已经满足目标契约。

| 编号 | 当前实现 | 目标契约 | 证据 |
| --- | --- | --- | --- |
| G1 | 单笔校验，退款后不扣余额；去重仅按会话+订单 | 订单级累计上限、原子扣减、跨会话共享额度 | `ToolExecutionService.java:159-181`、`OrderRepository.java:18-33`、`V1__schema.sql:14` |
| G2 | 先查后插人工工单，无唯一约束 | 同会话最多一个未关闭 HANDOFF 工单 | `ToolExecutionService.java:204-214`、`V1__schema.sql:116` |
| G3 | 非 ACTIVE 会话仍可继续 AI，并可回到 ACTIVE | 状态机阻断，返回 `CONVERSATION_NOT_ACTIVE` | `CustomerServiceOrchestrator.java:161-181,191-246`、`HandoffService.java:74-83` |
| G4 | 只检查字段存在和 `"null"` 子串 | 回复与工具事实逐项一致 | `ResultValidator.java:14-76` |
| G5 | 评测直接写真实会话/退款/工单 | 隔离或显式标注副作用 | `EvaluationService.java:38-65,117-134` |
| G6 | LLM 槽位直接进入规划，无 schema 校验 | LLM 只输出候选，必须校验后才可用 | `LlmIntentClassifier.java:54-77`、`TaskPlanner.java:16-36` |
| G7 | 未识别回复清空 `pendingAction` | 保留待办并重问，连续两次才转人工 | `RuleBasedIntentClassifier.java:79-95`、`TaskPlanner.java:91-104` |
| G8 | 幂等先查后插，并发冲突返回 409 CONFLICT | 并发同键返回同一响应 | `CustomerServiceOrchestrator.java:104-110,157,545-572`、`GlobalExceptionHandler.java:50-55` |
| G9 | 退款成功后校验失败只转人工，无补偿 | 保留退款记录并进入复核/补偿 | `CustomerServiceOrchestrator.java:125-130,316-365,451-465` |
| G10 | `X-User-Id` 完全可信 | 生产必须认证；演示明确标注 | `AccessControlService.java:36-43`、`ChatController.java:33` |
| G11 | 人工回复无幂等、可回复已解决工单 | 幂等 + 状态门禁 | `HandoffService.java:51-88`、`HandoffController.java:45-53` |
| G12 | 状态/类别/优先级缺少 CHECK | 数据库约束与应用枚举一致 | `V1__schema.sql:27-30,45,56` |
| G13 | 退款原因在会话消息中明文 | 脱敏/加密/保留期限策略 | `ConversationRepository.java:94-100`、`ToolExecutionService.java:266-273` |
| G14 | `plan.reason()` 字符串 switch，未知 reason 静默 general | 使用枚举/封闭集合，未知即失败 | `TaskPlanner.java:20-99`、`CustomerServiceOrchestrator.java:191-226` |
| G15 | 参数类型错误/未知路径可能 500 | 统一 400/404 | `AuditController.java:35`、`GlobalExceptionHandler.java:58-64` |
| G16 | 现有测试只覆盖主路径 | 增加并发退款、跨会话重复退款、状态阻断、人工回复幂等、LLM 假模型、非法参数测试 | `ChatApiIntegrationTest.java:37-162` |

## 16. 兼容与验收不变量

以下不变量必须在 backend 修复后成立，并由 reviewer 独立核对：

1. **数据隔离**：任何客户/客服都不得通过任何端点读到他人订单、工单、会话或消息；管理员例外。
2. **资金上限**：同一订单的累计退款金额不超过初始 `refundable_amount`；余额永不为负；重复/并发请求不产生超额退款。
3. **人工唯一**：同一会话最多一个未关闭 `HANDOFF` 工单；重复请求返回同一 `ticketId`。
4. **状态自洽**：`WAITING_HUMAN`/`COMPLETED`/`FAILED` 不再执行 AI 工具；客服解决后会话与工单状态一致。
5. **幂等可重放**：同键同哈希返回同一响应；同键不同哈希返回 `IDEMPOTENCY_CONFLICT`；副作用只发生一次。
6. **结果可信**：回复中的订单号、状态、退款号、金额、工单号与工具事实一致；校验失败有明确兜底/补偿。
7. **审计可追溯**：每次允许/拒绝都有 `traceId`、`actorId`、`action`、`resourceType`、`outcome`；拒绝记录不因业务回滚丢失。
8. **离线可测**：默认 `RULES_ONLY` 下测试不联网；LLM 模式用假模型覆盖回退与 JSON 边界。
9. **迁移可回滚**：新增约束/索引的迁移和回滚在干净 H2 数据库上可执行；生产 PostgreSQL 需单独验证。
10. **契约稳定**：本文的路径、字段名、枚举、错误码是 backend 修复的边界；实现不得擅自改名或改变语义。
