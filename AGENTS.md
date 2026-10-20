# AGENTS.md — 多 Agent 协同工作规范

## 0. 适用范围与优先级

本文件对在**本目录（仓库根）**启动的所有 Codex 会话生效，对编排者（root）和每一个子 agent 同等有效。

优先级从高到低：

1. 用户当轮的指令；
2. 本文件（仓库级协作契约）；
3. `agents/<role>.toml` 中该角色的 `developer_instructions`（角色细节）；
4. agent 的默认习惯与个人偏好。

出现冲突时，**取更严格的一条**，并在最终答复中写明取舍与理由。

子 agent 的最终答复会整体交回父 agent，因此每个子 agent 必须假设"父 agent 只看到你这一条消息"：把结论、证据、未完成项都写在最终答复里，不要藏在思考过程中。

## 1. 项目现状

本仓库现已包含可运行的 **Spring Boot 3.5.16 + LangChain4j 1.22.0 AI 客服**：

- 后端源码在 `src/main/java`，前端静态资源在 `src/main/resources/static`；
- H2 内存库与 Flyway 迁移在 `src/main/resources/db`；
- 自动化测试在 `src/test`；
- 规划、接口契约与验证记录在 `docs/`，使用说明在 `README.md`。

当前默认使用离线规则模式，外部 LLM 为可选增强。验证结论必须来自真实命令输出；未运行的检查一律写“未验证”。

## 2. 角色与权限（唯一权威表）

| 角色 | 何时使用 | 沙箱 | 审批 | 主要产物 | 明确不做 |
| --- | --- | --- | --- | --- | --- |
| `planner` | 需求模糊、跨模块、有依赖，或需要先定接口 | `workspace-write` | `on-request` | 任务卡、接口契约、验收标准、风险与假设、并行分组 | 不写业务代码；不替 reviewer 下结论 |
| `backend` | 服务端、数据模型、持久化、对外接口 | `workspace-write` | `on-request` | 实现 + 迁移/回滚方案 + 测试 + 运行证据 | 不擅自改契约；不动前端目录 |
| `frontend` | UI、交互、状态管理、接口调用层 | `workspace-write` | `on-request` | 实现 + 三态处理 + 可访问性 + 构建/测试证据 | 不擅自改契约；不动后端与数据库 |
| `reviewer` | 任何要合入的改动，且必须与作者**不是同一个 agent** | `read-only` | `on-request` | 分级问题清单（文件:行 + 证据）、未覆盖范围 | **不修改任何文件**；不重写别人的结论 |

权限不是建议，而是硬边界：`reviewer` 的沙箱是只读的，它**无法**改代码。"发现问题顺手修掉"必须交回 root 或原作者执行。

## 3. 标准工作流

```
用户需求
   │
   ├─(1) planner ──► 任务卡 + 接口契约 + 验收标准
   │                      │
   │                      ├─(2) backend  ─┐
   │                      └─(2) frontend ─┤ 并行实现（各写各的目录）
   │                                      │
   ├─(3) reviewer ◄───────────────────────┘ 独立评审（只读）
   │        │
   │        └─ 不通过 ──► 回到原作者的最小修复循环（≤ 2 轮）
   │
   └─(4) root 收口：核对验收标准 + 汇总证据 + 交付或升级
```

1. **规划（planner）**：输出任务卡（§4）与接口契约。契约没定下来之前，不许开工写实现。
2. **并行实现（backend / frontend）**：只在被分配的任务卡范围内改动，遵守 §5 的文件所有权。
3. **独立评审（reviewer）**：作者不能评审自己的改动。结论只有"通过 / 不通过"；不通过必须给出阻塞项与最小修复建议。
4. **收口（root）**：逐条核对验收标准，确认证据齐备，再决定交付、回退还是升级给用户。

同一轮里**同一个文件只能有一个写者**；并行必须落在互不重叠的目录或文件上。

## 4. 任务卡契约（派发给子 agent 的最小信息集）

缺少任意一个必填字段的任务卡，子 agent 应当**退回并要求补全**，而不是自行补假设。

```
目标：一句话说明要达成什么（可验证的结果，不是动作）
输入：需要读的文件/契约/上一轮结论（给出确切路径）
约束：不能碰的文件、必须沿用的接口、性能或兼容性要求
验收标准：逐条可判定的 checklist（二值：通过/不通过）
证据要求：需要贴出的命令与关键输出
禁止项：本任务明确不允许做的事
回报格式：结论 → 变更清单（文件:行）→ 证据 → 未完成/未验证项
```

## 5. 文件所有权与冲突规避

- **写者唯一**：同一时刻一个文件只能有一个 agent 在写；root 派活时就必须说清文件归属。
- **目录所有权（默认）**：`planner` → 计划与契约文档；`backend` → 服务端与数据相关目录；`frontend` → 前端相关目录；`reviewer` → 只读；`root` → 仓库根配置文件（含本文件）。
- **契约变更**：接口、数据结构、错误码的变更必须先回到 `planner`（或由 root 裁决）更新契约，再改实现。禁止"实现顺手改契约"。
- **不做无关改动**：不重构任务卡之外的代码，不整文件格式化，不改与任务无关的依赖版本。
- **不做共享状态操作**：子 agent 不执行 `git commit`、`git checkout`、`git rebase`、`git push`，不创建或删除分支；需要时由 root 统一执行，或先向用户确认。

## 6. 证据规则（何时算"完成"）

一条结论只有在**能被别人复现**时才成立：

1. 每条断言后面跟证据：**完整命令 + 关键输出片段 + 影响范围**；
2. 禁止"应该可以""大概没问题""逻辑上正确"这类无证据表述；
3. 失败必须**原样回报**（命令、退出码、原始报错），不得静默重试，不得只贴结论不贴输出；
4. 没跑过的验证显式写成"未验证"，并说明原因与风险；
5. 涉及行为的改动必须有对应测试；确实无法测时，要说明为什么，以及如何手工验证。

## 7. 验证命令

| 用途 | 命令 | 通过判据 | 状态 |
| --- | --- | --- | --- |
| 安装依赖 | `mvn -q -DskipTests dependency:go-offline` | 退出码 0 | 已定义（首次构建需要网络） |
| 后端编译 | `mvn -q -DskipTests compile` | 退出码 0 | 已验证 |
| 前端静态检查 | `node --check src/main/resources/static/app.js` | 退出码 0 | 已验证 |
| 单元/集成测试 | `mvn -q test` | 全绿；当前 24 个测试、0 失败 | 已验证 |
| 可执行包 | `mvn -q package` | 退出码 0，生成 `target/ai-customer-service-0.1.0-SNAPSHOT.jar` | 已验证 |
| HTTP 冒烟 | 启动 `java -jar target/ai-customer-service-0.1.0-SNAPSHOT.jar --server.port=18080`，再执行 `curl -fsS http://127.0.0.1:18080/` 与 `POST /api/chat` | 首页 200；聊天返回 `validation.passed=true` | 已验证 |
| 协同配置自检 | `CODEX_HOME=<本目录> codex exec --skip-git-repo-check "hi"`，检查输出中**没有** `malformed agent role` 警告 | 无该警告 | 已定义 |

跨进程验证 `POST /api/admin/evaluations/run` 后，应再次查单确认可退金额未被评测污染。最后一条用于验证 `agents/*.toml` 能被 Codex 正确解析，详见 §11。

## 8. 并发与生命周期

- 同时活跃的 agent 上限为 4（**含 root**），即最多 3 个子 agent 并行；超出部分排队，不要靠"多开几个"提速。
- 并行度由 `[agents].max_concurrent_threads_per_session` 控制（本仓库约定为 4）。
- 子 agent 交付后必须由 root 显式关闭；已完成的 agent 仍占用并发额度，不要长期挂着。
- 派发时使用稳定任务名（小写字母、数字、下划线，例如 `be_task_1`、`fe_task_1`），便于按名字回派后续任务。
- 拆分粒度以"能独立验收"为准：一张任务卡对应一组可判定的验收标准。

## 9. 必须停下来问用户的场景

出现以下情况时**停下并向用户确认**，不要自行决策：

1. 需求存在会改变交付形态的歧义（例如"是否兼容旧数据"直接决定表结构）；
2. 需要引入新的外部依赖、外部服务、凭证或网络访问；
3. 需要删除或覆盖数据，或执行不可逆操作（`rm`、迁移回滚、强推）；
4. 需要超出已授权范围的写操作（本目录之外的文件、系统配置、CI 或仓库设置）；
5. 同一条阻塞条件连续两轮无进展（第三轮必须升级，而不是继续重试）。

提问时给出：**已确认的事实 → 卡住的具体点 → 2~3 个可行选项及各自代价 → 你的推荐**。

## 10. 反模式清单（出现即视为不合格交付）

- 没有任务卡就开工，或自行扩大任务范围；
- 两个 agent 同时改同一个文件，或跨目录"顺手"重构；
- 结论没有命令与输出作为证据，或用截图代替可复现的命令；
- reviewer 直接改代码，或作者自评"已完成"；
- 契约与实现不一致却继续往下做；
- 把"未验证"包装成"已验证"；
- 静默重试直到看起来成功；
- 长期占用并发额度的僵尸 agent。

## 11. 角色文件如何被 Codex 加载（已在本机 Codex 0.162.1 上实测）

改写这些约定前，请先按下面给出的命令复现验证。

1. 角色定义文件（agent role file）会被自动发现于 **`$CODEX_HOME/agents/*.toml`**。
   - 实测**不会**被发现的位置：仓库根的 `agents/`、`<repo>/.codex/agents/`、`$CODEX_HOME/.agents/`、`$CODEX_HOME/.codex/agents/`。
   - 结论：本目录只有作为 `CODEX_HOME` 使用时，`agents/*.toml` 才自动生效；否则必须按第 4 条显式注册。
2. 自动发现的角色文件**必须**提供 `name`（非空）、`description`、`developer_instructions`（非空）；`nickname_candidates` 可选。
3. 角色文件的结构是**严格的**：出现未知字段会导致反序列化失败（`reasoning_effort`、`config_file`、`nickname`、`title` 都会被拒绝），该角色被忽略并打印 `Ignoring malformed agent role definition` 警告。可用字段与 `config.toml` 的 profile 字段一致，常用的有：

   `name`、`description`、`nickname_candidates`、`developer_instructions`、
   `model`、`model_provider`、`model_reasoning_effort`、`model_reasoning_summary`、
   `model_verbosity`、`model_context_window`、`service_tier`、`personality`、
   `sandbox_mode`、`approval_policy`、`approvals_reviewer`、`instructions`、`tools`。

   取值约束（实测）：
   - `sandbox_mode`：`read-only` | `workspace-write` | `danger-full-access`
   - `approval_policy`：`untrusted` | `on-failure` | `on-request` | `granular` | `never`
   - `nickname_candidates`：至少一项，不得为空串，不得重复，仅允许 ASCII 字母、数字、空格、`-`、`_`
   - `model_reasoning_effort`：不做取值校验（由模型目录映射）
4. 也可以在 `config.toml` 里显式注册，适合"本目录不是 `CODEX_HOME`"的场景：

   ```toml
   [agents]
   max_concurrent_threads_per_session = 4
   max_depth = 2                        # 仅 V1 生效，V2 忽略
   default_subagent_reasoning_effort = "high"
   interrupt_message = true             # 布尔值
   # default_subagent_model = "<模型名>"  # 省略则子 agent 继承当前模型

   [agents.planner]
   description = "需求澄清、方案设计与任务拆解"
   config_file = "agents/planner.toml"  # 相对路径以 config.toml 所在目录为基准
   nickname_candidates = ["Planner", "Architect"]
   ```

   - `agents.<name>.description` 必填；`config_file` 指向不存在的文件会直接报错并拒绝启动。
   - `[agents]` 下**除上述 6 个键之外的名字都会被当作"角色名"**，所以不要在这里随手加自定义配置项。
   - 同一角色既被自动发现又在 `config.toml` 注册（名字相同）不会报错；但**两个文件里出现同名角色**会报 `duplicate agent role name ... discovered in ...`。
5. 角色文件本身不携带"谁能派活给谁"的规则；派发权限来自用户指令与本文件。本文件在 §3 显式授权了按角色的并行委派，委派因此才被允许。

## 12. 维护本文件

- "团队约定"级别的变化（新的验证命令、新的目录归属、权限调整）落到本文件，不要散落在 prompt 里；
- 只属于当前一次任务或 PR 的约束写在当轮指令里，不要写进本文件；
- 修改 §11 的配置事实时，必须附上复现命令与本机 Codex 版本号。
