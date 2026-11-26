"use strict";
// Non-security UI interactions for Ledger Admin Web (strict CSP: zero inline scripts or handlers).
// Handles: theme switcher, responsive sidebar drawer, clipboard copy with polite toast,
// back navigation, grants tabs/prefill/expiry presets, codes group filter/edit prefill,
// and progressive JSON diff rendering in history.
(() => {
  const THEME_KEY = "pgledger.theme";
  const toastRegion = document.getElementById("ui-toast");

  function showToast(message) {
    if (!toastRegion || !message) { return; }
    const item = document.createElement("div");
    item.className = "toast";
    item.textContent = message;
    toastRegion.appendChild(item);
    window.setTimeout(() => { item.remove(); }, 2800);
  }

  // 1. Theme switcher (system -> light -> dark)
  const themeBtn = document.getElementById("theme-toggle");
  function applyTheme(mode) {
    const root = document.documentElement;
    if (mode === "light" || mode === "dark") {
      root.setAttribute("data-theme", mode);
    } else {
      root.removeAttribute("data-theme");
      mode = "system";
    }
    if (themeBtn) {
      themeBtn.dataset.mode = mode;
      const label = themeBtn.dataset[mode] || mode;
      const textNode = themeBtn.querySelector("[data-theme-text]");
      if (textNode) { textNode.textContent = label; }
      themeBtn.setAttribute("aria-label", (themeBtn.dataset.prefix || "Theme") + ": " + label);
    }
  }
  let storedTheme = "system";
  try { storedTheme = window.localStorage.getItem(THEME_KEY) || "system"; } catch (ignored) { storedTheme = "system"; }
  applyTheme(storedTheme);
  if (themeBtn) {
    themeBtn.addEventListener("click", () => {
      const current = themeBtn.dataset.mode || "system";
      const next = current === "system" ? "light" : (current === "light" ? "dark" : "system");
      try {
        if (next === "system") { window.localStorage.removeItem(THEME_KEY); }
        else { window.localStorage.setItem(THEME_KEY, next); }
      } catch (ignored) { /* storage disabled */ }
      applyTheme(next);
    });
  }

  // 2. Responsive sidebar drawer with keyboard Escape and focus restoration
  const sidebar = document.getElementById("sidebar");
  const backdrop = document.getElementById("sidebar-backdrop");
  const openBtn = document.getElementById("sidebar-open");
  const closeBtn = document.getElementById("sidebar-close");

  function setDrawer(open) {
    if (!sidebar) { return; }
    sidebar.dataset.open = open ? "true" : "false";
    if (backdrop) { backdrop.hidden = !open; }
    if (openBtn) { openBtn.setAttribute("aria-expanded", open ? "true" : "false"); }
    if (open) {
      const firstLink = sidebar.querySelector("a, button");
      if (firstLink) { firstLink.focus(); }
    } else if (openBtn) {
      openBtn.focus();
    }
  }

  if (openBtn && sidebar) {
    openBtn.addEventListener("click", () => setDrawer(sidebar.dataset.open !== "true"));
  }
  if (closeBtn) {
    closeBtn.addEventListener("click", () => setDrawer(false));
  }
  if (backdrop) {
    backdrop.addEventListener("click", () => setDrawer(false));
  }
  document.addEventListener("keydown", (event) => {
    if (event.isComposing || event.keyCode === 229) { return; }
    if (event.key === "Escape" && sidebar && sidebar.dataset.open === "true") {
      setDrawer(false);
    }
  });

  // 3. History back button (error page)
  document.querySelectorAll("[data-go-back]").forEach((btn) => {
    btn.addEventListener("click", () => {
      if (window.history.length > 1) { window.history.back(); }
      else { window.location.href = btn.dataset.fallback || "/"; }
    });
  });

  // 4. One-click clipboard copy ([data-copy] or [data-copy-target])
  document.addEventListener("click", (event) => {
    const btn = event.target.closest("[data-copy], [data-copy-target]");
    if (!btn) { return; }
    let text = btn.dataset.copy;
    if (text === undefined && btn.dataset.copyTarget) {
      const target = document.getElementById(btn.dataset.copyTarget);
      text = target ? target.textContent.trim() : "";
    }
    if (!text) { return; }
    const copiedMsg = btn.dataset.copied || (toastRegion ? toastRegion.dataset.copied : "Copied");
    const failedMsg = btn.dataset.copyFailed || (toastRegion ? toastRegion.dataset.copyFailed : "Copy failed");
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        () => showToast(copiedMsg),
        () => showToast(failedMsg)
      );
    } else {
      showToast(failedMsg);
    }
  });

  // 5. Grants hub: progressive tabs, expiry UTC presets, row-to-form prefill, preview auto-focus
  const tabList = document.querySelector("[data-tabs]");
  if (tabList) {
    const tabs = Array.from(tabList.querySelectorAll("[role='tab']"));
    const panels = tabs.map((t) => document.getElementById(t.getAttribute("aria-controls"))).filter(Boolean);

    function activateTab(tabId, updateHash) {
      tabs.forEach((tab) => {
        const active = tab.id === tabId;
        tab.setAttribute("aria-selected", active ? "true" : "false");
        tab.tabIndex = active ? 0 : -1;
      });
      panels.forEach((panel) => {
        panel.hidden = panel.getAttribute("aria-labelledby") !== tabId;
      });
      if (updateHash && window.history && window.history.replaceState) {
        const activeTab = tabs.find((t) => t.id === tabId);
        if (activeTab && activeTab.dataset.hash) {
          window.history.replaceState(null, "", "#" + activeTab.dataset.hash);
        }
      }
    }

    const defaultTab = tabList.dataset.defaultTab;
    const hash = window.location.hash.replace(/^#/, "");
    const hashTab = hash ? tabs.find((t) => t.dataset.hash === hash) : null;
    const initial = defaultTab
      ? tabs.find((t) => t.id === defaultTab)
      : (hashTab || tabs[0]);
    if (initial) { activateTab(initial.id, false); }

    tabs.forEach((tab, index) => {
      tab.addEventListener("click", () => activateTab(tab.id, true));
      tab.addEventListener("keydown", (event) => {
        if (event.isComposing || event.keyCode === 229) { return; }
        if (event.key === "ArrowRight") {
          event.preventDefault();
          const next = tabs[(index + 1) % tabs.length];
          activateTab(next.id, true);
          next.focus();
        } else if (event.key === "ArrowLeft") {
          event.preventDefault();
          const prev = tabs[(index - 1 + tabs.length) % tabs.length];
          activateTab(prev.id, true);
          prev.focus();
        }
      });
    });

    // Auto-scroll and focus fingerprint preview when present
    const previewCard = document.getElementById("grant-preview-card");
    if (previewCard) {
      previewCard.scrollIntoView({ behavior: "smooth", block: "start" });
      const confirmBox = previewCard.querySelector('input[name="confirmFingerprint"]');
      if (confirmBox) { confirmBox.focus(); }
    }
  }

  // Expiry UTC quick presets
  document.addEventListener("click", (event) => {
    const btn = event.target.closest("[data-expiry-target]");
    if (!btn) { return; }
    const input = document.getElementById(btn.dataset.expiryTarget);
    if (!input) { return; }
    const days = Number(btn.dataset.expiryDays);
    if (!days || isNaN(days)) {
      input.value = "";
    } else {
      const targetDate = new Date(Date.now() + days * 24 * 60 * 60 * 1000);
      input.value = targetDate.toISOString().replace(/\.\d{3}Z$/, "Z");
    }
    input.focus();
  });

  // Row-to-form prefill (grants, tokens, keys, codes) — never auto-submits!
  function setField(id, value) {
    const el = document.getElementById(id);
    if (!el || value === undefined) { return; }
    el.value = value;
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  document.addEventListener("click", (event) => {
    const btn = event.target.closest("[data-prefill-form]");
    if (!btn) { return; }
    const kind = btn.dataset.prefillForm;
    let focusEl = null;

    if (kind === "revoke-grant") {
      setField("revoke-author", btn.dataset.author || "");
      setField("revoke-project", btn.dataset.project || "");
      setField("revoke-scope", btn.dataset.scope || "");
      focusEl = document.getElementById("revoke-author");
    } else if (kind === "grant-author") {
      setField("enrolled-author", btn.dataset.author || "");
      focusEl = document.getElementById("enrolled-author");
    } else if (kind === "revoke-token") {
      setField("token-revoke-id", btn.dataset.tokenId || "");
      focusEl = document.getElementById("token-revoke-id");
    } else if (kind === "revoke-key") {
      setField("key-revoke-author", btn.dataset.author || "");
      setField("key-revoke-fingerprint", btn.dataset.fingerprint || "");
      focusEl = document.getElementById("key-revoke-fingerprint");
    } else if (kind === "edit-code") {
      setField("group", btn.dataset.group || "");
      setField("code", btn.dataset.code || "");
      setField("nameKo", btn.dataset.nameKo || "");
      setField("nameEn", btn.dataset.nameEn || "");
      setField("descriptionKo", btn.dataset.descriptionKo || "");
      setField("descriptionEn", btn.dataset.descriptionEn || "");
      setField("sortOrder", btn.dataset.sortOrder || "");
      setField("state", btn.dataset.state || "ACTIVE");
      setField("direction", btn.dataset.direction || "");
      const codeForm = document.getElementById("code-editor-form");
      if (codeForm) { codeForm.dataset.originalState = btn.dataset.state || "ACTIVE"; }
      focusEl = document.getElementById("nameKo");
    }

    if (focusEl) {
      focusEl.scrollIntoView({ behavior: "smooth", block: "center" });
      focusEl.focus();
      showToast(btn.dataset.toast || "");
    }
  });

  // 6. Common codes: group filter tabs + conditional direction & reactivation emphasis
  const codeFilterBar = document.querySelector("[data-code-filters]");
  if (codeFilterBar) {
    const buttons = Array.from(codeFilterBar.querySelectorAll("[data-filter-group]"));
    const rows = Array.from(document.querySelectorAll("tr[data-code-group]"));
    buttons.forEach((btn) => {
      btn.addEventListener("click", () => {
        const chosen = btn.dataset.filterGroup;
        buttons.forEach((b) => b.setAttribute("aria-pressed", b === btn ? "true" : "false"));
        rows.forEach((row) => {
          row.hidden = chosen !== "ALL" && row.dataset.codeGroup !== chosen;
        });
      });
    });
  }

  const codeGroupSelect = document.getElementById("group");
  const codeDirectionWrap = document.getElementById("direction-field");
  const codeDirectionSelect = document.getElementById("direction");
  const codeStateSelect = document.getElementById("state");
  const codeReactivationWrap = document.getElementById("reactivation-field");
  const codeEditorForm = document.getElementById("code-editor-form");

  function syncCodeFormState() {
    if (codeGroupSelect && codeDirectionWrap) {
      const isRelation = codeGroupSelect.value === "RELATION_KIND";
      codeDirectionWrap.classList.toggle("form-field-muted", !isRelation);
      if (!isRelation && codeDirectionSelect) {
        codeDirectionSelect.value = "";
      }
    }
    if (codeStateSelect && codeReactivationWrap && codeEditorForm) {
      const wasInactive = codeEditorForm.dataset.originalState === "INACTIVE";
      const reactivating = wasInactive && codeStateSelect.value === "ACTIVE";
      codeReactivationWrap.classList.toggle("alert-warning", reactivating);
    }
  }
  if (codeGroupSelect) {
    codeGroupSelect.addEventListener("change", syncCodeFormState);
    if (codeStateSelect) { codeStateSelect.addEventListener("change", syncCodeFormState); }
    syncCodeFormState();
  }

  // 7. History JSON diff progressive enhancement
  document.querySelectorAll("[data-changes-json]").forEach((cell) => {
    const raw = cell.textContent.trim();
    if (!raw || raw === "{}") { return; }
    let parsed = null;
    try { parsed = JSON.parse(raw); } catch (ignored) { return; }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) { return; }
    const keys = Object.keys(parsed);
    if (keys.length === 0) { return; }

    const container = document.createElement("div");
    container.className = "changes-diff";

    keys.slice(0, 4).forEach((key) => {
      const val = parsed[key];
      const chip = document.createElement("span");
      chip.className = "diff-chip";
      const keyEl = document.createElement("strong");
      keyEl.textContent = key + ":";
      chip.appendChild(keyEl);
      const valEl = document.createElement("span");
      if (val && typeof val === "object" && ("from" in val || "to" in val)) {
        valEl.textContent = String(val.from ?? "—") + " → " + String(val.to ?? "—");
      } else if (typeof val === "string") {
        valEl.textContent = val.length > 48 ? val.slice(0, 48) + "…" : val;
      } else {
        const s = JSON.stringify(val);
        valEl.textContent = s.length > 48 ? s.slice(0, 48) + "…" : s;
      }
      chip.appendChild(valEl);
      container.appendChild(chip);
    });

    const details = document.createElement("details");
    details.className = "diff-details";
    const summary = document.createElement("summary");
    summary.textContent = cell.dataset.rawLabel || "Raw JSON";
    const pre = document.createElement("pre");
    pre.className = "diff-pre";
    pre.textContent = JSON.stringify(parsed, null, 2);
    details.appendChild(summary);
    details.appendChild(pre);
    container.appendChild(details);

    cell.textContent = "";
    cell.appendChild(container);
  });
})();
