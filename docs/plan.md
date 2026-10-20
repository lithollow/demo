# AI 客服系统修复计划与验收契约

版本：`planner-contract-v1`

范围：`/root/projects/demo`，Spring Boot 3.5.16 + LangChain4j 1.22.0 + Java 21

本文件只描述计划、任务卡、验收标准和缺口，不包含业务实现改动。

> **2026-10-19 实施状态更新**：B1 阻塞项已由 root 接管修复，退款额度、人工工单唯一性、状态机门禁、事实校验、评测回滚与相关测试已落地。本文第 1、2、8 节保留为修复前的评审快照；当前证据以 `docs/verification.md` 和最新测试报告为准，仍需由独立 reviewer 复核。

## 1. 结论

现有实现已经打通“意图识别 → 任务规划 → 工具调用 → 状态记忆 → 结果校验 → 人工兜底 → 权限审计与评测”的演示链路，且离线规则模式的 6 个集成测试全部通过。
但它还不能按“可安全交付”的标准收口，主要阻塞项是：

1. 退款只校验单笔金额，不扣减订单可退余额，跨会话可以重复退款（资金正确性）；
2. 人工兜底按会话先查后插，没有数据库唯一约束，并发时可能生成重复工单；
3. `WAITING_HUMAN` / `COMPLETED` 会话仍可继续走 AI 工具链，可能绕过人工处理并把会话重新置为 `ACTIVE`；
4. 结果校验只检查字段是否存在，没有逐项核对回复与工具事实；
5. 评测接口会直接创建真实退款、工单和会话，重复运行会污染业务数据。

因此计划可执行，但必须先完成一张 backend 修复卡，再由独立 reviewer 做只读评审，最后由 root 固化验证命令并收口。当前不建议直接判定为“可交付”。

## 2. 基线事实（可复现）

| 事实 | 证据 |
| --- | --- |
| 测试基线为 6 个测试、0 失败 | `mvn -q test` 后读取 `target/surefire-reports/com.example.aicsupport.ChatApiIntegrationTest.txt`：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| 可执行包可构建 | `mvn -q -o -DskipTests package`，退出码 0 |
| 实现文件当前未纳入 Git | `git status --short` 输出 `?? pom.xml`、`?? src/`；`AGENTS.md`、`config.toml`、`agents/*.toml` 没有出现在修改列表中 |
| 项目代码规模 | `src/main/java` 共 3,743 行，`src/test/java` 198 行；核心编排在 `CustomerServiceOrchestrator.java`（605 行） |
| 独立 HTTP 进程冒烟未完成 | `java -jar target/ai-customer-service-0.1.0-SNAPSHOT.jar --server.port=18081` 在当前沙箱失败：`java.net.SocketException: Operation not permitted`。HTTP 层行为以 MockMvc 集成测试为准，真实端口监听未验证 |

说明：`mvn -q test` 已真实执行；在 root 把命令写入 `AGENTS.md` §7 之前，本计划中的构建/测试命令只作为本轮证据，不作为团队长期契约。

## 3. 目标与非目标

### 3.1 本轮目标

在保持现有 REST 字段和离线规则模式可用的前提下，修复资金、人工兜底、状态机、幂等并发、结果校验和评测隔离问题，并补齐可复现的测试证据。

### 3.2 本轮明确不做

- 不做前端页面、登录页、SSO 或 OAuth2；身份仍以演示用 `X-User-Id` 为准，但必须在契约中标注为非生产方案。
- 不引入消息队列、Redis、分布式锁、外部支付或真实退款通道。
- 不把实时 LLM 调用放入默认测试链路；LLM 模式使用可注入的假模型测试。
- 不做与任务无关的重构、格式化、依赖升级或全仓库清理。

## 4. 八个链路环节与验收锚点

| 环节 | 现有组件 | 契约锚点 | 必须可判定的结果 |
| --- | --- | --- | --- |
| 意图识别 | `HybridIntentClassifier`、`RuleBasedIntentClassifier`、可选 `LlmIntentClassifier` | `docs/api-contract.md` §4、§13 | 规则模式可离线复现；LLM 异常回退规则结果；非法 LLM JSON 不产生工具调用 |
| 任务规划 | `TaskPlanner`、`Plan`、`PlanStep`、`ActionType` | §4、§6、§7 | 计划只包含允许的动作；缺槽位时进入对应 `pendingAction`；拒绝字符串魔法分支的静默降级 |
| 工具调用 | `ToolExecutionService`、四个 `ToolName` | §7、§8 | 每次调用都有权限校验、审计和 `tool_invocation` 记录；失败不泄露他人数据 |
| 状态记忆 | `ConversationState`、`state_json`、`conversation_message` | §5、§6 | 会话可恢复；槽位、待办动作、未知轮次在事务内一致更新；并发更新使用版本号 |
| 结果校验 | `ResultValidator`、`ValidationResult` | §4.5、§7 | 回复必须与工具事实一致；校验失败有明确兜底或补偿；不能只检查“字段非空” |
| 人工兜底 | `HandoffService`、`support_ticket` | §6、§7.4、§10 | 显式转人工和连续两次未知都只生成一个工单；`WAITING_HUMAN` 不被 AI 静默接管 |
| 权限审计 | `AccessControlService`、`AuditService`、`audit_log` | §3、§9、§10 | 客户、客服、管理员的读/写边界可测试；拒绝请求留下 `DENIED` 审计 |
| 评测 | `EvaluationService`、`/api/admin/evaluations/run` | §11、§13 | 评测可重复运行且不改变真实订单可退余额；报告能区分规则模式与 LLM 模式 |

## 5. 任务卡 B1：后端一致性修复

### 目标

在不改变已发布 REST 字段语义的前提下，修复退款累计上限、人工兜底幂等、会话状态机、结果校验、评测隔离和 LLM 边界，使八条链路在离线规则模式下有可复现的自动化证据。

### 输入

- `/root/projects/demo/AGENTS.md`
- `/root/projects/demo/docs/api-contract.md`
- `/root/projects/demo/pom.xml`
- `/root/projects/demo/src/main/java/**`
- `/root/projects/demo/src/main/resources/db/migration/**`
- `/root/projects/demo/src/main/resources/db/rollback/**`
- `/root/projects/demo/src/test/**`
- 本文件的 §8（缺口清单）和 §9（风险与假设）

### 约束

- 只修改 `src/main/**`、`src/test/**`、`src/main/resources/db/**`、`pom.xml` 中确有必要的部分；不得修改 `AGENTS.md`、`config.toml`、`agents/*.toml`、`docs/plan.md`、`docs/api-contract.md`。
- 契约变更（字段、枚举、错误码、状态）必须先回到 planner 或由 root 裁决；实现不得“顺手改契约”。
- 不新增外部依赖、不访问外网、不需要真实 `OPENAI_API_KEY`；LLM 模式测试使用本地假 `ChatModel`。
- 保持 H2 `MODE=PostgreSQL` 兼容；迁移必须同时给出可执行的回滚方案。
- 保持现有 6 个集成测试通过；新增行为必须有对应测试。
- 不执行 `git commit`、`git checkout`、`git rebase`、`git push`，不创建或删除分支。

### 验收标准

以下条目全部为二值判定，全部通过才算完成：

- [ ] **退款累计上限**：同一订单在不同会话中连续退款时，累计退款金额不得超过订单可退余额；第二次超额请求被拒绝且不新增 `refund_request` 行。
- [ ] **退款并发安全**：两个并发退款请求不能同时通过余额校验；订单行使用行锁或等价的原子条件更新，余额不会变成负数。
- [ ] **人工工单唯一**：同一会话在并发或重复触发下最多只有一个 `category='HANDOFF'` 的未关闭工单；重复请求返回同一 `ticketId` 且 `reused=true`。
- [ ] **状态机收口**：`WAITING_HUMAN`、`COMPLETED`、`FAILED` 会话再次调用 `/api/chat` 时不执行任何业务工具，返回契约规定的冲突错误；`WAITING_FOR_INPUT` 仍可继续收集槽位。
- [ ] **幂等并发**：同一用户、同一 `Idempotency-Key`、同一请求哈希的并发请求只产生一次业务副作用，并返回同一响应体；不同请求哈希仍返回 `IDEMPOTENCY_CONFLICT`。
- [ ] **结果校验落地**：`ResultValidator` 至少核对订单号、订单状态、退款单号/金额、工单号与工具事实一致；构造事实不一致的测试必须校验失败并触发兜底或补偿。
- [ ] **退款副作用补偿**：退款工具已成功但后续校验失败时，不得静默返回“失败”并允许用户再次退款；必须保留可追踪的退款记录，并进入人工复核或明确的补偿状态。
- [ ] **待办动作保持**：退款/工单槽位收集中收到无法识别的内容时，保留原 `pendingAction` 并重问同一问题；连续两次无法识别才转人工。
- [ ] **评测隔离**：`POST /api/admin/evaluations/run` 重复运行不会改变真实订单可退余额，不会为真实用户新增未关闭人工工单；评测数据使用隔离命名空间、事务回滚或专用测试数据。
- [ ] **LLM 边界**：规则置信度足够时不调用 LLM；LLM 异常、超时或非法 JSON 时回退规则结果；LLM 只输出意图和槽位，不能直接决定工具执行。
- [ ] **数据库约束**：为工单唯一性、状态枚举和退款余额不变量补充可执行迁移；迁移在干净 H2 数据库上可重复执行，回滚脚本可执行。
- [ ] **错误映射**：非法的查询参数类型、未知路径和不支持的方法返回 4xx，不落入通用 500；错误响应仍包含 `code`、`message`、`traceId`、`timestamp`。
- [ ] **回归**：原有 6 个集成测试全部通过；新增测试覆盖上述失败场景；`mvn -q test` 退出码为 0。
- [ ] **范围纪律**：`git diff --check` 无空白错误；变更文件清单与任务卡一致；未修改契约文档、治理配置和无关依赖。

### 证据要求

最终答复必须包含以下内容，不能只写“已完成”：

1. 变更清单：`文件:行`，按退款、工单、状态机、幂等、校验、评测、LLM 分组；
2. 命令与关键输出：
   - `mvn -q test`（完整测试结果，至少贴 surefire 汇总行）；
   - `mvn -q -o -DskipTests package`（退出码）；
   - 需要时贴 `mvn -q -Dtest=<类名>#<方法名> test` 的定向结果；
   - `git diff --check` 与 `git status --short`；
3. 新增/修改迁移的升级与回滚命令，以及实际执行结果；
4. 对每个验收条目给出“通过/不通过 + 对应测试或命令”的映射；
5. 未验证项必须原样列出，包括失败命令、退出码和原始错误。

### 禁止项

- 不得修改 `AGENTS.md`、`config.toml`、`agents/*.toml`、`docs/plan.md`、`docs/api-contract.md`；
- 不得通过放宽测试或删除断言来“修复”失败；
- 不得引入新的外部服务、真实支付通道或需要联网的测试；
- 不得把 `X-User-Id` 演示身份包装成生产级认证；
- 不得做与验收标准无关的重构、整文件格式化或依赖升级；
- 不得执行 Git 共享状态操作或删除/覆盖用户数据。

### 回报格式

结论（通过/部分通过/阻塞）→ 变更清单（文件:行）→ 验收条目逐条证据 → 未完成/未验证项（含原始错误）→ 需要 planner/root 裁决的契约问题。

## 6. 任务卡 R1：独立只读评审

### 目标

由与 B1 作者不同的 agent，在不修改任何文件的前提下，独立判断 B1 是否满足 `docs/plan.md` 的验收标准和 `docs/api-contract.md` 的契约，并给出可复现的问题清单。

### 输入

- `/root/projects/demo/docs/plan.md`
- `/root/projects/demo/docs/api-contract.md`
- B1 的变更清单、测试报告和证据（由 root 在派发时提供确切路径）
- `/root/projects/demo/src/main/**`、`/root/projects/demo/src/test/**`、`/root/projects/demo/src/main/resources/db/**`
- `/root/projects/demo/pom.xml`、`/root/projects/demo/config.toml`

### 约束

- 只读沙箱；不得修改、格式化、暂存、提交或删除任何文件；
- 不得重写作者结论，不得代替作者修代码；
- 必须独立复跑关键命令，不能只看作者截图；
- 发现的问题必须给出 `文件:行`、复现命令/输出、影响和最小修复建议；
- 结论只能是“通过”或“不通过”；“通过”也必须列出未覆盖范围。

### 验收标准

- [ ] 逐条核对 `docs/plan.md` §5 的全部验收标准，给出通过/不通过；
- [ ] 核对 `docs/api-contract.md` 的端点、字段、枚举、错误码、权限矩阵、状态机、幂等语义是否有实现与测试支撑；
- [ ] 独立复跑 `mvn -q test` 和至少一个定向测试，贴出原始汇总；
- [ ] 对退款累计上限、并发退款、并发幂等、人工工单唯一性、状态机阻断分别给出代码级证据；
- [ ] 对 LLM 模式给出“使用假模型/未联网”的证据，确认测试没有依赖真实 API；
- [ ] 列出未覆盖范围、残余风险和建议的下一步；
- [ ] 若判定不通过，按 `blocker` / `major` / `minor` 分级，并给出最小修复范围；
- [ ] 不修改任何文件，最终答复自包含。

### 证据要求

- 复跑命令、退出码、关键输出片段；
- 每个问题的 `文件:行` 与代码摘录；
- 测试报告路径和汇总行；
- 对“未验证”项说明原因和风险，不得写成“应该没问题”。

### 禁止项

- 不得修改任何文件（reviewer 的沙箱为只读）；
- 不得评审自己写的改动；
- 不得用“看起来合理”“逻辑上没问题”代替可复现证据；
- 不得把未覆盖范围包装成通过结论；
- 不得执行 Git 写操作或调用外部服务。

### 回报格式

结论（通过/不通过）→ 阻塞项（blocker/major，含文件:行与证据）→ 其他发现（minor）→ 未覆盖范围与残余风险 → 建议的下一步。最终答复必须自包含。

## 7. 并行与串行分组

| 阶段 | 可并行性 | 文件所有权与说明 |
| --- | --- | --- |
| P0 规划与契约 | 已完成 | planner 只写 `docs/plan.md`、`docs/api-contract.md`；不改业务代码 |
| B1 后端修复 | 不可拆分并行 | 退款、状态机、幂等、校验都会触碰 `CustomerServiceOrchestrator.java`；为遵守“同一文件唯一写者”，必须由一个 backend agent 串行完成 |
| R1 独立评审 | 必须串行在 B1 之后 | reviewer 只读；作者不能自评；评审期间 B1 不得继续改文件 |
| Root 治理收口 | 可与 B1 并行，但文件不同 | root 只改 `AGENTS.md`、`README` 等治理文件；不得改 `src/**` 或 planner 的两份文档；验证命令必须在 B1 证据产生后写入 |

如果未来要并行拆分 backend，最小安全切法是：

1. 先由一名 backend 完成 `CustomerServiceOrchestrator` 的状态机接口和共享 record；
2. 再按 `ToolExecutionService + RefundRepository`、`HandoffService + TicketRepository`、`ChatRequestRepository + ResultValidator` 分成互不重叠的文件组；
3. 每组必须有独立验收标准，且不得同时修改同一文件。

本轮不建议这样做，因为当前代码的耦合点集中，拆分会增加合并冲突和契约漂移风险。

## 8. 可证据化的高风险缺口

下列问题均由当前源码和测试直接证明；“影响”按演示/生产风险分别描述。行号以本文件生成时的源码为准。

| 编号 | 级别 | 证据（文件:行） | 原因 | 影响 |
| --- | --- | --- | --- | --- |
| G1 | Critical | `src/main/java/com/example/aicsupport/tool/ToolExecutionService.java:159`、`:164`、`:175`；`src/main/java/com/example/aicsupport/repository/OrderRepository.java:18`；`src/main/resources/db/migration/V1__schema.sql:14` | 只把单笔金额与 `refundable_amount` 比较，退款后既不扣减余额，也没有订单级唯一约束；去重只按 `conversation_id + order_id` | 用户新建会话即可再次退同一订单，造成超退/资金损失；评测重复运行也会放大该问题 |
| G2 | High | `src/main/java/com/example/aicsupport/tool/ToolExecutionService.java:204`、`:212`；`src/main/resources/db/migration/V1__schema.sql:116` | `findHandoffByConversation` 后再 `insert`，没有唯一约束或锁；`idx_ticket_conversation` 不是唯一索引 | 并发转人工或连续未知兜底可能生成多个工单，分配多个客服并产生重复审计 |
| G3 | High | `src/main/java/com/example/aicsupport/orchestration/CustomerServiceOrchestrator.java:161`、`:191`、`:229`、`:240`；`src/main/java/com/example/aicsupport/service/HandoffService.java:74` | `openConversation` 不检查 `WAITING_HUMAN` / `COMPLETED` / `FAILED`；订单、工单、取消、通用分支会把会话重新置为 `ACTIVE` | 人工处理中的用户可继续让 AI 查单/退款，绕过人工；工单仍为 `OPEN` 时会话已变成 `ACTIVE`，状态不一致 |
| G4 | High | `src/main/java/com/example/aicsupport/validation/ResultValidator.java:14`、`:19`、`:35`、`:46`、`:57`、`:68` | 校验只看回复非空、是否含字符串 `"null"`、工具结果字段是否存在；没有核对回复中的订单号、状态、金额、退款单号、工单号是否等于工具事实 | 错误或幻觉回复可能通过校验；`"null"` 还会误伤合法文本；校验失败后的兜底语义不明确 |
| G5 | High | `src/main/java/com/example/aicsupport/evaluation/EvaluationService.java:38`、`:117`、`:123`；`src/main/java/com/example/aicsupport/orchestration/CustomerServiceOrchestrator.java:125`、`:157` | 评测直接调用真实 `chat()`，会创建会话、退款和人工工单；`run()` 没有事务边界或隔离数据 | 管理员每跑一次评测就污染业务数据；结合 G1 会重复生成退款，导致演示数据和资金状态失真 |
| G6 | High | `src/main/java/com/example/aicsupport/intent/LlmIntentClassifier.java:54`、`:67`、`:72`；`src/main/java/com/example/aicsupport/plan/TaskPlanner.java:16`；`src/main/java/com/example/aicsupport/intent/HybridIntentClassifier.java:26` | LLM 返回的 `orderId`、`ticketId`、`refundAmount` 直接进入槽位；只做 JSON 解析，没有字段长度、金额精度、枚举白名单校验；`requiresHuman` 在非人工意图下未被消费 | 提示注入或模型漂移可能注入不存在的订单号/金额；工具层虽有权限和数据库校验，但错误会以“查不到/参数不完整”形式出现，审计难以区分模型错误与用户输入 |
| G7 | Medium | `src/main/java/com/example/aicsupport/intent/RuleBasedIntentClassifier.java:79`、`:88`、`:95`；`src/main/java/com/example/aicsupport/plan/TaskPlanner.java:91`、`:98`；`src/main/java/com/example/aicsupport/orchestration/CustomerServiceOrchestrator.java:206` | 处于 `WAITING_FOR_INPUT` 时，无法识别的回复会走到 `UNKNOWN`，`clarify(..., null, ...)` 把 `pendingAction` 清空 | 退款/工单收集流程被一句“嗯？”打断，用户必须重新开始；同时未知计数可能在没有上下文时触发人工兜底 |
| G8 | Medium | `src/main/java/com/example/aicsupport/orchestration/CustomerServiceOrchestrator.java:104`、`:157`、`:545`、`:560`；`src/main/java/com/example/aicsupport/repository/ChatRequestRepository.java:45`；`src/main/resources/db/migration/V1__schema.sql:111`；`src/main/java/com/example/aicsupport/common/GlobalExceptionHandler.java:50` | 先 `replay` 查询再 `insert`，唯一键冲突由通用 `DuplicateKeyException` 处理；并发同键请求可能一个成功、一个返回 `CONFLICT` | 客户端重试同一请求时可能收到 409 而不是同一响应；对“至少一次”调用方不友好，也增加重复业务操作风险 |
| G9 | Medium | `src/main/java/com/example/aicsupport/orchestration/CustomerServiceOrchestrator.java:125`、`:316`、`:337`；`src/main/java/com/example/aicsupport/validation/ResultValidator.java:57`；`src/main/java/com/example/aicsupport/orchestration/CustomerServiceOrchestrator.java:451` | `REFUND_CREATE` 已成功写库后才做校验；校验失败只转人工，没有把退款标记为待复核/补偿，也没有阻止再次退款 | 用户看到“转人工”但退款已存在；再次发起会重复退款（G1 放大），人工需要手工对账 |
| G10 | Medium | `src/main/java/com/example/aicsupport/access/AccessControlService.java:36`；`src/main/java/com/example/aicsupport/api/ChatController.java:33`；`src/main/java/com/example/aicsupport/api/AuditController.java:33` | 身份完全来自可伪造的 `X-User-Id` 请求头，没有签名、令牌或网关鉴权 | 任何能访问服务的人都可以冒充 `cust-alice` 或 `admin` 读审计、跑评测、发起退款；演示可用，生产不可接受 |
| G11 | Medium | `src/main/java/com/example/aicsupport/service/HandoffService.java:51`、`:59`、`:74`、`:83`；`src/main/java/com/example/aicsupport/api/HandoffController.java:45` | 人工回复没有幂等键，也不校验工单是否已 `RESOLVED`；重复 POST 会重复插入 `HUMAN_AGENT` 消息并重复递增消息数 | 客服网络重试会产生重复回复；已解决工单可被再次回复并改回 `PROCESSING`，状态机不自洽 |
| G12 | Medium | `src/main/resources/db/migration/V1__schema.sql:27`、`:30`、`:45`、`:56`；`src/main/java/com/example/aicsupport/domain/Intent.java:3`、`ConversationStatus.java:3` | `support_ticket.status/priority/category`、`refund_request.status`、`conversation.status` 都是普通 `VARCHAR`，没有 `CHECK` 约束；应用层用字符串写入 | 手工 SQL、旧数据或未来代码可以写入非法状态，查询和状态机分支会静默失效；数据库无法作为最后一道契约防线 |
| G13 | Medium | `src/main/java/com/example/aicsupport/repository/ConversationRepository.java:94`；`src/main/java/com/example/aicsupport/orchestration/CustomerServiceOrchestrator.java:115`、`:145`；`src/main/java/com/example/aicsupport/tool/ToolExecutionService.java:266` | 退款原因在 `tool_invocation.input_json` 中被哈希，但用户原始消息和助手回复仍以明文写入 `conversation_message`；没有加密、脱敏展示或保留期限 | 退款原因等敏感信息长期留在数据库，审计导出/备份会扩散；与“权限审计”目标不匹配 |
| G14 | Medium | `src/main/java/com/example/aicsupport/plan/TaskPlanner.java:20`、`:45`、`:57`、`:62`、`:91`；`src/main/java/com/example/aicsupport/orchestration/CustomerServiceOrchestrator.java:191` | `plan.reason()` 是自由字符串，`execute` 用它做 `switch`；新增/拼错 reason 会静默落入 `default -> general()` | 计划与执行可能悄悄不一致，工具不执行但接口返回 200；测试只能覆盖已知分支 |
| G15 | Low | `src/main/java/com/example/aicsupport/api/AuditController.java:35`；`src/main/java/com/example/aicsupport/common/GlobalExceptionHandler.java:58` | `limit` 是非数值时抛 `MethodArgumentTypeMismatchException`，没有专门处理；未知路径/方法也落入通用 `Exception` | 客户端得到 500 而不是 400/404，违背 HTTP 语义，增加排障噪音 |
| G16 | Low | `src/test/java/com/example/aicsupport/ChatApiIntegrationTest.java:37`、`:77`、`:106`、`:135`、`:146` | 现有测试覆盖主路径，但没有并发退款、跨会话重复退款、`WAITING_HUMAN` 后继续聊天、重复人工回复、LLM 假模型、非法查询参数等场景 | 上述高风险缺口在回归中不可见；修复后必须有定向测试，而不能只依赖手动验证 |

### 8.1 其他低风险观察（不作为 B1 阻塞）

| 编号 | 证据（文件:行） | 观察 |
| --- | --- | --- |
| L1 | `src/main/java/com/example/aicsupport/evaluation/EvaluationService.java:141`、`:144` | 异常分支把 `exception.getMessage()` 放进 `conversationId` 字段，`detail` 反而为空；报告字段语义不一致 |
| L2 | `src/main/java/com/example/aicsupport/orchestration/CustomerServiceOrchestrator.java:107`、`:545`；`src/main/java/com/example/aicsupport/config/RequestTraceFilter.java:31` | 幂等重放返回首次请求体中的旧 `traceId`，而响应头是当前请求的 `X-Trace-Id`；契约已写明，排障时需同时看两处 |
| L3 | `src/main/java/com/example/aicsupport/repository/TicketRepository.java:57` | 人工工单列表没有分页；数据增长后会一次返回全部记录 |
| L4 | `src/main/java/com/example/aicsupport/access/AccessControlService.java:73` | 客服对订单的访问依据“存在任一关联工单”，工单解决后仍保留访问；是否符合最小权限需业务确认 |
| L5 | `src/main/java/com/example/aicsupport/repository/UserRepository.java:48` | 客服分配使用“当前工单最少”查询，但没有锁；并发转人工可能选中同一客服，属于近似负载均衡 |

## 9. 风险与假设

| 假设 | 默认值 | 不成立时的后果 |
| --- | --- | --- |
| 交付形态是 REST API + 演示数据，不含前端 | 维持现状，前端另立任务 | 若用户要求可交互页面，当前计划的验收范围不覆盖，需要新增 frontend 任务卡和接口调用层 |
| 身份认证在演示中由调用方提供 `X-User-Id` | 保留，但在契约中明确“非生产” | 若要求生产级安全，必须引入真实认证/网关，G10 会从“已知限制”升级为发布阻塞 |
| 退款按订单累计上限控制，而不是按会话 | 同一订单的累计退款不得超过 `refundable_amount` | 若业务允许按会话独立退款，G1 的修复方向需改为“会话额度 + 订单总额双重约束”，否则会误拒合法请求 |
| 人工处理期间 AI 不应继续执行工具 | `WAITING_HUMAN` 时 `/api/chat` 返回冲突，必须由客服处理或用户新建会话 | 若业务希望 AI 可继续查询，必须定义只读白名单，仍禁止退款/转人工等写操作 |
| 默认使用离线规则模式，LLM 为可选增强 | `AI_LLM_ENABLED=false`，测试不联网 | 若要求默认 LLM，需提供 API Key、网络、超时和成本预算，并增加真实模型评测；当前测试不覆盖该路径 |
| 评测可以污染演示数据 | 本轮要求改为隔离；若暂不隔离，至少要在报告中显式标注“会写业务数据” | 若不改，管理员重复评测会导致退款/工单数据膨胀，不能作为回归依据 |
| H2 内存库足以验证迁移 | 迁移保持 PostgreSQL 兼容语法；生产数据库另行验证 | 若直接上 PostgreSQL，需额外验证锁、唯一索引和 `FOR UPDATE` 行为；当前未做真实 PG 测试 |

## 10. 需要用户确认的问题（含推荐默认值）

以下问题不阻塞本轮文档，但会影响 backend 修复的具体取舍。root 应在派发 B1 前确认；若用户未反对，按推荐默认值执行。

1. **退款额度按什么维度控制？** 推荐：按订单累计，退款成功后原子扣减 `refundable_amount`，同一订单所有会话共享额度。
2. **`WAITING_HUMAN` 期间是否允许 AI 继续服务？** 推荐：只允许客服处理；用户继续发消息返回冲突，避免绕过人工。
3. **人工回复是否需要幂等键？** 推荐：需要，作用域为 `(ticketId, actorId, Idempotency-Key)`，重复提交返回同一结果。
4. **评测是否允许写真实业务数据？** 推荐：默认隔离；若保留写操作，必须显式标注并在报告中列出受影响资源。
5. **是否要求生产级身份认证？** 推荐：本轮保持 `X-User-Id` 演示方案并在 README/契约中标注；生产化另立任务。
6. **是否需要前端？** 推荐：本轮不做；如果用户需要，新增 frontend 任务卡，接口以本契约为准。

## 11. 未覆盖与未验证

- 真实端口 HTTP 冒烟未完成：沙箱禁止监听端口，启动失败为 `java.net.SocketException: Operation not permitted`；MockMvc 集成测试覆盖了控制器层。
- 真实 LLM 服务未调用：没有 API Key，且当前环境网络受限；只计划用假 `ChatModel` 验证回退和 JSON 边界。
- PostgreSQL/生产数据库未验证：当前测试使用 H2 `MODE=PostgreSQL`；行锁、唯一索引和迁移需在目标数据库复验。
- 性能、并发压测、长会话内存增长未验证；本轮只做正确性并发测试。
- 前端、浏览器可访问性、CORS 未覆盖。
- 现有 `pom.xml` 和 `src/**` 仍是未跟踪文件；提交动作由 root 在评审通过后执行，planner 不执行 Git 写操作。
