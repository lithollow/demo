(() => {
  "use strict";

  const actorSelect = document.getElementById("actor-select");
  const apiStatus = document.getElementById("api-status");
  const chatView = document.getElementById("chat-view");
  const handoffsView = document.getElementById("handoffs-view");
  const auditView = document.getElementById("audit-view");
  const navTabs = Array.from(document.querySelectorAll(".nav-tab"));
  const messageList = document.getElementById("message-list");
  const chatForm = document.getElementById("chat-form");
  const messageInput = document.getElementById("message-input");
  const sendButton = document.getElementById("send-button");
  const newConversationButton = document.getElementById("new-conversation");
  const chatAlert = document.getElementById("chat-alert");
  const handoffAlert = document.getElementById("handoff-alert");
  const handoffList = document.getElementById("handoff-list");
  const auditAlert = document.getElementById("audit-alert");
  const auditList = document.getElementById("audit-list");
  const auditForm = document.getElementById("audit-form");
  const auditResource = document.getElementById("audit-resource");
  const evaluationReport = document.getElementById("evaluation-report");
  const runEvaluationButton = document.getElementById("run-evaluation");
  const refreshHandoffsButton = document.getElementById("refresh-handoffs");
  const validationBlock = document.getElementById("validation-block");
  const validationCopy = document.getElementById("validation-copy");
  const planList = document.getElementById("plan-list");
  const toolList = document.getElementById("tool-list");

  const state = {
    actor: actorSelect.value,
    conversationId: localStorage.getItem(conversationKey(actorSelect.value)),
    conversationStatus: null,
    busy: false,
    requestGeneration: 0,
    abortController: null,
    activeView: "chat"
  };

  function conversationKey(actor) {
    return `aster.conversation.${actor}`;
  }

  function actorLabel() {
    return actorSelect.options[actorSelect.selectedIndex].textContent.split("·")[0].trim();
  }

  function actorRole() {
    const text = actorSelect.options[actorSelect.selectedIndex].textContent;
    if (text.includes("管理员")) return "ADMIN";
    if (text.includes("客服")) return "AGENT";
    return "CUSTOMER";
  }

  function idempotencyKey() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
      return window.crypto.randomUUID();
    }
    return `${Date.now()}.${Math.random().toString(36).slice(2)}`;
  }

  function setApiStatus(text, mode = "") {
    apiStatus.textContent = text;
    apiStatus.className = `status-pill${mode ? ` ${mode}` : ""}`;
  }

  function setBusy(busy) {
    state.busy = busy;
    setApiStatus(busy ? "处理中…" : "已连接", busy ? "busy" : "");
    updateComposerAvailability();
  }

  function showAlert(element, message, type = "") {
    if (!message) {
      element.textContent = "";
      element.className = "inline-alert hidden";
      return;
    }
    element.textContent = message;
    element.className = `inline-alert${type ? ` ${type}` : ""}`;
  }

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("X-User-Id", state.actor);
    if (options.body && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }
    let response;
    try {
      response = await fetch(path, { ...options, headers });
    } catch (error) {
      throw new Error("网络连接失败，请确认后端服务已启动。");
    }
    const text = await response.text();
    let payload = null;
    if (text) {
      try {
        payload = JSON.parse(text);
      } catch (error) {
        payload = null;
      }
    }
    if (!response.ok) {
      const requestError = new Error(payload?.message || `请求失败（HTTP ${response.status}）`);
      requestError.status = response.status;
      requestError.code = payload?.code || "UNKNOWN";
      requestError.traceId = payload?.traceId;
      throw requestError;
    }
    return payload;
  }

  function clearElement(element) {
    element.replaceChildren();
  }

  function appendText(parent, tag, text, className = "") {
    const node = document.createElement(tag);
    if (className) node.className = className;
    node.textContent = text;
    parent.appendChild(node);
    return node;
  }

  function appendEmptyState(title, copy, compact = false) {
    clearElement(messageList);
    const box = document.createElement("div");
    box.className = `empty-state${compact ? " compact" : ""}`;
    appendText(box, "span", "↗", "empty-symbol").setAttribute("aria-hidden", "true");
    appendText(box, "h3", title);
    appendText(box, "p", copy);
    messageList.appendChild(box);
  }

  function appendMessage(role, content, createdAt = null) {
    const existingEmpty = messageList.querySelector(".empty-state");
    if (existingEmpty) existingEmpty.remove();
    const article = document.createElement("article");
    article.className = `message ${role}`;
    const labels = { user: "你", assistant: "AI 客服", system: "系统提示", human_agent: "人工客服" };
    appendText(article, "p", labels[role] || role, "message-meta");
    appendText(article, "div", content, "bubble");
    if (createdAt) {
      const time = appendText(article, "time", formatTime(createdAt), "message-meta");
      time.dateTime = createdAt;
    }
    messageList.appendChild(article);
    messageList.scrollTop = messageList.scrollHeight;
  }

  function formatTime(value) {
    if (!value) return "";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return new Intl.DateTimeFormat("zh-CN", {
      hour: "2-digit",
      minute: "2-digit"
    }).format(date);
  }

  function formatDateTime(value) {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return new Intl.DateTimeFormat("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit"
    }).format(date);
  }

  function setFact(id, value) {
    const element = document.getElementById(id);
    element.textContent = value === null || value === undefined || value === "" ? "—" : String(value);
  }

  function resetInspector() {
    setFact("fact-conversation", "未开始");
    setFact("fact-intent", "—");
    setFact("fact-confidence", "—");
    setFact("fact-status", "—");
    setFact("fact-pending", "—");
    setFact("fact-refund", "—");
    setFact("fact-handoff", "—");
    clearElement(planList);
    appendText(planList, "li", "暂无计划", "muted");
    clearElement(toolList);
    appendText(toolList, "li", "暂无工具调用", "muted");
    validationBlock.className = "validation-block";
    validationCopy.className = "muted";
    validationCopy.textContent = "等待本轮结果";
  }

  function updateInspector(response) {
    setFact("fact-conversation", response.conversationId || "—");
    setFact("fact-intent", response.intent);
    setFact("fact-confidence",
      typeof response.confidence === "number" ? `${(response.confidence * 100).toFixed(0)}%` : "—");
    setFact("fact-status", response.status);
    setFact("fact-pending", response.pendingAction);
    setFact("fact-refund", response.refundId);
    setFact("fact-handoff", response.handoffTicketId);

    clearElement(planList);
    const plan = Array.isArray(response.plan) ? response.plan : [];
    if (!plan.length) {
      appendText(planList, "li", "本轮无工具计划", "muted");
    } else {
      plan.forEach((step) => appendText(planList, "li", step));
    }

    clearElement(toolList);
    const tools = Array.isArray(response.tools) ? response.tools : [];
    if (!tools.length) {
      appendText(toolList, "li", "本轮无工具调用", "muted");
    } else {
      tools.forEach((tool) => {
        const text = `${tool.tool} · ${tool.success ? "成功" : (tool.errorCode || "失败")}${tool.summary ? ` · ${tool.summary}` : ""}`;
        appendText(toolList, "li", text, tool.success ? "ok" : "fail");
      });
    }

    const validation = response.validation || { passed: true, issues: [] };
    const passed = validation.passed === true;
    validationBlock.className = `validation-block ${passed ? "pass" : "fail"}`;
    validationCopy.className = "";
    validationCopy.textContent = passed
      ? "事实核对通过"
      : `未通过：${(validation.issues || []).join("、") || "未知原因"}`;
  }

  function setConversationStatus(status) {
    state.conversationStatus = status || null;
    updateComposerAvailability();
    if (status === "WAITING_HUMAN") {
      showAlert(chatAlert, "会话已转人工，AI 工具调用已冻结。请等待客服处理，或新建会话继续咨询。");
    } else if (status === "COMPLETED") {
      showAlert(chatAlert, "本轮会话已完成。可以新建会话继续咨询。");
    } else if (status === "FAILED") {
      showAlert(chatAlert, "会话进入失败状态。请新建会话，或联系人工客服。", "error");
    } else {
      showAlert(chatAlert, "");
    }
  }

  function updateComposerAvailability() {
    const locked = ["WAITING_HUMAN", "COMPLETED", "FAILED"].includes(state.conversationStatus);
    messageInput.disabled = state.busy || locked;
    sendButton.disabled = state.busy || locked;
    if (locked) {
      messageInput.placeholder = "当前会话已结束或转人工，请新建会话。";
    } else if (!state.busy) {
      messageInput.placeholder = "描述你的问题，例如：我的订单 ORD-1001 到哪了？";
    }
  }

  function lockConversationFromResponse(response) {
    state.requestGeneration += 1;
    state.conversationId = response.conversationId;
    localStorage.setItem(conversationKey(state.actor), state.conversationId);
    setConversationStatus(response.status);
    updateInspector(response);
  }

  async function sendMessage(rawMessage) {
    const message = rawMessage.trim();
    if (!message || state.busy) return;
    if (["WAITING_HUMAN", "COMPLETED", "FAILED"].includes(state.conversationStatus)) {
      showAlert(chatAlert, "当前会话不能继续发送消息，请新建会话。");
      return;
    }

    showAlert(chatAlert, "");
    appendMessage("user", message, new Date().toISOString());
    messageInput.value = "";
    setBusy(true);
    const generation = ++state.requestGeneration;
    state.abortController?.abort();
    state.abortController = new AbortController();
    const body = { message };
    if (state.conversationId) body.conversationId = state.conversationId;
    try {
      const response = await api("/api/chat", {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey() },
        body: JSON.stringify(body),
        signal: state.abortController.signal
      });
      if (generation !== state.requestGeneration) return;
      state.conversationId = response.conversationId;
      localStorage.setItem(conversationKey(state.actor), state.conversationId);
      appendMessage("assistant", response.reply || "本轮没有返回文本回复。");
      updateInspector(response);
      setConversationStatus(response.status);
    } catch (error) {
      if (error.name === "AbortError" || generation !== state.requestGeneration) return;
      const detail = error.code ? `（${error.code}）` : "";
      showAlert(chatAlert, `${error.message}${detail}`, "error");
      appendMessage("system", `${error.message}${detail}`);
      if (error.code === "CONVERSATION_NOT_ACTIVE") {
        setConversationStatus("WAITING_HUMAN");
      }
    } finally {
      if (generation === state.requestGeneration) setBusy(false);
    }
  }

  async function restoreConversation() {
    if (!state.conversationId) {
      appendEmptyState("从一句具体的问题开始", "可以查询订单、工单，发起退款，或直接要求转人工。");
      resetInspector();
      setConversationStatus(null);
      return;
    }
    setApiStatus("恢复会话…", "busy");
    try {
      const conversation = await api(`/api/conversations/${encodeURIComponent(state.conversationId)}`);
      clearElement(messageList);
      const messages = Array.isArray(conversation.messages) ? conversation.messages : [];
      if (!messages.length) {
        appendEmptyState("会话已恢复", "这个会话还没有消息。", true);
      } else {
        messages.forEach((message) => {
          const role = String(message.role || "assistant").toLowerCase();
          appendMessage(role === "human_agent" ? "human_agent" : role,
            message.content || "", message.createdAt);
        });
      }
      setFact("fact-conversation", conversation.id);
      setFact("fact-intent", conversation.intent);
      setFact("fact-confidence",
        typeof conversation.confidence === "number" ? `${(conversation.confidence * 100).toFixed(0)}%` : "—");
      setFact("fact-status", conversation.status);
      setFact("fact-pending", conversation.pendingAction);
      setConversationStatus(conversation.status);
      setApiStatus("已连接");
    } catch (error) {
      if (error.status === 404 || error.status === 403) {
        localStorage.removeItem(conversationKey(state.actor));
        state.conversationId = null;
        appendEmptyState("会话不可用", "旧会话已不存在或当前身份无权查看，已为你重置。", true);
        resetInspector();
        setConversationStatus(null);
        setApiStatus("已连接");
      } else {
        setApiStatus("恢复失败", "error");
        showAlert(chatAlert, error.message, "error");
      }
    }
  }

  function newConversation() {
    state.abortController?.abort();
    state.requestGeneration += 1;
    setBusy(false);
    state.conversationId = null;
    state.conversationStatus = null;
    localStorage.removeItem(conversationKey(state.actor));
    appendEmptyState("新会话已就绪", "可以查询订单、工单，发起退款，或直接要求转人工。");
    resetInspector();
    showAlert(chatAlert, "");
    updateComposerAvailability();
    messageInput.focus();
  }

  function switchView(view) {
    state.activeView = view;
    navTabs.forEach((tab) => {
      const active = tab.dataset.view === view;
      tab.classList.toggle("active", active);
      tab.setAttribute("aria-selected", String(active));
    });
    [
      [chatView, "chat"],
      [handoffsView, "handoffs"],
      [auditView, "audit"]
    ].forEach(([panel, name]) => {
      const active = name === view;
      panel.classList.toggle("active", active);
      panel.hidden = !active;
    });
    if (view === "handoffs") loadHandoffs();
    if (view === "audit") {
      auditResource.value = "";
      loadAudit();
      renderAuditPermission();
    }
  }

  function renderAuditPermission() {
    if (actorRole() === "ADMIN") {
      clearElement(evaluationReport);
      appendText(evaluationReport, "p", "运行一组离线场景，验证意图、工具、人工兜底与结果校验。", "muted");
    } else {
      clearElement(evaluationReport);
      appendText(evaluationReport, "p", "评测与全量审计仅管理员可操作。", "muted");
    }
    runEvaluationButton.disabled = actorRole() !== "ADMIN";
  }

  async function loadHandoffs() {
    showAlert(handoffAlert, "");
    if (actorRole() === "CUSTOMER") {
      clearElement(handoffList);
      const empty = document.createElement("div");
      empty.className = "empty-state compact";
      appendText(empty, "h3", "当前身份无工单权限");
      appendText(empty, "p", "请切换到客服或管理员身份查看人工工单。");
      handoffList.appendChild(empty);
      return;
    }
    setApiStatus("加载工单…", "busy");
    try {
      const tickets = await api("/api/handoffs");
      renderHandoffs(Array.isArray(tickets) ? tickets : []);
      setApiStatus("已连接");
    } catch (error) {
      clearElement(handoffList);
      showAlert(handoffAlert, error.message, "error");
      setApiStatus("加载失败", "error");
    }
  }

  function renderHandoffs(tickets) {
    clearElement(handoffList);
    if (!tickets.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state compact";
      appendText(empty, "h3", "暂无人工工单");
      appendText(empty, "p", "新的人工兜底工单会出现在这里。");
      handoffList.appendChild(empty);
      return;
    }
    tickets.forEach((ticket) => handoffList.appendChild(renderHandoffCard(ticket)));
  }

  function renderHandoffCard(ticket) {
    const card = document.createElement("article");
    card.className = "handoff-card";
    const heading = document.createElement("div");
    heading.className = "card-heading";
    const title = document.createElement("div");
    appendText(title, "h3", ticket.subject || "人工工单");
    appendText(title, "p", `工单 ${ticket.id}`, "muted");
    heading.appendChild(title);
    const status = appendText(heading, "span", ticket.status,
      ticket.status === "RESOLVED" ? "tag" : "tag amber");
    status.setAttribute("aria-label", `状态 ${ticket.status}`);
    heading.appendChild(status);
    card.appendChild(heading);

    const meta = document.createElement("div");
    meta.className = "handoff-meta";
    [
      `优先级 ${ticket.priority}`,
      `客户 ${ticket.userId}`,
      `负责人 ${ticket.assignedAgentId || "未分配"}`,
      `创建 ${formatDateTime(ticket.createdAt)}`
    ].forEach((item) => appendText(meta, "span", item, "tag"));
    card.appendChild(meta);
    if (ticket.orderId) appendText(card, "p", `关联订单：${ticket.orderId}`, "muted");

    if (ticket.status !== "RESOLVED") {
      const actions = document.createElement("form");
      actions.className = "handoff-actions";
      const label = document.createElement("label");
      label.className = "sr-only";
      label.htmlFor = `reply-${ticket.id}`;
      label.textContent = `回复工单 ${ticket.id}`;
      const textarea = document.createElement("textarea");
      textarea.id = `reply-${ticket.id}`;
      textarea.maxLength = 2000;
      textarea.placeholder = "输入给客户的回复…";
      textarea.required = true;
      const row = document.createElement("div");
      row.className = "composer-actions";
      const resolveLabel = document.createElement("label");
      resolveLabel.className = "check-row";
      const resolve = document.createElement("input");
      resolve.type = "checkbox";
      resolve.checked = true;
      resolveLabel.appendChild(resolve);
      resolveLabel.appendChild(document.createTextNode("完成后关闭工单"));
      const submit = appendText(row, "button", "发送回复", "button primary");
      submit.type = "submit";
      actions.appendChild(label);
      actions.appendChild(textarea);
      actions.appendChild(row);
      actions.appendChild(resolveLabel);
      actions.addEventListener("submit", async (event) => {
        event.preventDefault();
        const message = textarea.value.trim();
        if (!message) {
          showAlert(handoffAlert, "回复内容不能为空。", "error");
          textarea.focus();
          return;
        }
        submit.disabled = true;
        submit.textContent = "发送中…";
        try {
          await api(`/api/handoffs/${encodeURIComponent(ticket.id)}/messages`, {
            method: "POST",
            body: JSON.stringify({ message, resolve: resolve.checked })
          });
          showAlert(handoffAlert, "");
          await loadHandoffs();
        } catch (error) {
          showAlert(handoffAlert, error.message, "error");
          submit.disabled = false;
          submit.textContent = "发送回复";
        }
      });
      card.appendChild(actions);
    }
    return card;
  }

  async function loadAudit() {
    showAlert(auditAlert, "");
    if (actorRole() !== "ADMIN") {
      clearElement(auditList);
      appendText(auditList, "p", "仅管理员可读取权限审计日志。", "muted");
      showAlert(auditAlert, "");
      return;
    }
    setApiStatus("加载审计…", "busy");
    try {
      const filter = auditResource.value.trim();
      const path = filter ? `/api/audit?resourceId=${encodeURIComponent(filter)}` : "/api/audit";
      const logs = await api(path);
      renderAuditLogs(Array.isArray(logs) ? logs : []);
      setApiStatus("已连接");
    } catch (error) {
      clearElement(auditList);
      showAlert(auditAlert, error.message, "error");
      setApiStatus("加载失败", "error");
    }
  }

  function renderAuditLogs(logs) {
    clearElement(auditList);
    if (!logs.length) {
      appendText(auditList, "p", "没有匹配的审计记录。", "muted");
      return;
    }
    logs.forEach((log) => {
      const row = document.createElement("div");
      row.className = "data-row";
      const content = document.createElement("div");
      appendText(content, "strong", log.action || "UNKNOWN_ACTION");
      appendText(content, "small",
        `${log.actorId} · ${log.actorRole} · ${log.resourceType || "RESOURCE"} ${log.resourceId || ""}`);
      appendText(content, "small",
        `${formatDateTime(log.createdAt)} · trace ${log.traceId || "—"}`);
      const outcome = appendText(row, "span", log.outcome || "UNKNOWN",
        log.outcome === "DENIED" ? "tag amber" : "tag");
      row.appendChild(content);
      row.appendChild(outcome);
      auditList.appendChild(row);
    });
  }

  async function runEvaluation() {
    if (actorRole() !== "ADMIN" || runEvaluationButton.disabled) return;
    runEvaluationButton.disabled = true;
    runEvaluationButton.textContent = "评测运行中…";
    clearElement(evaluationReport);
    appendText(evaluationReport, "p", "正在执行 5 个离线场景，评测事务会在结束后回滚。", "muted");
    try {
      const report = await api("/api/admin/evaluations/run", { method: "POST" });
      renderEvaluation(report);
      showAlert(auditAlert, "");
    } catch (error) {
      clearElement(evaluationReport);
      appendText(evaluationReport, "p", "评测未完成，请查看错误提示。", "muted");
      showAlert(auditAlert, error.message, "error");
    } finally {
      runEvaluationButton.disabled = false;
      runEvaluationButton.textContent = "运行评测";
    }
  }

  function renderEvaluation(report) {
    clearElement(evaluationReport);
    const board = document.createElement("div");
    board.className = "score-board";
    [
      [`${report.passed}/${report.total}`, "通过用例"],
      [`${Math.round((report.passRate || 0) * 100)}%`, "通过率"],
      [report.llmMode || "RULES_ONLY", "识别模式"]
    ].forEach(([value, label]) => {
      const score = document.createElement("div");
      score.className = "score";
      appendText(score, "strong", String(value));
      appendText(score, "span", label);
      board.appendChild(score);
    });
    evaluationReport.appendChild(board);
    appendText(evaluationReport, "p", `运行 ${report.runId} · ${formatDateTime(report.finishedAt)}`, "muted");

    const list = document.createElement("div");
    list.className = "case-list";
    (report.cases || []).forEach((item) => {
      const row = document.createElement("div");
      row.className = "case-row";
      const dot = appendText(row, "span", "", `case-dot${item.passed ? "" : " fail"}`);
      dot.setAttribute("aria-hidden", "true");
      const body = document.createElement("div");
      appendText(body, "strong", `${item.passed ? "通过" : "失败"} · ${item.name}`);
      appendText(body, "small", item.actual || "无详情");
      row.appendChild(body);
      list.appendChild(row);
    });
    evaluationReport.appendChild(list);
  }

  function changeActor() {
    state.abortController?.abort();
    state.requestGeneration += 1;
    state.actor = actorSelect.value;
    state.conversationId = localStorage.getItem(conversationKey(state.actor));
    state.conversationStatus = null;
    showAlert(chatAlert, "");
    showAlert(handoffAlert, "");
    showAlert(auditAlert, "");
    setBusy(false);
    restoreConversation();
    if (state.activeView === "handoffs") loadHandoffs();
    if (state.activeView === "audit") {
      renderAuditPermission();
      loadAudit();
    }
  }

  chatForm.addEventListener("submit", (event) => {
    event.preventDefault();
    sendMessage(messageInput.value);
  });

  messageInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      sendMessage(messageInput.value);
    }
  });

  document.querySelectorAll(".chip").forEach((chip) => {
    chip.addEventListener("click", () => {
      messageInput.value = chip.dataset.message || "";
      sendMessage(messageInput.value);
    });
  });

  newConversationButton.addEventListener("click", newConversation);
  refreshHandoffsButton.addEventListener("click", loadHandoffs);
  actorSelect.addEventListener("change", changeActor);
  navTabs.forEach((tab) => tab.addEventListener("click", () => switchView(tab.dataset.view)));
  auditForm.addEventListener("submit", (event) => {
    event.preventDefault();
    loadAudit();
  });
  runEvaluationButton.addEventListener("click", runEvaluation);

  resetInspector();
  updateComposerAvailability();
  restoreConversation();
})();
