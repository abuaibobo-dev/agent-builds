const STORAGE_KEY = "b-collector-state-v1";

const pageTitle = document.querySelector("#page-title");
const statusPill = document.querySelector("#status-pill");
const app = document.querySelector("#app");
const toast = document.querySelector("#toast");

const pageNames = {
  targets: "目标",
  collect: "采集",
  console: "控制台",
  ads: "广告",
  settings: "设置",
};

const isPackagedAndroid = location.protocol === "file:";

function id(prefix) {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
}

function defaultState() {
  return {
    page: "console",
    selectedTargetId: "",
    detailChannelId: "",
    detailLogId: "",
    account: {
      phone: "",
      apiId: "",
      apiHash: "",
      apiHashMask: "",
      code: "",
      password: "",
      name: "未登录",
      status: "待登录",
      connected: false,
      channelCount: 0,
    },
    channels: [],
    targets: [],
    bindings: {},
    queueTargetIds: [],
    settings: {
      runMinutes: 10,
      collectInterval: 3,
      sendMode: "fixed",
      sendFixedSeconds: 3,
      sendRandomMin: 2,
      sendRandomMax: 5,
      loop: true,
      autoSkip: true,
      retry: true,
      retryCount: 3,
      rateLimit: 50,
      autoSync: true,
      firstCollectMode: "first",
      filterMode: "strip_text_keep_media",
      captionMode: "keep",
      removeSource: true,
      contentMode: "all",
      adEnabled: true,
      // 通知设置
      notifyOnSend: true,
      notifyOnFail: true,
      notifyOnSwitch: false,
      // 代理设置
      proxyEnabled: false,
      proxyType: "socks5",
      proxyHost: "",
      proxyPort: 1080,
      proxyUser: "",
      proxyPass: "",
      // 定时采集
      scheduleEnabled: false,
      scheduleStart: "09:00",
      scheduleEnd: "23:00",
      scheduleMon: true,
      scheduleTue: true,
      scheduleWed: true,
      scheduleThu: true,
      scheduleFri: true,
      scheduleSat: true,
      scheduleSun: true,
      // 日志设置
      logLevel: "all",
      // 去重增强
      contentDedup: true,
      // 健康监控
      healthCheck: true,
      healthInterval: 3600,
    },
    sourceRules: {},
    runtime: {
      status: "stopped",
      startedAt: null,
      pausedAt: null,
      currentTargetIndex: 0,
      currentSourceIndex: 0,
      currentRound: 0,
      remainingSeconds: 0,
      collected: 0,
      sent: 0,
      failed: 0,
      todayCollected: 0,
      todaySent: 0,
      todayDate: "",
      lastServiceEventId: 0,
      lastResult: "",
      lastSourceName: "",
      lastSourceRule: "",
      lastPosition: "",
    },
    positions: {},
    dedup: {},
    filters: {
      keywords: ["广告", "推广", "私聊", "加群", "返利", "联系方式", "代理", "招商"],
      contentTypes: ["image", "video", "gif"],
      blockedSources: [],
    },
    ads: {
      pool: [],
      mode: "random",
      fixedEvery: 10,
      randomMin: 8,
      randomMax: 15,
      scheduleTimes: ["08:00", "12:00", "18:00", "20:00", "22:00"],
      insertPosition: "after",
    },
    security: {
      appLock: false,
      biometric: false,
      pinCode: "",
      pinHash: "",
      backupPassword: true,
      integrityCheck: true,
      sensitiveVerify: true,
      databaseEncrypted: false,
      lastCheckAt: null,
      lastCheckResult: "未检查",
    },
    logs: [],
    // 多账号
    accounts: [],
    activeAccountId: "",
    // 统计报表
    stats: {
      daily: [],
      weekly: [],
      monthly: [],
      lastUpdated: 0,
    },
    // 内容去重指纹
    contentFingerprints: {},
    // 健康监控记录
    healthLog: [],
    lastHealthCheck: 0,
  };
}

function loadState() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? normalize({ ...defaultState(), ...JSON.parse(raw) }) : normalize(defaultState());
  } catch {
    return normalize(defaultState());
  }
}

function normalize(next) {
  if (!["targets", "collect", "console", "ads", "settings"].includes(next.page)) {
    next.page = "settings";
  }
  next.channels = Array.isArray(next.channels) ? next.channels : [];
  next.targets = Array.isArray(next.targets) ? next.targets : [];
  next.queueTargetIds = Array.isArray(next.queueTargetIds) ? next.queueTargetIds.filter((id) => next.targets.some((target) => target.id === id)) : [];
  next.logs = Array.isArray(next.logs) ? next.logs : [];
  next.ads = { ...defaultState().ads, ...(next.ads || {}) };
  next.settings = { ...defaultState().settings, ...(next.settings || {}) };
  next.security = { ...defaultState().security, ...(next.security || {}) };
  next.sourceRules = next.sourceRules && typeof next.sourceRules === "object" ? next.sourceRules : {};
  next.bindings = next.bindings && typeof next.bindings === "object" ? next.bindings : {};
  next.positions = next.positions && typeof next.positions === "object" ? next.positions : {};
  next.dedup = next.dedup && typeof next.dedup === "object" ? next.dedup : {};
  next.accounts = Array.isArray(next.accounts) ? next.accounts : [];
  next.stats = { daily: [], weekly: [], monthly: [], lastUpdated: 0, ...(next.stats || {}) };
  next.contentFingerprints = next.contentFingerprints && typeof next.contentFingerprints === "object" ? next.contentFingerprints : {};
  next.healthLog = Array.isArray(next.healthLog) ? next.healthLog : [];
  return next;
}

let state = loadState();
let tick = null;
let collectBusy = false;
let unlocked = false;

function isEditingForm() {
  const active = document.activeElement;
  return !!active && ["INPUT", "SELECT", "TEXTAREA"].includes(active.tagName);
}

async function api(path, body = {}) {
  if (isPackagedAndroid) {
    if (!window.NativeBridge?.request) {
      // 等待 NativeBridge 就绪（最多 10 秒）
      for (let i = 0; i < 20; i++) {
        await new Promise(r => setTimeout(r, 500));
        if (window.NativeBridge?.request) break;
      }
      if (!window.NativeBridge?.request) {
        throw new Error("TDLib 正在初始化，请稍后刷新页面重试");
      }
    }
    const raw = window.NativeBridge.request(path, JSON.stringify(body));
    const payload = JSON.parse(raw || "{}");
    if (payload.ok === false) {
      const msg = payload.error || "请求失败";
      // TDLib 初始化中，自动重试
      if (msg.includes("初始化中") || msg.includes("请稍后")) {
        await new Promise(r => setTimeout(r, 2000));
        return api(path, body);
      }
      throw new Error(msg);
    }
    return payload;
  }
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.ok === false) {
    throw new Error(payload.error || "请求失败");
  }
  return payload;
}

async function sha256(text) {
  try {
    if (window.crypto && crypto.subtle) {
      const data = new TextEncoder().encode(String(text || ""));
      const digest = await crypto.subtle.digest("SHA-256", data);
      return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
    }
  } catch (e) { /* fall through */ }
  return ""; // 不支持时降级：不落盘明文，需重新设置
}

async function save() {
  const snapshot = {
    ...state,
    account: { ...state.account, password: "", apiHash: "" },
    security: { ...state.security, pinCode: "" },
  };
  if (state.security.pinCode) {
    snapshot.security.pinHash = await sha256(state.security.pinCode);
  } else {
    snapshot.security.pinHash = "";
  }
  // 同步内存中的哈希值，保证同会话内解锁可用
  state.security.pinHash = snapshot.security.pinHash;
  localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
}

function log(level, title, detail) {
  state.logs.unshift({ id: id("log"), time: Date.now(), level, title, detail });
  state.logs = state.logs.slice(0, 1000);
}

function showToast(text) {
  toast.textContent = text;
  toast.hidden = false;
  setTimeout(() => {
    toast.hidden = true;
  }, 2200);
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[char]);
}

function escapeAttr(value) {
  return escapeHtml(value).replace(/`/g, "&#96;");
}

function formatTime(ts) {
  if (!ts) return "未记录";
  return new Date(ts).toLocaleString("zh-CN", { hour12: false });
}

function shortTime(ts) {
  if (!ts) return "--:--:--";
  return new Date(ts).toLocaleTimeString("zh-CN", { hour12: false });
}

function currentTarget() {
  const active = runnableTargets();
  return active[state.runtime.currentTargetIndex] || null;
}

function runnableTargets() {
  return (state.queueTargetIds || [])
    .map((id) => state.targets.find((target) => target.id === id && target.enabled !== false))
    .filter(Boolean);
}

function boundSources(targetId) {
  const ids = state.bindings[targetId] || [];
  return ids.map((sourceId) => state.channels.find((item) => item.id === sourceId)).filter(Boolean);
}

function targetChannelIds() {
  return new Set(state.targets.map((target) => target.channelId || target.id));
}

function availableSourceChannels() {
  const targetIds = targetChannelIds();
  return state.channels.filter((channel) => !targetIds.has(channel.id));
}

function render() {
  renderLockOverlay();
  pageTitle.textContent = pageNames[state.page] || "控制台";
  statusPill.textContent = state.runtime.status === "running" ? "运行中" : state.account.connected ? "已登录" : state.account.status === "需要API凭证" ? "需要凭证" : "待登录";
  statusPill.classList.toggle("is-running", state.runtime.status === "running");
  document.querySelectorAll(".bottom-nav button").forEach((button) => {
    button.classList.toggle("is-active", button.dataset.page === state.page);
  });

  const views = {
    console: pageConsole,
    targets: pageTargets,
    collect: pageCollect,
    ads: pageAds,
    settings: pageSettings,
  };
  app.innerHTML = (views[state.page] || pageConsole)();
}

function renderLockOverlay() {
  let lock = document.querySelector("#app-lock");
  const needsLock = state.security.appLock && state.security.pinCode && !unlocked;
  if (!needsLock) {
    lock?.remove();
    return;
  }
  if (!lock) {
    lock = document.createElement("div");
    lock.id = "app-lock";
    lock.className = "app-lock";
    document.body.appendChild(lock);
  }
  lock.innerHTML = `
    <div class="app-lock__panel">
      <div class="brand-mark brand-mark--small">B</div>
      <h2>启动密码</h2>
      <div class="field"><input id="unlock-pin" type="password" inputmode="numeric" placeholder="输入启动密码" /></div>
      <div class="toolbar">
        <button class="btn" data-action="unlock-app">解锁</button>
        ${state.security.biometric ? `<button class="btn secondary" data-action="biometric-unlock">指纹解锁</button>` : ""}
      </div>
      ${state.security.biometric ? `<div class="hint">指纹验证失败时可继续使用启动密码。</div>` : ""}
    </div>
  `;
}

function card(title, subtitle, body, extra = "") {
  return `
    <section class="card">
      <div class="card__body">
        <div class="section-head">
          <div>
            <h3>${escapeHtml(title)}</h3>
            ${subtitle ? `<small>${escapeHtml(subtitle)}</small>` : ""}
          </div>
          ${extra}
        </div>
        ${body}
      </div>
    </section>
  `;
}

function metric(label, value, hint = "") {
  return `
    <div class="metric">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
      ${hint ? `<div class="hint">${escapeHtml(hint)}</div>` : ""}
    </div>
  `;
}

function empty(text) {
  return `<div class="empty">${escapeHtml(text)}</div>`;
}

function pageConsole() {
  const target = currentTarget();
  const sources = target ? boundSources(target.id) : [];
  const currentSource = sources[state.runtime.currentSourceIndex] || sources[0] || null;
  const currentSourceName = state.runtime.lastSourceName || currentSource?.name || "未开始";
  const sourceHint = `${sources.length} 个${state.runtime.lastSourceRule ? ` · ${ruleLabel(state.runtime.lastSourceRule)}` : ""}`;
  const queue = runnableTargets();
  const queuePreview = queue.length > 2
    ? `${queue.slice(0, 2).map((item) => item.name).join(" → ")} 等 ${queue.length} 个`
    : queue.map((item) => item.name).join(" → ");
  const mode = queue.length === 1 ? "单跑" : queue.length > 1 ? "队列" : "未选择";
  return `
    ${card("状态总览", "账号、连接和后台运行", `
      <div class="grid grid--compact">
        ${metric("账号", state.account.connected ? state.account.name || "已登录" : "未登录", state.account.status)}
        ${metric("连接", state.account.connected ? "正常" : "未连接")}
        ${metric("频道", String(state.channels.length))}
        ${metric("运行", runtimeIcon(), runtimeLabel())}
        ${metric("后台", state.runtime.status === "running" ? "●" : "■", state.runtime.status === "running" ? "前台服务" : "未运行")}
      </div>
      <div class="status-actions">
        <button class="btn" data-action="start">开始采集</button>
        <button class="btn secondary" data-action="pause">暂停采集</button>
        <button class="btn danger" data-action="stop">停止采集</button>
        ${state.account.connected ? `
          <button class="btn secondary" type="button" data-action="sync-channels">同步频道</button>
          <button class="btn ghost" type="button" data-action="logout">退出登录</button>
        ` : `
          <button class="btn secondary" type="button" data-action="sync-channels">同步频道</button>
          <button class="btn secondary" type="button" data-page="settings">去登录</button>
        `}
      </div>
    `)}
    ${card("采集状态", "当前任务和最近采集结果", `
      <div class="task-strip">
        <div><span>当前</span><strong>${escapeHtml(target?.name || "未启动")}</strong></div>
        <div><span>队列</span><strong>${escapeHtml(queuePreview || "未选择")}</strong></div>
        <div><span>轮次</span><strong>${state.runtime.currentRound || 0} / ${state.runtime.status === "running" ? "进行中" : "已停止"}</strong></div>
      </div>
      <div class="grid">
        ${metric("模式", mode)}
        ${metric("来源", currentSourceName, sourceHint)}
        ${metric("剩余", secondsText(state.runtime.remainingSeconds))}
        ${metric("结果", state.runtime.lastResult || "等待采集", state.runtime.lastPosition ? `记忆点 ${state.runtime.lastPosition}` : "")}
        ${metric("已发送", String(state.runtime.sent), "累计")}
        ${metric("失败", String(state.runtime.failed), "累计")}
      </div>
      <div class="mini-log">
        <div class="mini-log__head">
          <strong>运行日志</strong>
          <span class="mini-log__toggle" data-action="toggle-log" style="cursor:pointer">${state.runtime.showFullLog ? "收起 ▲" : "展开 ▼"} · 共 ${state.logs.length} 条</span>
        </div>
        <div class="mini-log__body">
          ${(state.runtime.showFullLog ? state.logs : state.logs.slice(0, 8)).map(renderMiniLog).join("") || empty("暂无运行日志。")}
        </div>
      </div>
    `)}
    ${card("数据统计", "今日和累计数据", `
      <div class="grid-3">
        ${metric("今日发送", String(state.runtime.todaySent))}
        ${metric("累计发送", String(state.runtime.sent))}
        ${metric("失败", String(state.runtime.failed))}
        ${metric("队列轮次", String(state.runtime.currentRound || 0))}
        ${metric("运行时长", state.runtime.startedAt ? elapsedText(Date.now() - state.runtime.startedAt) : "00:00")}
        ${metric("去重", String(Object.keys(state.dedup).length))}
      </div>
    `)}
    ${renderLogDetail()}
  `;
}

function pageTargets() {
  const selected = state.targets.find((item) => item.id === state.selectedTargetId) || null;
  if (selected) return pageTargetBinding(selected);
  const detailChannel = findAnyChannel(state.detailChannelId);
  const channelPool = availableSourceChannels();
  return `
    ${detailChannel ? renderChannelDetail(detailChannel) : ""}
    ${card("目标频道", "添加、删除、排序和选择目标频道", `
      <div class="toolbar">
        <button class="btn secondary" data-action="sync-channels">同步 Telegram 频道</button>
      </div>
      <div class="list" style="margin-top:12px">
        ${state.targets.length ? state.targets.map(renderTargetRow).join("") : empty("暂无目标频道，请先添加目标频道。")}
      </div>
    `)}
    ${card("频道池", "同步后可将频道设为目标", `
      <div class="toolbar">
        <button class="btn secondary" data-action="sync-channels">同步 Telegram 频道</button>
      </div>
      <div class="list" style="margin-top:12px">
        ${channelPool.length ? channelPool.map(renderChannelPoolRow).join("") : empty("暂无可设为目标的频道。")}
      </div>
    `)}
  `;
}

function pageTargetBinding(target) {
  const checkedCount = boundSources(target.id).length;
  const detailChannel = findAnyChannel(state.detailChannelId);
  const sourcePool = availableSourceChannels();
  return `
    ${detailChannel ? renderChannelDetail(detailChannel) : ""}
    ${card(target.name, `绑定来源频道：已选择 ${checkedCount} 个`, `
      <div class="toolbar">
        <button class="btn secondary" data-action="back-target-list">返回目标列表</button>
        <button class="btn secondary" data-action="sync-channels">同步 Telegram 频道</button>
      </div>
    `)}
    ${card("频道池", "勾选要绑定到该目标的来源频道", `
      <div class="hint" style="margin-bottom:12px">点击整行即可勾选或取消绑定；已绑定来源会显示为选中。</div>
      <div class="checks">
        ${sourcePool.length ? sourcePool.map((source) => renderSourceCheck(target, source)).join("") : empty("暂无可绑定的来源频道。")}
      </div>
    `)}
  `;
}

function renderChannelPoolRow(channel) {
  const username = channel.username ? `@${channel.username}` : "无公开链接";
  return `
    <div class="row">
      <div class="row-main">
        <div class="row-title">${escapeHtml(channel.name)}</div>
        <div class="row-meta">${escapeHtml(username)} · ${escapeHtml(channel.type || "channel")} · ${channel.status === "active" ? "有效" : "失效"}</div>
      </div>
      <div class="target-actions">
        <button class="icon-btn icon-btn--wide" data-action="show-channel-detail" data-id="${channel.id}">详情</button>
        <button class="icon-btn icon-btn--wide" data-action="add-target-from-pool" data-id="${channel.id}">目标</button>
      </div>
    </div>
  `;
}

function renderTargetRow(target, index) {
  return `
    <div class="row row--target" data-action="open-target-binding" data-id="${target.id}">
      <div class="row-main">
        <div class="row-title">${index + 1}. ${escapeHtml(target.name)}</div>
        <div class="row-meta">${target.username ? `@${escapeHtml(target.username)} · ` : ""}来源 ${boundSources(target.id).length} · 点击绑定</div>
      </div>
      <div class="target-actions">
        <button class="icon-btn" data-action="show-channel-detail" data-id="${target.id}" title="详情">i</button>
        <button class="icon-btn" data-action="move-target" data-id="${target.id}" data-dir="-1" title="上移">↑</button>
        <button class="icon-btn" data-action="move-target" data-id="${target.id}" data-dir="1" title="下移">↓</button>
        <button class="icon-btn" data-action="delete-target" data-id="${target.id}" title="删除">×</button>
      </div>
    </div>
  `;
}

function renderSourceCheck(target, source) {
  const checked = target && (state.bindings[target.id] || []).includes(source.id);
  const rule = state.sourceRules[source.id]?.mode || "default";
  return `
    <label class="check-row bind-row">
      <span>
        <strong>${escapeHtml(source.name)}</strong>
        <span class="row-meta">${source.username ? `@${escapeHtml(source.username)} · ` : ""}${checked ? "已绑定" : "未绑定"} · 来源频道</span>
      </span>
      <div class="segmented segmented--compact" data-source-rule-group="${source.id}">
        ${[
          { value: "default", label: "默认" },
          { value: "media_clean", label: "净采" },
          { value: "no_ads_text", label: "无广告" },
          { value: "comments", label: "评论" },
          { value: "main_comments", label: "主+评" },
        ].map((option) => `
          <button class="${option.value === rule ? "is-active" : ""}" type="button" data-action="set-source-rule" data-source="${source.id}" data-value="${option.value}">${option.label}</button>
        `).join("")}
      </div>
      <input type="checkbox" data-bind-source="1" data-target="${target?.id || ""}" data-source="${source.id}" ${checked ? "checked" : ""} />
    </label>
  `;
}

function findAnyChannel(channelId) {
  if (!channelId) return null;
  return state.channels.find((item) => item.id === channelId)
    || state.targets.find((item) => item.id === channelId || item.channelId === channelId)
    || null;
}

function channelLink(channel) {
  return channel?.username ? `https://t.me/${channel.username}` : "";
}

function renderChannelDetail(channel) {
  const link = channelLink(channel);
  return card("频道详情", channel.name, `
    <div class="detail-grid">
      <div><span>名称</span><strong>${escapeHtml(channel.name)}</strong></div>
      <div><span>用户名</span><strong>${channel.username ? `@${escapeHtml(channel.username)}` : "无"}</strong></div>
      <div><span>类型</span><strong>${escapeHtml(channel.type || "channel")}</strong></div>
      <div><span>状态</span><strong>${channel.status === "active" ? "有效" : "失效"}</strong></div>
      <div class="detail-grid__full"><span>链接</span><strong>${link ? escapeHtml(link) : "无公开链接"}</strong></div>
    </div>
    <div class="toolbar" style="margin-top:10px">
      ${link ? `<button class="btn secondary" data-action="copy-channel-link" data-id="${channel.id}">复制链接</button>` : ""}
      <button class="btn ghost" data-action="hide-channel-detail">关闭</button>
    </div>
  `);
}

function pageCollect() {
  return `
    ${card("运行队列", "只采集队列中的目标，按加入顺序单线程执行", `
      <div class="toolbar">
        <button class="btn secondary" type="button" data-action="queue-all-targets">全部加入</button>
        <button class="btn ghost" type="button" data-action="clear-target-queue">清空队列</button>
      </div>
      <div class="list" style="margin-top:12px">
        ${state.targets.length ? state.targets.map(renderQueueTargetRow).join("") : empty("暂无目标频道，请先到目标页添加。")}
      </div>
      <form class="form queue-settings" data-form="save-queue-settings">
        <div class="grid">
          ${numberField("runMinutes", "每个目标运行分钟", state.settings.runMinutes)}
          ${numberField("collectInterval", "采集间隔秒", state.settings.collectInterval)}
          ${numberField("retryCount", "失败重试次数", state.settings.retryCount)}
          ${numberField("rateLimit", "每分钟最大发送", state.settings.rateLimit)}
        </div>
        <button class="btn secondary" type="submit">保存队列参数</button>
      </form>
      <div class="hint" style="margin-top:12px">队列为空时不会启动采集；点击“单跑”会清空队列并只保留当前目标。</div>
    `)}
    ${card("采集规则", "首次采集位置、内容范围和广告处理", `
      <form class="form" data-form="save-collect-rules">
        <div class="grid">
          ${segmentedField("firstCollectMode", "首次采集位置", state.settings.firstCollectMode, [
            { value: "latest", label: "最新" },
            { value: "recent100", label: "100条" },
            { value: "recent500", label: "500条" },
            { value: "first", label: "较早" },
          ])}
          ${segmentedField("contentMode", "采集内容", state.settings.contentMode, [
            { value: "all", label: "全部" },
            { value: "media_only", label: "媒体" },
            { value: "text_only", label: "文字" },
          ])}
        </div>
        <label class="check-row">
          <span><strong>采集时插入广告</strong><span class="row-meta">关闭后只采内容，不追加广告池内容</span></span>
          <input name="adEnabled" type="checkbox" ${state.settings.adEnabled ? "checked" : ""} />
        </label>
        <div class="toolbar">
          <button class="btn" type="submit">保存采集规则</button>
          <button class="btn secondary" type="button" data-action="rebuild-positions-latest">从当前最新开始</button>
          <button class="btn ghost" type="button" data-action="clear-positions">清空记忆点</button>
        </div>
      </form>
    `)}
    ${card("过滤与发送", "关键词、媒体类型和文案处理", `
      <form class="form" data-form="save-filter-send">
        <div class="field">
          <label>关键词过滤，每行一个</label>
          <textarea name="keywords">${escapeHtml(state.filters.keywords.join("\n"))}</textarea>
        </div>
        <div class="grid">
          ${segmentedField("filterMode", "过滤模式", state.settings.filterMode, [
            { value: "strip_text_keep_media", label: "删字保媒" },
            { value: "discard", label: "丢弃" },
            { value: "log", label: "记录" },
          ])}
          ${segmentedField("captionMode", "文案模式", state.settings.captionMode, [
            { value: "keep", label: "保留" },
            { value: "delete", label: "删除" },
            { value: "append", label: "追加" },
            { value: "replace", label: "替换" },
          ])}
        </div>
        <label class="check-row">
          <span><strong>删除来源信息</strong><span class="row-meta">发送时隐藏转发来源、频道来源和原始链接</span></span>
          <input name="removeSource" type="checkbox" ${state.settings.removeSource ? "checked" : ""} />
        </label>
        <div class="grid">
          ${segmentedField("sendMode", "发送间隔模式", state.settings.sendMode, [
            { value: "fixed", label: "固定" },
            { value: "random", label: "随机" },
          ])}
          ${numberField("sendFixedSeconds", "固定发送秒", state.settings.sendFixedSeconds)}
          ${numberField("sendRandomMin", "随机最小秒", state.settings.sendRandomMin)}
          ${numberField("sendRandomMax", "随机最大秒", state.settings.sendRandomMax)}
        </div>
        <button class="btn" type="submit">保存过滤与发送</button>
      </form>
    `)}
  `;
}

function renderQueueTargetRow(target, index) {
  const inQueue = state.queueTargetIds.includes(target.id);
  const isCurrent = state.runtime.status === "running" && currentTarget()?.id === target.id;
  const sourceCount = boundSources(target.id).length;
  const status = sourceCount === 0 ? "无来源" : isCurrent ? "当前运行" : inQueue ? "队列中" : "未加入";
  return `
    <div class="row">
      <div class="row-main">
        <div class="row-title">${index + 1}. ${escapeHtml(target.name)}</div>
        <div class="row-meta">来源 ${sourceCount} · ${status}</div>
      </div>
      <div class="target-actions target-actions--wrap">
        ${inQueue
          ? `<button class="icon-btn icon-btn--wide" data-action="queue-remove-target" data-id="${target.id}">移出</button>`
          : `<button class="icon-btn icon-btn--wide icon-btn--primary" data-action="queue-add-target" data-id="${target.id}">加入</button>`}
        <button class="icon-btn icon-btn--wide" data-action="queue-single-target" data-id="${target.id}">单跑</button>
      </div>
    </div>
  `;
}

function numberField(name, label, value) {
  return `<div class="field"><label>${escapeHtml(label)}</label><input name="${name}" type="number" min="1" value="${escapeHtml(value)}" /></div>`;
}

function segmentedField(name, label, value, options) {
  return `
    <div class="field">
      <label>${escapeHtml(label)}</label>
      <input type="hidden" name="${escapeAttr(name)}" value="${escapeAttr(value)}" />
      <div class="segmented" data-option-field="${escapeAttr(name)}">
        ${options.map((option) => `
          <button class="${option.value === value ? "is-active" : ""}" type="button" data-action="set-option" data-field="${escapeAttr(name)}" data-value="${escapeAttr(option.value)}">${escapeHtml(option.label)}</button>
        `).join("")}
      </div>
    </div>
  `;
}

function switchRow(name, title, detail, checked) {
  return `
    <label class="check-row">
      <span><strong>${escapeHtml(title)}</strong><span class="row-meta">${escapeHtml(detail)}</span></span>
      <input name="${name}" type="checkbox" ${checked ? "checked" : ""} />
    </label>
  `;
}

function pageAds() {
  return `
    ${card("广告池", "支持文字、图片、视频和混合广告的配置入口", `
      <form class="form" data-form="add-ad">
        <div class="field"><label>广告名称</label><input name="name" placeholder="例如：后置广告 A" required /></div>
        <div class="field"><label>广告类型</label>
          <div class="segmented" data-name="type">
            <button type="button" class="is-active" data-value="text">文字</button>
            <button type="button" data-value="link">链接</button>
            <button type="button" data-value="anchor">锚点</button>
          </div>
        </div>
        <div class="field"><label>广告链接</label><input name="url" placeholder="https://t.me/..." /></div>
        <div class="field"><label>锚点文字</label><input name="anchorText" placeholder="例如：点击查看" /></div>
        <div class="field"><label>广告内容</label><textarea name="content" placeholder="输入广告文案或媒体说明" required></textarea></div>
        <button class="btn" type="submit">添加广告</button>
      </form>
      <div class="list" style="margin-top:12px">
        ${state.ads.pool.length ? state.ads.pool.map(renderAdRow).join("") : empty("暂无广告，请先添加广告。")}
      </div>
    `)}
    ${card("广告规则", "固定、随机、定时和内容插入", `
      <form class="form" data-form="save-ad-rules">
        <div class="grid">
          ${segmentedField("mode", "调用方式", state.ads.mode, [
            { value: "random", label: "随机" },
            { value: "order", label: "顺序" },
          ])}
          ${numberField("fixedEvery", "固定间隔条数", state.ads.fixedEvery)}
          ${numberField("randomMin", "随机最小条数", state.ads.randomMin)}
          ${numberField("randomMax", "随机最大条数", state.ads.randomMax)}
        </div>
        ${segmentedField("insertPosition", "插入位置", state.ads.insertPosition, [
          { value: "after", label: "后置" },
          { value: "before", label: "前置" },
          { value: "random", label: "随机" },
        ])}
        <button class="btn" type="submit">保存广告规则</button>
      </form>
      <div class="hint" style="margin-top:12px">置顶广告和高级广告统计作为增强能力预留。</div>
    `)}
  `;
}

function renderAdRow(ad) {
  const meta = [ad.type === "anchor" ? "锚点广告" : ad.type === "link" ? "链接广告" : "文字广告", ad.url || ""].filter(Boolean).join(" · ");
  return `
    <div class="row">
      <div class="row-main">
        <div class="row-title">${escapeHtml(ad.name)}</div>
        <div class="row-meta">${escapeHtml(meta || ad.content.slice(0, 58))}</div>
      </div>
      <button class="btn ghost" data-action="delete-ad" data-id="${ad.id}">删除</button>
    </div>
  `;
}

function visibleLogs() {
  if (state.logFilter === "error") return state.logs.filter((item) => item.level === "error");
  return state.logs;
}

function renderLog(item) {
  return `
    <div class="log">
      <div class="log-head"><span>${escapeHtml(item.level.toUpperCase())}</span><span>${formatTime(item.time)}</span></div>
      <div class="log-title">${escapeHtml(item.title)}</div>
      <div class="log-detail">${escapeHtml(item.detail)}</div>
    </div>
  `;
}

function renderMiniLog(item) {
  return `
    <button class="mini-log__item" type="button" data-action="show-log-detail" data-id="${escapeAttr(item.id)}">
      <time>${escapeHtml(shortTime(item.time))}</time>
      <span>${escapeHtml(item.level.toUpperCase())}</span>
      <strong>${escapeHtml(item.title)}</strong>
      <em>${escapeHtml(item.detail)}</em>
    </button>
  `;
}

function renderLogDetail() {
  if (!state.detailLogId) return "";
  const item = state.logs.find((entry) => entry.id === state.detailLogId);
  if (!item) return "";
  return `
    <div class="modal-backdrop" data-action="hide-log-detail">
      <section class="modal-card" role="dialog" aria-modal="true" onclick="event.stopPropagation()">
        <div class="modal-head">
          <div>
            <small>${escapeHtml(item.level.toUpperCase())} · ${escapeHtml(formatTime(item.time))}</small>
            <h3>${escapeHtml(item.title)}</h3>
          </div>
          <button class="icon-btn" data-action="hide-log-detail" title="关闭">×</button>
        </div>
        <div class="modal-body">${escapeHtml(item.detail || "无详情")}</div>
      </section>
    </div>
  `;
}

function pageSettings() {
  const accountLogged = state.account.connected;
  return `
    ${accountLogged ? "" : card("账号管理", "API 凭证和 Telegram 登录", `
        ${state.account.status === "需要API凭证" ? `<div class="banner banner--warn">⚠️ 需要 API 凭证！请在下面填写从 my.telegram.org/apps 获取的 API ID 和 API Hash<br><small>注意：Bot Token（含冒号）不能用于登录用户账号</small></div>` : ""}
        <form class="form" data-form="api-credentials">
          <div class="grid">
            <div class="field"><label>API ID</label><input name="apiId" value="${escapeAttr(state.account.apiId || '')}" placeholder="从 my.telegram.org 获取" inputmode="numeric" /></div>
            <div class="field"><label>API Hash</label><input name="apiHash" value="${escapeAttr(state.account.apiHashMask || '')}" placeholder="${state.account.apiHashMask ? '已保存（留空保持不变）' : '从 my.telegram.org 获取'}" /></div>
          </div>
          <button class="btn secondary" type="submit">保存 API 凭证</button>
        </form>
        <form class="form" data-form="login" style="margin-top:12px">
          <div class="field"><label>手机号（含区号）</label>
            <div class="field-phone">
              <select class="phone-code" data-name="phoneCode">
                <option value="+86" ${state.account.phone?.startsWith("+86") ? "selected" : ""}>🇨🇳 +86</option>
                <option value="+66" ${!state.account.phone || state.account.phone?.startsWith("+66") ? "selected" : ""}>🇹🇭 +66</option>
                <option value="+1" ${state.account.phone?.startsWith("+1") ? "selected" : ""}>🇺🇸 +1</option>
                <option value="+7" ${state.account.phone?.startsWith("+7") ? "selected" : ""}>🇷🇺 +7</option>
                <option value="+44" ${state.account.phone?.startsWith("+44") ? "selected" : ""}>🇬🇧 +44</option>
                <option value="+81" ${state.account.phone?.startsWith("+81") ? "selected" : ""}>🇯🇵 +81</option>
                <option value="+82" ${state.account.phone?.startsWith("+82") ? "selected" : ""}>🇰🇷 +82</option>
                <option value="+91" ${state.account.phone?.startsWith("+91") ? "selected" : ""}>🇮🇳 +91</option>
                <option value="+852" ${state.account.phone?.startsWith("+852") ? "selected" : ""}>🇭🇰 +852</option>
                <option value="+886" ${state.account.phone?.startsWith("+886") ? "selected" : ""}>🇹🇼 +886</option>
              </select>
              <input name="phone" data-account-field="phone" value="${escapeAttr(state.account.phone?.replace(/^\+\d+/, ""))}" placeholder="例如 812345678" />
            </div>
          </div>
          <div class="grid">
            <div class="field"><label>验证码</label><input name="code" data-account-field="code" value="${escapeAttr(state.account.code)}" placeholder="验证码" inputmode="numeric" autocomplete="one-time-code" /></div>
            <div class="field"><label>二步验证密码</label><input name="password" data-account-field="password" value="${escapeAttr(state.account.password)}" type="password" placeholder="如已开启" /></div>
          </div>
          <div class="toolbar">
            <button class="btn secondary" type="button" data-action="request-code">获取验证码</button>
            <button class="btn" type="submit">登录</button>
          </div>
        </form>
    `)}
    ${card("安全中心", "保护账号、配置和敏感操作", `
      <div class="grid">
        ${metric("设备状态", "正常")}
        ${metric("APP 完整性", state.security.integrityCheck ? "已开启" : "关闭")}
        ${metric("本地数据保护", state.security.databaseEncrypted ? "已加密" : "待接入", "当前使用 APP 私有目录保存配置、记忆点和日志")}
        ${metric("配置保护", state.security.backupPassword ? "已开启" : "关闭")}
        ${metric("登录保护", state.security.appLock && state.security.pinCode ? (state.security.biometric ? "密码 + 指纹" : "启动密码") : "关闭", state.security.biometric ? "使用系统生物识别" : "")}
        ${metric("检查结果", state.security.lastCheckResult || "未检查", state.security.lastCheckAt ? formatTime(state.security.lastCheckAt) : "")}
      </div>
      <div class="checks" style="margin-top:12px">
        ${securitySwitch("appLock", "启动密码", "打开 APP 时需要输入启动密码")}
        ${securitySwitch("biometric", "指纹解锁", "使用系统生物识别，启动密码作为备用")}
        ${securitySwitch("backupPassword", "导出配置密码", "备份文件需要密码")}
        ${securitySwitch("integrityCheck", "APP 完整性检测", "检测签名和核心文件状态")}
        ${securitySwitch("sensitiveVerify", "敏感操作二次验证", "导出、清空、退出前再次确认")}
      </div>
      <form class="form" data-form="save-pin" style="margin-top:12px">
        <div class="field"><label>启动密码</label><input name="pinCode" type="password" inputmode="numeric" value="${escapeAttr(state.security.pinCode)}" placeholder="设置后开启启动密码生效" /></div>
        <button class="btn secondary" type="submit">保存启动密码</button>
      </form>
      <div class="toolbar" style="margin-top:12px">
        <button class="btn secondary" data-action="run-security-check">立即安全检查</button>
      </div>
    `)}
    ${card("配置迁移", "导出、导入和换机恢复", `
      <div class="toolbar">
        <button class="btn secondary" data-action="export-config">导出配置</button>
        <button class="btn secondary" data-action="import-config">导入配置</button>
        <button class="btn ghost" data-action="reset-defaults">恢复默认配置</button>
      </div>
      <input class="hidden" id="import-config-input" type="file" accept="application/json" />
      <div class="hint" style="margin-top:12px">导出文件不包含 Telegram Session、Token 或登录状态。</div>
    `)}
    ${card("代码交接", "导出完整源码给其他开发者或智能体升级", `
      <div class="toolbar">
        <button class="btn secondary" data-action="export-source">导出 APP 源码</button>
      </div>
      <div class="hint" style="margin-top:12px">源码包不包含 node_modules、Telegram Session、运行数据和本机 .env。</div>
    `)}
  `;
}

function securitySwitch(key, title, detail) {
  return `
    <label class="check-row">
      <span><strong>${escapeHtml(title)}</strong><span class="row-meta">${escapeHtml(detail)}</span></span>
      <input type="checkbox" data-action="toggle-security" data-key="${key}" ${state.security[key] ? "checked" : ""} />
    </label>
  `;
}

function runtimeLabel() {
  return { running: "运行中", paused: "暂停", stopped: "停止" }[state.runtime.status] || "停止";
}

function runtimeIcon() {
  return { running: "●", paused: "Ⅱ", stopped: "■" }[state.runtime.status] || "■";
}

function secondsText(value) {
  const seconds = Math.max(0, Number(value) || 0);
  return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

function elapsedText(ms) {
  return secondsText(Math.floor(ms / 1000));
}

function nextTargetName() {
  const active = runnableTargets();
  if (!active.length) return "无";
  return active[(state.runtime.currentTargetIndex + 1) % active.length]?.name || "无";
}

function filterModeLabel(mode) {
  return mode === "discard" ? "整条丢弃" : mode === "log" ? "保留并记录" : "删文字保媒体";
}

function captionModeLabel(mode) {
  return mode === "delete" ? "删除文案" : mode === "replace" ? "替换文案" : mode === "append" ? "追加文案" : "保留原文";
}

function ruleLabel(mode) {
  return {
    default: "默认",
    media_clean: "净采",
    no_ads_text: "无广告",
    comments: "评论",
    main_comments: "主+评",
  }[mode || "default"] || "默认";
}

function startJob() {
  const active = runnableTargets();
  if (!active.length) {
    log("error", "无法启动", "运行队列为空。");
    showToast("请先在采集页加入运行队列");
    return;
  }
  const runnableIndex = active.findIndex((target) => boundSources(target.id).length > 0);
  if (runnableIndex < 0) {
    log("error", "无法启动", "所有目标频道都没有绑定来源频道。");
    showToast("请先给目标绑定来源频道");
    return;
  }
  state.runtime.status = "running";
  state.runtime.startedAt = state.runtime.startedAt || Date.now();
  state.runtime.currentTargetIndex = runnableIndex;
  state.runtime.remainingSeconds = Number(state.settings.runMinutes) * 60;
  log("info", "开始采集", "单线程轮询任务已启动。");
  ensureTick();
  if (!isPackagedAndroid) {
    notifyRuntimeService("start");
  }
}

function pauseJob() {
  if (state.runtime.status !== "running") return;
  state.runtime.status = "paused";
  state.runtime.pausedAt = Date.now();
  log("info", "暂停采集", "当前目标、来源和剩余时间已保存。");
  notifyRuntimeService("pause");
}

function stopJob() {
  state.runtime.status = "stopped";
  state.runtime.startedAt = null;
  state.runtime.currentTargetIndex = 0;
  state.runtime.currentSourceIndex = 0;
  state.runtime.remainingSeconds = 0;
  log("info", "停止采集", "任务已停止，运行状态已清空。");
  notifyRuntimeService("stop");
}

async function notifyRuntimeService(action) {
  try {
    await api("/api/runtime/service", {
      action,
      status: action === "pause" ? "Ⅱ 采集暂停" : action === "stop" ? "■ 采集停止" : "● 后台采集中",
      config: {
        channels: state.channels,
        targets: runnableTargets(),
        bindings: state.bindings,
        settings: state.settings,
        filters: state.filters,
        ads: state.ads,
        sourceRules: state.sourceRules,
        positions: state.positions,
      },
    });
  } catch (error) {
    log("error", "后台服务", error.message);
  }
}

async function rebuildPositionsLatest() {
  if (!confirm("这会把所有已绑定来源设置为从当前最新消息之后开始采集，避免重复采旧内容。继续？")) return;
  const pairs = [];
  for (const target of state.targets) {
    for (const source of boundSources(target.id)) {
      pairs.push({ target, source });
    }
  }
  if (!pairs.length) {
    showToast("暂无绑定来源");
    return;
  }
  let updated = 0;
  for (const pair of pairs) {
    try {
      const result = await api("/api/telegram/latest-message-id", { source: pair.source });
      const latest = Number(result.lastMessageId || 0);
      if (latest > 0) {
        state.positions[`${pair.target.id}:${pair.source.id}`] = latest;
        updated += 1;
      }
    } catch (error) {
      log("error", "重建记忆点失败", `${pair.source.name}：${error.message}`);
    }
  }
  save();
  render();
  log("info", "重建记忆点", `已将 ${updated} 组绑定设置为从当前最新消息之后开始。`);
  showToast(`已重建 ${updated} 组记忆点`);
}

async function checkApiStatus() {
  try {
    const status = await api("/api/telegram/status", {});
    const connected = status.authorized === true;
    if (connected !== state.account.connected) {
      state.account.connected = connected;
      if (connected) state.account.name = (status.user && status.user.name) || "已登录";
    }
    // 处理需要API凭证的状态
    if (status.needCredentials) {
      state.account.status = "需要API凭证";
      log("warn", "需要API凭证", "请在设置页面配置 API ID 和 API Hash");
    }
    // 如果后端有已保存的API凭证，同步到前端
    if (status.apiConfigured && status.api_id > 0) {
      if (!state.account.apiId || state.account.apiId === "0") {
        state.account.apiId = String(status.api_id);
      }
      state.account.apiHashMask = status.apiHashMask || "";
    }
    save();
    if (state.page === "settings" && !isEditingForm()) render();
  } catch (e) {
    // 忽略，可能是后端还没准备好
  }
}

function ensureTick() {
  if (tick) return;
  // 定期检查后端状态
  checkApiStatus();
  tick = setInterval(async () => {
    checkApiStatus();
    if (state.runtime.status !== "running") return;
    state.runtime.remainingSeconds -= 1;
    if (isPackagedAndroid) {
      await syncRuntimeState();
    }
    if (state.runtime.remainingSeconds % Math.max(1, Number(state.settings.collectInterval)) === 0) {
      collectAndSendOnce();
    }
    if (state.runtime.remainingSeconds <= 0) {
      rotateTarget();
    }
    save();
    if (state.page === "console" && !isEditingForm()) render();
  }, 1000);
}

async function syncRuntimeState() {
  try {
    const snapshot = await api("/api/runtime/state", {});
    state.runtime.collected = Number(snapshot.collected || state.runtime.collected || 0);
    state.runtime.sent = Number(snapshot.sent || state.runtime.sent || 0);
    state.runtime.failed = Number(snapshot.failed || state.runtime.failed || 0);
    if (snapshot.positions && typeof snapshot.positions === "object") {
      state.positions = { ...state.positions, ...snapshot.positions };
    }
    const status = snapshot.status || {};
    if (status.eventId && status.eventId !== state.runtime.lastServiceEventId) {
      state.runtime.lastServiceEventId = status.eventId;
      state.runtime.lastResult = status.title || status.status || "";
      state.runtime.lastSourceRule = status.sourceRule || state.runtime.lastSourceRule || "default";
      state.runtime.lastPosition = String(status.position || "");
      log(status.level === "error" ? "error" : "info", status.title || "后台采集", status.detail || "");
    }
  } catch (error) {
    log("error", "后台状态读取失败", error.message);
  }
}

async function collectAndSendOnce() {
  if (collectBusy) return;
  if (state.runtime.cooldownUntil > Date.now()) return;
  const target = currentTarget();
  if (!target) return;
  const sources = boundSources(target.id);
  if (!sources.length) {
    log("info", "队列跳过", `${target.name} 没有来源频道。`);
    rotateTarget();
    return;
  }
  collectBusy = true;
  const source = sources[state.runtime.currentSourceIndex % sources.length];
  const key = `${target.id}:${source.id}`;
  const lastPosition = String(state.positions[key] || "0");
  const lastMessageId = Number(lastPosition.match(/^\d+$/) ? lastPosition : 0);
  try {
    const result = await api("/api/telegram/collect-once", {
      target,
      source,
      lastMessageId,
      lastPosition,
      settings: state.settings,
      filters: state.filters,
      ads: state.ads,
      sourceRule: state.sourceRules[source.id] || {},
    });

    if (result.status === "no_new") {
      const nextSourceIndex = (state.runtime.currentSourceIndex + 1) % sources.length;
      state.runtime.currentSourceIndex = nextSourceIndex;
      state.runtime.lastResult = "暂无新消息";
      log("info", "暂无新消息", `${source.name} 暂无可采集的新消息。`);
      if (nextSourceIndex === 0) {
        log("info", "切换目标", `${target.name} 全部来源暂无内容，执行下一目标。`);
        rotateTarget();
      }
      return;
    }

    const fingerprint = `${target.id}:${source.id}:${result.sourceMessageId}`;
    if (!state.dedup[fingerprint]) {
      state.dedup[fingerprint] = Date.now();
      state.runtime.collected += 1;
      state.runtime.sent += 1;
      state.runtime.todayCollected += 1;
      state.runtime.todaySent += 1;
    }
    state.runtime.lastResult = "已发送";
    state.runtime.lastSourceName = source.name;
    // 随机延迟：发送后冷却
    if (state.settings.sendMode === "random") {
      const min = Math.max(1, Number(state.settings.sendRandomMin) || 30);
      const max = Math.max(min + 1, Number(state.settings.sendRandomMax) || 300);
      const delaySec = min + Math.floor(Math.random() * (max - min + 1));
      state.runtime.cooldownUntil = Date.now() + delaySec * 1000;
    } else {
      const fixed = Math.max(1, Number(state.settings.sendFixedSeconds) || 3);
      state.runtime.cooldownUntil = Date.now() + fixed * 1000;
    }
    state.positions[key] = String(result.position || result.lastMessageId || result.sourceMessageId || lastPosition);
    state.runtime.currentSourceIndex = (state.runtime.currentSourceIndex + 1) % sources.length;
    log("info", "发送成功", `${source.name} -> ${target.name}，消息 ${state.positions[key]}，模式 ${result.mode || "send"}。`);
  } catch (error) {
    state.runtime.failed += 1;
    state.runtime.lastResult = "发送失败: " + error.message;
    state.runtime.lastSourceName = source.name;
    state.runtime.cooldownUntil = Date.now() + 5000; // 失败后5秒重试
    state.runtime.currentSourceIndex = (state.runtime.currentSourceIndex + 1) % sources.length;
    log("error", "发送失败", `${source.name} -> ${target.name}：${error.message}`);
    showToast(error.message);
  } finally {
    collectBusy = false;
    save();
    render();
  }
}

function rotateTarget() {
  const active = runnableTargets();
  if (!active.length) return stopJob();
  state.runtime.currentTargetIndex += 1;
  state.runtime.currentSourceIndex = 0;
  state.runtime.cooldownUntil = 0;
  state.runtime.lastSourceName = "";
  if (state.runtime.currentTargetIndex >= active.length) {
    state.runtime.currentRound += 1;
    state.runtime.currentTargetIndex = 0;
  }
  state.runtime.remainingSeconds = Number(state.settings.runMinutes) * 60;
  log("info", "目标频道切换", `切换到 ${currentTarget()?.name || "下一目标"}。`);
}

// 按键振动反馈（Android）
function tapVibrate() {
  try {
    if (isPackagedAndroid && navigator.vibrate) navigator.vibrate(15);
  } catch (_) {}
}

document.addEventListener("click", (event) => {
  tapVibrate();
  const nav = event.target.closest("[data-page]");
  const action = event.target.closest("[data-action]");
  if (nav) {
    state.page = nav.dataset.page;
    save();
    render();
    return;
  }
  if (!action) return;
  const type = action.dataset.action;
  if (type === "start") startJob();
  if (type === "pause") pauseJob();
  if (type === "stop") stopJob();
  if (type === "set-option") {
    const form = action.closest("form");
    const field = action.dataset.field;
    const value = action.dataset.value || "";
    const input = form?.querySelector(`input[name="${field}"]`);
    if (input) input.value = value;
    action.closest("[data-option-field]")?.querySelectorAll("button").forEach((button) => {
      button.classList.toggle("is-active", button === action);
    });
    return;
  }
  if (type === "set-source-rule") {
    const sourceId = action.dataset.source;
    state.sourceRules[sourceId] = { mode: action.dataset.value || "default" };
    log("info", "来源规则", "来源频道单独采集规则已更新。");
    save();
    if (state.runtime.status === "running") notifyRuntimeService("start");
    render();
    return;
  }
  if (type === "show-log-detail") {
    state.detailLogId = action.dataset.id || "";
    save();
    render();
    return;
  }
  if (type === "hide-log-detail") {
    state.detailLogId = "";
    save();
    render();
    return;
  }
  if (type === "unlock-app") {
    const pin = document.querySelector("#unlock-pin")?.value || "";
    const hash = await sha256(pin);
    if (hash && state.security.pinHash && hash === state.security.pinHash) {
      unlocked = true;
      render();
    } else {
      showToast("启动密码错误");
    }
    return;
  }
  if (type === "biometric-unlock") {
    if (!window.NativeBridge?.authenticateBiometric) {
      showToast("当前安装包不支持指纹解锁");
      return;
    }
    try {
      window.NativeBridge.authenticateBiometric();
    } catch (error) {
      showToast(error.message || "无法启动指纹验证");
    }
    return;
  }
  if (type === "delete-target") deleteTarget(action.dataset.id);
  if (type === "show-channel-detail") {
    state.detailChannelId = action.dataset.id;
    event.stopPropagation();
  }
  if (type === "hide-channel-detail") {
    state.detailChannelId = "";
  }
  if (type === "copy-channel-link") {
    copyChannelLink(action.dataset.id);
    return;
  }
  if (type === "open-target-binding") {
    state.selectedTargetId = action.dataset.id;
  }
  if (type === "back-target-list") {
    state.selectedTargetId = "";
  }
  if (type === "add-target-from-pool") addTargetFromPool(action.dataset.id);
  if (type === "move-target") moveTarget(action.dataset.id, Number(action.dataset.dir));
  if (type === "queue-add-target") {
    addTargetToQueue(action.dataset.id);
    return;
  }
  if (type === "queue-remove-target") {
    removeTargetFromQueue(action.dataset.id);
    return;
  }
  if (type === "queue-single-target") {
    singleTargetQueue(action.dataset.id);
    return;
  }
  if (type === "queue-all-targets") {
    queueAllTargets();
    return;
  }
  if (type === "clear-target-queue") {
    clearTargetQueue();
    return;
  }
  if (type === "delete-ad") deleteAd(action.dataset.id);
  if (type === "filter-logs") state.logFilter = action.dataset.level;
  if (type === "clear-logs" && confirm("清空日志？")) state.logs = [];
  if (type === "toggle-log") { state.runtime.showFullLog = !state.runtime.showFullLog; }
    if (type === "export-config") exportConfig();
  if (type === "export-source") {
    exportSource();
    return;
  }
  if (type === "import-config") {
    document.querySelector("#import-config-input")?.click();
    return;
  }
  if (type === "reset-defaults" && confirm("恢复默认配置？")) state = defaultState();
  if (type === "clear-positions" && confirm("清空全部采集记忆点？")) {
    state.positions = {};
    state.dedup = {};
    log("info", "采集记忆点", "已清空采集记忆点和去重记录。");
    save();
    render();
    return;
  }
  if (type === "rebuild-positions-latest") {
    rebuildPositionsLatest();
    return;
  }
  if (type === "request-code") {
    requestCodeFromForm(action);
    return;
  }
  if (type === "sync-channels") {
    syncTelegramChannels();
    return;
  }
  if (type === "logout") {
    logoutTelegram();
    return;
  }
  if (type === "run-security-check") {
    runSecurityCheck();
    return;
  }
  if (type === "toggle-security") {
    return;
  }
  save();
  render();
});

document.addEventListener("change", (event) => {
  const input = event.target;
  if (input.id === "import-config-input") {
    importConfig(input.files?.[0]);
    input.value = "";
    return;
  }
  let handled = false;
  if (input.dataset.bindSource === "1") {
    const targetId = input.dataset.target;
    const sourceId = input.dataset.source;
    const set = new Set(state.bindings[targetId] || []);
    if (input.checked) set.add(sourceId);
    else set.delete(sourceId);
    state.bindings[targetId] = Array.from(set);
    log("info", "保存绑定", "目标频道来源绑定已更新。");
    handled = true;
  }
  if (input.dataset.sourceRule === "1") {
    state.sourceRules[input.dataset.source] = { mode: input.value };
    log("info", "来源规则", "来源频道单独采集规则已更新。");
    handled = true;
  }
  if (input.dataset.action === "toggle-security") {
    if (input.dataset.key === "biometric" && input.checked && !state.security.pinCode) {
      input.checked = false;
      state.security.biometric = false;
      showToast("请先设置启动密码");
      handled = true;
    } else {
      state.security[input.dataset.key] = input.checked;
      log("info", "安全设置", `${input.dataset.key} 已${input.checked ? "开启" : "关闭"}。`);
      handled = true;
    }
  }
  if (handled) {
    save();
    if (state.runtime.status === "running" && (input.dataset.bindSource === "1" || input.dataset.sourceRule === "1")) {
      notifyRuntimeService("start");
    }
    render();
  }
});

document.addEventListener("input", (event) => {
  const input = event.target;
  const field = input.dataset.accountField;
  if (!["phone", "code", "password"].includes(field)) return;
  state.account[field] = input.value;
  save();
});

document.addEventListener("submit", (event) => {
  event.preventDefault();
  const form = event.target;
  const data = new FormData(form);
  if (form.dataset.form === "add-target") {
    state.targets.push({ id: id("target"), name: String(data.get("name")).trim(), enabled: true, createdAt: Date.now() });
    log("info", "新增目标频道", String(data.get("name")).trim());
  }
  if (form.dataset.form === "add-source") {
    state.channels.push({ id: id("source"), name: String(data.get("name")).trim(), status: "active", createdAt: Date.now() });
    log("info", "新增来源频道", String(data.get("name")).trim());
  }
  if (form.dataset.form === "create-telegram-channel") {
    createTelegramChannel(data);
    return;
  }
  if (form.dataset.form === "save-pin") {
    state.security.pinCode = String(data.get("pinCode") || "").trim();
    if (state.security.pinCode) state.security.appLock = true;
    log("info", "安全设置", state.security.pinCode ? "启动密码已保存。" : "启动密码已清空。");
  }
  if (form.dataset.form === "save-queue-settings") {
    for (const key of ["runMinutes", "collectInterval", "retryCount", "rateLimit"]) {
      state.settings[key] = Math.max(1, Number(data.get(key)) || state.settings[key]);
    }
    if (state.runtime.status !== "running") {
      state.runtime.remainingSeconds = Number(state.settings.runMinutes) * 60;
    }
    if (state.runtime.status === "running") notifyRuntimeService("start");
    log("info", "队列参数", "运行队列参数已保存。");
  }
  if (form.dataset.form === "save-collect-rules") {
    state.settings.firstCollectMode = String(data.get("firstCollectMode") || "latest");
    state.settings.contentMode = String(data.get("contentMode") || "all");
    state.settings.adEnabled = data.get("adEnabled") === "on";
    log("info", "采集规则", "采集规则已保存。");
  }
  if (form.dataset.form === "save-filter-send") {
    state.filters.keywords = String(data.get("keywords") || "")
      .split(/\n+/)
      .map((item) => item.trim())
      .filter(Boolean);
    state.settings.filterMode = String(data.get("filterMode") || "strip_text_keep_media");
    state.settings.captionMode = String(data.get("captionMode") || "keep");
    state.settings.removeSource = data.get("removeSource") === "on";
    state.settings.sendMode = String(data.get("sendMode") || "fixed");
    for (const key of ["sendFixedSeconds", "sendRandomMin", "sendRandomMax"]) {
      state.settings[key] = Math.max(1, Number(data.get(key)) || state.settings[key]);
    }
    log("info", "过滤发送设置", "过滤规则和发送规则已保存。");
  }
  if (form.dataset.form === "add-ad") {
    state.ads.pool.push({
      id: id("ad"),
      name: String(data.get("name")).trim(),
      type: String(data.get("type") || "text"),
      url: String(data.get("url") || "").trim(),
      anchorText: String(data.get("anchorText") || "").trim(),
      content: String(data.get("content")).trim(),
      createdAt: Date.now(),
    });
    log("info", "新增广告", String(data.get("name")).trim());
  }
  if (form.dataset.form === "save-ad-rules") {
    state.ads.mode = String(data.get("mode") || "random");
    state.ads.fixedEvery = Math.max(1, Number(data.get("fixedEvery")) || 10);
    state.ads.randomMin = Math.max(1, Number(data.get("randomMin")) || 8);
    state.ads.randomMax = Math.max(state.ads.randomMin, Number(data.get("randomMax")) || 15);
    state.ads.insertPosition = String(data.get("insertPosition") || "after");
    log("info", "广告规则", "广告规则已保存。");
  }
  if (form.dataset.form === "api-credentials") {
    const apiId = String(data.get("apiId") || "").trim();
    const apiHash = String(data.get("apiHash") || "").trim();
    if (!apiId) {
      showToast("请输入 API ID");
      return;
    }
    // 检查是否误填了 Bot Token 格式（包含冒号）
    if (apiId.includes(":")) {
      showToast("API ID 不能包含冒号，看起来你填入了 Bot Token。请从 my.telegram.org/apps 获取正确的 API ID 和 API Hash");
      return;
    }
    // API ID 必须是纯数字
    const parsedId = parseInt(apiId, 10);
    if (isNaN(parsedId) || parsedId <= 0 || String(parsedId) !== apiId) {
      showToast("API ID 必须是纯数字，请从 my.telegram.org/apps 获取");
      return;
    }
    // API Hash 留空表示保持已保存的值（后端不回传明文）
    if (!apiHash) {
      state.account.apiId = apiId;
      save();
      api("/api/telegram/update-credentials", { api_id: parsedId, api_hash: "" })
        .then((result) => {
          log("info", "API 凭证已保存", "API ID: " + apiId + "（API Hash 保持不变）");
          showToast("API 凭证已保存，正在初始化 TDLib...");
          render();
        })
        .catch((err) => {
          log("error", "API 凭证保存失败", err.message);
          showToast(err.message);
        });
      return;
    }
    // API Hash 常见的格式是32位十六进制
    if (apiHash.length < 16) {
      showToast("API Hash 长度太短（至少16位），请从 my.telegram.org/apps 获取正确的 API Hash");
      return;
    }
    state.account.apiId = apiId;
    state.account.apiHash = apiHash;
    state.account.apiHashMask = "";
    // 先保存到本地状态
    save();
    // 调用后端更新凭证
    api("/api/telegram/update-credentials", { api_id: parsedId, api_hash: apiHash })
      .then(result => {
        log("info", "API 凭证已保存", "API ID: " + apiId);
        showToast("API 凭证已保存，正在初始化 TDLib...");
        render();
      })
      .catch(err => {
        log("error", "API 凭证保存失败", err.message);
        showToast(err.message);
      });
    return;
  }
  if (form.dataset.form === "login") {
    signInTelegram(data);
    return;
  }
  if (!["save-queue-settings", "save-collect-rules", "save-filter-send", "save-ad-rules", "save-pin"].includes(form.dataset.form)) {
    form.reset();
  }
  save();
  render();
});

async function requestCodeFromForm(button) {
  const form = button.closest("form");
  const data = new FormData(form);
  const phoneCode = String(data.get("phoneCode") || "+66").trim();
  const phoneNumber = String(data.get("phone") || state.account.phone || "").trim().replace(/^0+/, "");
  const phone = phoneCode + phoneNumber;
  if (!phoneNumber) {
    showToast("请输入手机号");
    return;
  }
  // 检查API凭证是否已配置
  if (!state.account.apiId || !state.account.apiHash) {
    showToast("请先在「API凭证」区域填写 API ID 和 API Hash（获取：my.telegram.org/apps）");
    return;
  }
  // 禁用按钮防止重复点击
  button.disabled = true;
  button.textContent = "发送中...";
  state.account.phone = phone;
  state.account.status = "正在发送验证码...";
  save();
  render();
  try {
    const result = await api("/api/telegram/request-code", { phone });
    state.account.phone = result.phone || phone;
    state.account.status = result.codeViaApp ? "验证码已发到 Telegram" : "验证码已发送";
    log("info", "验证码已发送", result.codeViaApp ? "验证码已发送到 Telegram App。" : "验证码已发送。");
    save();
    render();
    showToast(result.codeViaApp ? "验证码已发到 Telegram App" : "验证码已发送");
  } catch (error) {
    state.account.phone = phone;
    state.account.status = "验证码失败: " + error.message;
    log("error", "验证码失败", error.message);
    save();
    render();
    showToast(error.message);
  } finally {
    // 恢复按钮状态
    button.disabled = false;
    button.textContent = "获取验证码";
    save();
    render();
  }
}

async function createTelegramChannel(formData) {
  const title = String(formData.get("title") || "").trim();
  const username = String(formData.get("username") || "").trim().replace(/^@/, "");
  const description = String(formData.get("description") || "").trim();
  if (!title) {
    showToast("请输入频道名称");
    return;
  }
  if (username && !/^[a-zA-Z][a-zA-Z0-9_]{4,31}$/.test(username)) {
    showToast("用户名需字母开头，5-32 位字母数字下划线");
    return;
  }
  try {
    showToast("正在新建频道");
    const result = await api("/api/telegram/create-channel", { title, username, description });
    const channel = {
      id: result.id,
      channelId: result.id,
      accessHash: "",
      name: result.name || title,
      username: result.username || username,
      type: "channel",
      canPostMessages: true,
      enabled: true,
      createdAt: Date.now(),
    };
    if (!state.targets.some((item) => (item.channelId || item.id) === channel.id)) {
      state.targets.unshift({ ...channel, id: id("target") });
    }
    if (result.usernameError) {
      log("error", "频道用户名", result.usernameError);
    }
    log("info", "新建频道", `${channel.name}${channel.username ? ` (@${channel.username})` : ""} 已创建并加入目标。`);
    await syncTelegramChannels();
    state.page = "targets";
    save();
    render();
    showToast(result.usernameError ? "频道已创建，用户名未设置" : "频道已创建");
  } catch (error) {
    log("error", "新建频道失败", error.message);
    save();
    render();
    showToast(error.message);
  }
}

async function copyChannelLink(channelId) {
  const channel = findAnyChannel(channelId);
  const link = channelLink(channel);
  if (!link) {
    showToast("这个频道没有公开链接");
    return;
  }
  try {
    await navigator.clipboard.writeText(link);
    showToast("链接已复制");
  } catch {
    showToast(link);
  }
}

async function signInTelegram(formData) {
  const phone = String(formData.get("phone") || state.account.phone || "").trim();
  const code = String(formData.get("code") || state.account.code || "").trim();
  const password = String(formData.get("password") || state.account.password || "").trim();
  if (!phone.startsWith("+")) {
    showToast("手机号格式错误，必须包含国家区号（如 +66812345678）");
    return;
  }
  try {
    state.account.status = "登录中";
    state.account.connected = false;
    save();
    render();
    showToast("正在登录 Telegram");
    const result = await api("/api/telegram/sign-in", { phone, code, password });
    const user = result.user || {};
    state.account.phone = user.phone || phone;
    state.account.code = "";
    state.account.password = "";
    state.account.name = user.username ? `@${user.username}` : user.name || phone;
    state.account.status = result.authorized ? "已登录" : "未登录";
    state.account.connected = !!result.connected;
    log("info", "登录成功", `${state.account.name} 已登录。`);
    save();
    render();
    showToast("登录成功");
    await syncTelegramChannels();
  } catch (error) {
    state.account.status = "登录失败";
    state.account.connected = false;
    log("error", "登录失败", error.message);
    save();
    render();
    showToast(error.message);
  }
}

async function logoutTelegram() {
  try {
    await api("/api/telegram/logout", {});
  } catch {
    // local state is still cleared if server logout fails
  }
  state.account = defaultState().account;
  log("info", "退出登录", "Telegram 登录状态已清空。");
  save();
  render();
}

async function syncTelegramChannels() {
  try {
    showToast("正在同步频道");
    const result = await api("/api/telegram/sync-channels", {});
    const rows = result.channels || [];
    const byId = new Map(rows.map((item) => [String(item.id), item]));
    state.targets = state.targets.map((target) => {
      const chatId = String(target.channelId || target.id);
      const fresh = byId.get(chatId);
      if (!fresh) return target;
      return {
        ...target,
        channelId: fresh.id,
        name: fresh.name || target.name,
        username: fresh.username || "",
        type: fresh.type || target.type || "channel",
        canPostMessages: fresh.canPostMessages !== false,
        status: fresh.status || "active",
        syncedAt: fresh.syncedAt || Date.now(),
      };
    });
    const existingTargetIds = new Set(state.targets.map((item) => String(item.channelId || item.id)));
    state.channels = rows
      .filter((item) => !existingTargetIds.has(String(item.id)))
      .map((item) => ({
        id: item.id,
        accessHash: item.accessHash || "",
        name: item.name,
        username: item.username || "",
        type: item.type || "channel",
        canPostMessages: item.canPostMessages !== false,
        status: item.status || "active",
        syncedAt: item.syncedAt || Date.now(),
      }));
    state.account.channelCount = state.channels.length + state.targets.length;
    state.account.connected = true;
    state.account.status = "已登录";
    log("info", "频道同步", `已同步 ${state.channels.length} 个来源候选频道。`);
    save();
    render();
    showToast("频道同步完成");
  } catch (error) {
    log("error", "频道同步失败", error.message);
    save();
    render();
    showToast(error.message);
  }
}

function deleteTarget(targetId) {
  const removed = state.targets.find((item) => item.id === targetId);
  if (removed && !state.channels.some((item) => item.id === (removed.channelId || removed.id))) {
    state.channels.push({
      id: removed.channelId || removed.id,
      accessHash: removed.accessHash || "",
      name: removed.name,
      username: removed.username || "",
      type: removed.type || "channel",
      canPostMessages: removed.canPostMessages !== false,
      status: "active",
      syncedAt: Date.now(),
    });
    state.channels.sort((a, b) => a.name.localeCompare(b.name, "zh-CN"));
  }
  state.targets = state.targets.filter((item) => item.id !== targetId);
  state.queueTargetIds = state.queueTargetIds.filter((id) => id !== targetId);
  delete state.bindings[targetId];
  if (state.selectedTargetId === targetId) state.selectedTargetId = "";
  log("info", "删除目标频道", "目标频道及绑定关系已删除。");
}

function addTargetFromPool(channelId) {
  const channel = state.channels.find((item) => item.id === channelId);
  if (!channel) return;
  const targetId = id("target");
  state.targets.push({
    id: targetId,
    channelId: channel.id,
    accessHash: channel.accessHash || "",
    name: channel.name,
    username: channel.username || "",
    type: channel.type || "channel",
    canPostMessages: channel.canPostMessages !== false,
    enabled: true,
    createdAt: Date.now(),
  });
  state.channels = state.channels.filter((item) => item.id !== channelId);
  state.bindings[targetId] = [];
  state.selectedTargetId = targetId;
  log("info", "新增目标频道", `${channel.name} 已设为目标频道。`);
}

function moveTarget(targetId, dir) {
  const index = state.targets.findIndex((item) => item.id === targetId);
  const next = index + dir;
  if (index < 0 || next < 0 || next >= state.targets.length) return;
  const [item] = state.targets.splice(index, 1);
  state.targets.splice(next, 0, item);
  log("info", "目标排序", "目标频道排序已更新。");
}

function addTargetToQueue(targetId) {
  const target = state.targets.find((item) => item.id === targetId);
  if (!target) return;
  if (!boundSources(targetId).length) {
    showToast("该目标没有绑定来源");
    log("error", "加入队列失败", `${target.name} 没有绑定来源频道。`);
    return;
  }
  if (!state.queueTargetIds.includes(targetId)) state.queueTargetIds.push(targetId);
  state.runtime.currentTargetIndex = Math.min(state.runtime.currentTargetIndex, Math.max(0, runnableTargets().length - 1));
  if (state.runtime.status === "running") notifyRuntimeService("start");
  log("info", "运行队列", `${target.name} 已加入队列。`);
  save();
  render();
}

function removeTargetFromQueue(targetId) {
  const target = state.targets.find((item) => item.id === targetId);
  state.queueTargetIds = state.queueTargetIds.filter((id) => id !== targetId);
  state.runtime.currentTargetIndex = Math.min(state.runtime.currentTargetIndex, Math.max(0, runnableTargets().length - 1));
  if (state.runtime.status === "running" && !runnableTargets().length) stopJob();
  else if (state.runtime.status === "running") notifyRuntimeService("start");
  log("info", "运行队列", `${target?.name || "目标"} 已移出队列。`);
  save();
  render();
}

function singleTargetQueue(targetId) {
  const target = state.targets.find((item) => item.id === targetId);
  if (!target) return;
  if (!boundSources(targetId).length) {
    showToast("该目标没有绑定来源");
    log("error", "单跑失败", `${target.name} 没有绑定来源频道。`);
    return;
  }
  state.queueTargetIds = [targetId];
  state.runtime.currentTargetIndex = 0;
  state.runtime.currentSourceIndex = 0;
  state.runtime.remainingSeconds = Number(state.settings.runMinutes) * 60;
  if (state.runtime.status === "running") notifyRuntimeService("start");
  log("info", "运行队列", `${target.name} 已设为单跑目标。`);
  save();
  render();
}

function queueAllTargets() {
  const runnable = state.targets.filter((target) => target.enabled !== false && boundSources(target.id).length > 0);
  state.queueTargetIds = runnable.map((target) => target.id);
  state.runtime.currentTargetIndex = 0;
  state.runtime.currentSourceIndex = 0;
  if (state.runtime.status === "running") notifyRuntimeService("start");
  log("info", "运行队列", `已加入 ${state.queueTargetIds.length} 个有来源的目标。`);
  save();
  render();
}

function clearTargetQueue() {
  state.queueTargetIds = [];
  state.runtime.currentTargetIndex = 0;
  state.runtime.currentSourceIndex = 0;
  if (state.runtime.status === "running") stopJob();
  log("info", "运行队列", "运行队列已清空。");
  save();
  render();
}

function deleteAd(adId) {
  state.ads.pool = state.ads.pool.filter((item) => item.id !== adId);
  log("info", "删除广告", "广告已从广告池删除。");
}

async function runSecurityCheck() {
  try {
    const status = await api("/api/telegram/status", {});
    state.security.lastCheckAt = Date.now();
    if (!status.configured) {
      state.security.lastCheckResult = "Telegram API 未配置";
      log("error", "安全检查", "Telegram API 凭证未配置。");
    } else if (!status.authorized) {
      state.security.lastCheckResult = "Telegram 未登录";
      log("error", "安全检查", "Telegram 会话未授权，请重新登录。");
    } else {
      state.security.lastCheckResult = "基础检查正常";
      log("info", "安全检查", "Telegram 配置、连接和登录状态正常。");
    }
  } catch (error) {
    state.security.lastCheckAt = Date.now();
    state.security.lastCheckResult = "检查失败";
    log("error", "安全检查失败", error.message);
  }
  save();
  render();
}

function importConfig(file) {
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const parsed = JSON.parse(String(reader.result || "{}"));
      const data = parsed.data || parsed;
      const account = state.account;
      state = normalize({
        ...state,
        ...data,
        account,
        page: "settings",
        selectedTargetId: "",
        detailChannelId: "",
      });
      log("info", "配置导入", "配置已导入，Telegram 登录状态未被覆盖。");
      save();
      render();
    } catch (error) {
      log("error", "配置导入失败", error.message);
      save();
      render();
      showToast("配置文件无效");
    }
  };
  reader.readAsText(file);
}

function exportConfig() {
  const payload = {
    exportedAt: Date.now(),
    app: "B Collector",
    version: "1.0.0",
    data: {
      channels: state.channels,
      targets: state.targets,
      queueTargetIds: state.queueTargetIds,
      bindings: state.bindings,
      settings: state.settings,
      filters: state.filters,
      ads: state.ads,
      sourceRules: state.sourceRules,
      positions: state.positions,
      dedup: state.dedup,
      security: state.security,
      logs: state.logs,
    },
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `collector_backup_${Date.now()}.json`;
  link.click();
  URL.revokeObjectURL(url);
  log("info", "配置导出", "配置文件已生成，不包含 Telegram Session。");
}

function exportSource() {
  if (isPackagedAndroid) {
    api("/api/export-source", {})
      .then((result) => {
        log("info", "源码导出", `源码包已导出：${result.path}`);
        showToast("源码包已导出");
        save();
        render();
      })
      .catch((error) => {
        log("error", "源码导出失败", error.message);
        showToast(error.message);
      });
    return;
  }
  const link = document.createElement("a");
  link.href = `/api/export-source?t=${Date.now()}`;
  link.download = "";
  document.body.appendChild(link);
  link.click();
  link.remove();
  log("info", "源码导出", "已生成 APP 源码交接包。");
  showToast("开始导出源码");
  save();
  render();
}

window.onNativeBiometricResult = (ok, message) => {
  if (ok) {
    unlocked = true;
    showToast("指纹验证通过");
    render();
    return;
  }
  if (message) showToast(message);
};

window.addEventListener("load", () => {
  setTimeout(() => document.body.classList.add("is-ready"), 3000);
});

log("info", "APP 启动", "B Collector 已启动。");
ensureTick();
save();
render();
