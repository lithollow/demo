# 验证记录

验证日期：2026-10-19  
环境：OpenJDK 21.0.12.1、Maven 3.6.3、Node.js v24.21.0  
版本：Spring Boot 3.5.16、LangChain4j 1.22.0

## 1. 自动化测试

命令：

```bash
mvn -q test
```

退出码：`0`

Surefire 汇总：

```text
ChatApiIntegrationTest: Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
HybridIntentClassifierTest: Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
ResultValidatorTest: Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

覆盖新增行为：

- `refundBalanceIsSharedAcrossConversations`：订单 `ORD-1002` 先退 400 后余额为 499.00，新会话再退 500 被要求重新输入金额，退款记录数保持 1。
- `waitingHumanConversationRejectsFurtherToolCalls`：转人工后继续聊天返回 `CONVERSATION_NOT_ACTIVE`，工具调用计数不增加。
- `pendingRefundSlotSurvivesOneUnrecognizedTurn`：一次无法识别不会清空 `COLLECT_REFUND_AMOUNT`。
- `evaluationRunsAreRolledBackAndRepeatable`：连续运行两次评测，退款和人工工单计数不变，两次均 5/5。
- `concurrentIdempotentChatReturnsOneResponseAndOneConversation`：并发同键请求返回同一响应，只创建一次会话。
- `concurrentRefundsCannotExceedOrderBalance`：两笔退款竞争同一订单时只成功一笔，余额不为负。
- `concurrentHandoffCreatesOneTicketAndFreezesConversation`：并发转人工只产生一个工单，会话被冻结。
- `clientInputErrorsUseStructured4xxResponses`：错误参数、错误方法和未知路径返回 400/405/404。
- `HybridIntentClassifierTest`：规则优先、LLM 异常回退和非法槽位过滤。
- `ResultValidatorTest`：回复订单号、退款金额或状态与工具事实不一致时校验失败。

## 2. 构建

命令：

```bash
mvn -q package
```

退出码：`0`

产物：

```text
target/ai-customer-service-0.1.0-SNAPSHOT.jar
```

## 3. 前端静态检查

命令：

```bash
node --check src/main/resources/static/app.js
rg -n "innerHTML|https?://|cdn|eval\(|new Function" src/main/resources/static
```

结果：

- `node --check` 退出码 `0`。
- 第二条命令无输出，静态前端没有 `innerHTML`、外部 URL/CDN、`eval` 或 `new Function`。

## 4. 真实 HTTP 冒烟

启动：

```bash
java -jar target/ai-customer-service-0.1.0-SNAPSHOT.jar --server.port=18080
```

本机实际结果：

| 检查 | 结果 |
| --- | --- |
| `GET /` | `200`，`8684` bytes |
| `POST /api/chat` 查询 `ORD-1001` | `intent=ORDER_STATUS`，`validation.passed=true`，回复包含 `SHIPPED` |
| `GET /api/conversations/{id}` | `200`，状态 `ACTIVE`，消息数 `2`，槽位包含 `orderId=ORD-1001` |
| `GET /api/audit?resourceId=ORD-1001` | `200`，包含 `ORDER_READ / ALLOWED` 审计 |
| `POST /api/admin/evaluations/run` | `200`，`total=5`、`passed=5`、`passRate=1.0` |
| 评测后再次查询 `ORD-1001` | 可退金额仍为 `299.00 CNY`，证明评测事务已回滚 |

## 5. 未验证范围

- 未调用真实 OpenAI 或兼容模型：没有 API Key，默认规则模式不影响演示与测试；真实模型质量、限流和成本未验证。
- 未在 PostgreSQL 上执行迁移与并发压测：当前由 H2 PostgreSQL 兼容模式验证；生产库需单独验证 `FOR UPDATE`、唯一索引与回滚脚本。
- 未做浏览器端自动化截图测试：已通过静态语法检查与真实同源 API 冒烟；交互建议按 README 中的演示路径手动验收。
- 未实现生产级认证：`X-User-Id` 是演示身份，不能作为安全边界上线。
