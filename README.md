# Aster AI 客服

基于 Spring Boot 3.5.16、LangChain4j 1.22.0 与 Java 21 的可运行 AI 客服示例。项目把一次客服请求拆成八个可验证阶段：

```
意图识别 → 任务规划 → 工具调用 → 状态记忆 → 结果校验 → 人工兜底 → 权限审计 → 离线评测
```

默认使用可重复的本地规则分类器，因此不需要 API Key 或外网即可启动、测试和演示。配置 OpenAI 兼容接口后，LangChain4j 会在规则置信度不足时参与意图识别；无论模型返回什么，工具权限、退款额度、资源归属和结果校验都由服务端强制执行。

## 快速启动

环境要求：JDK 21、Maven 3.6+。

```bash
mvn test
mvn package
java -jar target/ai-customer-service-0.1.0-SNAPSHOT.jar
```

打开 <http://localhost:8080/>。H2 是内存数据库，重启后恢复为种子数据。

演示身份通过请求头 `X-User-Id` 切换：

| 身份 | 角色 | 数据 |
| --- | --- | --- |
| `cust-alice` | 客户 | 订单 `ORD-1001`、`ORD-1002`，工单 `TCK-3001` |
| `cust-bob` | 客户 | 订单 `ORD-2001`，工单 `TCK-3002` |
| `agent-lin` / `agent-wang` | 客服 | 处理分配给自己的转人工工单 |
| `admin` | 管理员 | 审计、全部工单、评测 |

## 核心流程

| 阶段 | 实现 |
| --- | --- |
| 意图识别 | `RuleBasedIntentClassifier` 负责离线基线；`LlmIntentClassifier` 通过 LangChain4j `ChatModel` 输出受限 JSON；置信度不足时混合调度 |
| 任务规划 | `TaskPlanner` 将意图和槽位映射为查询订单、查询工单、退款草稿/提交、转人工或澄清动作 |
| 工具调用 | `ToolExecutionService` 统一执行 `ORDER_LOOKUP`、`TICKET_LOOKUP`、`REFUND_CREATE`、`HUMAN_HANDOFF` |
| 状态记忆 | 会话、槽位、待办动作、未知轮次和消息持久化到 H2；会话行使用 `FOR UPDATE` 与版本号控制并发 |
| 结果校验 | `ResultValidator` 核对回复中的订单号、状态、退款单/金额、工单号是否与工具事实一致 |
| 人工兜底 | 显式转人工、连续两轮未知、工具失败或校验失败都会创建/复用人工工单，并冻结 AI 工具链 |
| 权限审计 | 客户/客服/管理员边界、资源归属检查、工具调用记录和允许/拒绝审计 |
| 评测 | 5 个离线场景覆盖查单、查工单、显式转人工、未知兜底与退款；整轮评测事务最终回滚，可重复运行 |

一次退款会锁定订单行并原子扣减 `refundable_amount`。同一订单的额度由所有会话共享，超额请求会被拒绝。人工工单在数据库层对 `(conversation_id, category)` 建立唯一约束。

## 配置 LLM

```bash
AI_LLM_ENABLED=true \
OPENAI_API_KEY=your-key \
OPENAI_BASE_URL=https://api.openai.com/v1 \
OPENAI_MODEL=gpt-4.1-mini \
java -jar target/ai-customer-service-0.1.0-SNAPSHOT.jar
```

模型只负责意图和槽位分类，不能直接执行工具。非法 JSON、超时、未知枚举或越界槽位会回退到规则结果；模型返回的订单号、工单号、金额和原因都会再做格式与范围校验。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/chat` | 新建或继续会话，可选 `Idempotency-Key` |
| `GET` | `/api/conversations/{id}` | 恢复会话、槽位和消息 |
| `GET` | `/api/audit` | 管理员读取审计，可按 `resourceId` 过滤 |
| `GET` | `/api/handoffs` | 客服/管理员读取人工工单 |
| `POST` | `/api/handoffs/{ticketId}/messages` | 客服/管理员回复并可关闭工单 |
| `POST` | `/api/admin/evaluations/run` | 管理员运行离线评测 |

完整字段、状态机、错误码和权限矩阵见 [docs/api-contract.md](docs/api-contract.md)。

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'X-User-Id: cust-alice' \
  -H 'Idempotency-Key: demo-001' \
  -H 'Content-Type: application/json' \
  -d '{"message":"我的订单 ORD-1001 到哪了？"}'
```

## 测试与验证

```bash
mvn -q test
node --check src/main/resources/static/app.js
```

当前自动化测试覆盖 14 个 API 集成场景、5 个意图/规划单元测试和 5 个结果校验单元测试，包括：

- 订单查询、幂等重放与跨用户越权拒绝；
- 退款槽位收集、确认、金额扣减、跨会话额度共享；
- 待办动作在一次无法识别后保留；
- `WAITING_HUMAN` 后继续聊天返回 `409 CONVERSATION_NOT_ACTIVE`；
- 人工工单回复与解决；
- 连续未知意图转人工；
- 评测权限、重复运行与事务回滚；
- 回复与底层工具事实不一致时校验失败；
- 并发幂等聊天、并发退款额度竞争与并发转人工唯一性；
- LLM 异常回退、非法意图与槽位边界；
- 非法参数、错误方法和未知路径的结构化 4xx 响应。

本机验证记录和真实 HTTP 冒烟证据见 [docs/verification.md](docs/verification.md)。

## 安全边界

这是完整的演示项目，但 `X-User-Id` 不是生产认证机制：能访问服务的人可以伪造已存在用户。生产部署必须由网关签名或 OIDC 提供主体身份，并补充：

- 真实数据库（PostgreSQL）上的锁、迁移与并发压测；
- 真实 LLM 服务的超时、限流、成本与提示注入评测；
- 敏感消息加密、数据保留策略与审计导出权限；
- 退款审核、人工工单状态机和客服工单分配的正式业务规则。

当前人工回复接口没有独立幂等键；重复提交可能追加重复客服消息。此限制不阻塞演示，但上线前需要补齐。
