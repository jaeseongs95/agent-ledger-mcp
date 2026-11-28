"use strict";
// Non-security UI interactions for Ledger Admin Web (strict CSP: zero inline scripts or handlers).
// Handles: theme switcher, responsive sidebar drawer, clipboard copy with polite toast,
// unified native <dialog> modals (Projects, Grants, Codes), Korean IME-safe search & filters,
// grants tabs/scope matrix/expiry presets/key-block auto-fill, codes editor/state modals,
// and progressive JSON diff rendering in history.
(() => {
  const THEME_KEY = "veridger.theme";
  const toastRegion = document.getElementById("ui-toast");

  function showToast(message) {
    if (!toastRegion || !message) { return; }
    const item = document.createElement("div");
    item.className = "toast";
    item.textContent = message;
    toastRegion.appendChild(item);
    window.setTimeout(() => { item.remove(); }, 2800);
  }

  function formatCount(el, visible, total) {
    if (!el) { return; }
    const pattern = el.dataset.countPattern || "Showing {0} of {1}";
    el.textContent = pattern.replace("{0}", String(visible)).replace("{1}", String(total));
  }

  function setField(id, value) {
    const el = document.getElementById(id);
    if (!el || value === undefined) { return; }
    el.value = value;
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function setText(id, value) {
    const el = document.getElementById(id);
    if (!el || value === undefined) { return; }
    el.textContent = value;
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

  // 2. Responsive sidebar drawer
  const sidebar = document.getElementById("sidebar");
  const backdrop = document.getElementById("sidebar-backdrop");
  const openBtn = document.getElementById("sidebar-open");
  const closeBtn = document.getElementById("sidebar-close");
  const mobileDrawer = window.matchMedia("(max-width: 1023px)");
  let drawerBackground = [];

  function setDrawer(open, restoreFocus = true) {
    if (!sidebar || (open && !mobileDrawer.matches) || (sidebar.dataset.open === "true") === open) { return; }
    sidebar.dataset.open = open ? "true" : "false";
    if (backdrop) { backdrop.hidden = !open; }
    if (openBtn) { openBtn.setAttribute("aria-expanded", open ? "true" : "false"); }
    if (open) {
      sidebar.setAttribute("role", "dialog");
      sidebar.setAttribute("aria-modal", "true");
      sidebar.setAttribute("aria-labelledby", "sidebar-name");
      drawerBackground = Array.from(document.body.children).filter((element) =>
        element !== sidebar && element !== backdrop && !element.inert && element.tagName !== "SCRIPT"
      );
      drawerBackground.forEach((element) => { element.inert = true; });
      (sidebar.querySelector('[aria-current="page"]') || closeBtn).focus();
      return;
    }

    sidebar.removeAttribute("role");
    sidebar.removeAttribute("aria-modal");
    sidebar.removeAttribute("aria-labelledby");
    drawerBackground.forEach((element) => { element.inert = false; });
    drawerBackground = [];
    if (restoreFocus && openBtn) {
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
  mobileDrawer.addEventListener("change", () => {
    if (!mobileDrawer.matches) { setDrawer(false, false); }
  });
  sidebar?.addEventListener("keydown", (event) => {
    if (event.isComposing || event.keyCode === 229) { return; }
    if (sidebar.dataset.open !== "true") { return; }
    if (event.key === "Escape") {
      event.preventDefault();
      setDrawer(false);
      return;
    }
    if (event.key === "Tab") {
      const controls = sidebar.querySelectorAll("a[href], button:not([disabled])");
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
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

  // 5. Unified Native <dialog> Modal System
  const dialogTriggerMap = new WeakMap();
  let imeComposing = false;
  document.addEventListener("compositionstart", () => { imeComposing = true; });
  document.addEventListener("compositionend", () => { imeComposing = false; });

  function clearModalStatus(dialog) {
    const modalStatus = dialog.querySelector("[data-modal-status]");
    if (modalStatus) {
      modalStatus.hidden = true;
      modalStatus.textContent = "";
    }
  }

  function openModal(dialog, triggerEl) {
    if (!dialog) { return; }
    clearModalStatus(dialog);
    if (triggerEl) { dialogTriggerMap.set(dialog, triggerEl); }
    if (!dialog.open) {
      if (typeof dialog.showModal === "function") {
        dialog.showModal();
      } else {
        dialog.setAttribute("open", "");
      }
    }
    const focusable = dialog.querySelector(
      '.modal-body input:not([type="hidden"]):not([disabled]):not([readonly]), .modal-body textarea:not([disabled]), .modal-body select:not([disabled]), .modal-footer button[type="submit"]'
    );
    if (focusable) { focusable.focus(); }
  }

  function closeModal(dialog) {
    if (!dialog || !dialog.open) { return; }
    clearModalStatus(dialog);
    if (typeof dialog.close === "function") {
      dialog.close();
    } else {
      dialog.removeAttribute("open");
    }
  }

  document.querySelectorAll("dialog.modal-dialog").forEach((dialog) => {
    // Restore focus to trigger when closed
    dialog.addEventListener("close", () => {
      clearModalStatus(dialog);
      const trigger = dialogTriggerMap.get(dialog);
      if (trigger && typeof trigger.focus === "function" && document.contains(trigger)) {
        trigger.focus();
      }
    });
    // Protect Korean IME composition from closing dialog on Escape
    dialog.addEventListener("cancel", (event) => {
      if (imeComposing) {
        event.preventDefault();
      }
    });
    // Backdrop click closes dialog (only when clicking directly outside content box)
    dialog.addEventListener("click", (event) => {
      if (event.target !== dialog) { return; }
      const rect = dialog.getBoundingClientRect();
      const outside = event.clientX < rect.left || event.clientX > rect.right
        || event.clientY < rect.top || event.clientY > rect.bottom;
      if (outside) { closeModal(dialog); }
    });
  });

  // Close buttons inside modals
  document.addEventListener("click", (event) => {
    const closeEl = event.target.closest("[data-modal-close]");
    if (!closeEl) { return; }
    const dialog = closeEl.closest("dialog");
    if (dialog) { closeModal(dialog); }
  });

  // 6. Expiry UTC quick presets
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

  // 7. Scope Matrix (dirty tracking + presets + undo)
  function syncMatrix(form) {
    if (!form) { return; }
    const initialList = (form.dataset.initialScopes || "").split(",").map((s) => s.trim()).filter(Boolean);
    const initialSet = new Set(initialList);
    const chips = Array.from(form.querySelectorAll("[data-scope-chip]"));
    let added = 0;
    let removed = 0;

    chips.forEach((chip) => {
      const box = chip.querySelector('input[type="checkbox"]');
      if (!box) { return; }
      const token = box.value;
      const wasChecked = initialSet.has(token);
      const isChecked = box.checked;
      chip.classList.toggle("is-checked", isChecked);
      chip.classList.toggle("is-adding", !wasChecked && isChecked);
      chip.classList.toggle("is-removing", wasChecked && !isChecked);
      if (!wasChecked && isChecked) { added += 1; }
      if (wasChecked && !isChecked) { removed += 1; }
    });

    const statusEl = form.querySelector("[data-matrix-status]");
    const submitBtn = form.querySelector("[data-matrix-submit]");
    const resetBtn = form.querySelector("[data-scope-reset]");
    const dirty = added > 0 || removed > 0;

    if (statusEl) {
      statusEl.classList.toggle("is-dirty", dirty);
      statusEl.classList.toggle("muted", !dirty);
      if (!dirty) {
        statusEl.textContent = form.dataset.msgUnchanged || "No changes";
      } else {
        const parts = [];
        if (added > 0) { parts.push((form.dataset.msgAdded || "+{0}").replace("{0}", String(added))); }
        if (removed > 0) { parts.push((form.dataset.msgRemoved || "-{0}").replace("{0}", String(removed))); }
        statusEl.textContent = (form.dataset.msgChanged || "{0}").replace("{0}", parts.join(" · "));
      }
    }
    if (submitBtn) {
      submitBtn.classList.toggle("secondary", !dirty);
    }
    if (resetBtn) {
      resetBtn.hidden = !dirty;
    }
  }

  document.querySelectorAll("[data-scope-matrix]").forEach((form) => {
    syncMatrix(form);
    form.addEventListener("change", () => syncMatrix(form));
  });

  document.addEventListener("click", (event) => {
    const presetBtn = event.target.closest("[data-scope-preset]");
    if (presetBtn) {
      const container = presetBtn.closest("[data-scope-matrix], [data-scope-picker]");
      if (!container) { return; }
      const preset = presetBtn.dataset.scopePreset;
      const boxes = container.querySelectorAll('input[type="checkbox"][name="scopes"]');
      boxes.forEach((box) => {
        if (preset === "all") { box.checked = true; }
        else if (preset === "standard") { box.checked = box.value !== "verify"; }
        else if (preset === "none") { box.checked = false; }
      });
      if (container.hasAttribute("data-scope-matrix")) { syncMatrix(container); }
      return;
    }

    const undoBtn = event.target.closest("[data-scope-reset]");
    if (undoBtn) {
      const form = undoBtn.closest("[data-scope-matrix]");
      if (!form) { return; }
      const initialSet = new Set((form.dataset.initialScopes || "").split(",").map((s) => s.trim()).filter(Boolean));
      form.querySelectorAll('input[type="checkbox"][name="scopes"]').forEach((box) => {
        box.checked = initialSet.has(box.value);
      });
      syncMatrix(form);
    }
  });

  // 8. Common Codes editor conditional state (direction for RELATION_KIND, reactivation warning)
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
      codeReactivationWrap.hidden = !wasInactive;
    }
  }
  if (codeGroupSelect) {
    codeGroupSelect.addEventListener("change", syncCodeFormState);
    if (codeStateSelect) { codeStateSelect.addEventListener("change", syncCodeFormState); }
    syncCodeFormState();
  }

  // Snapshot all project options in #enrolled-project so session-specific filtering can restore them in manual mode
  const enrolledProjectSelect = document.getElementById("enrolled-project");
  const allEnrolledProjectIds = enrolledProjectSelect
    ? Array.from(enrolledProjectSelect.options).map((opt) => opt.value)
    : [];

  // Split host + session ID helpers (Grants add/revoke modals)
  function hostOptionsOf(selectEl) {
    if (!selectEl) { return []; }
    return Array.from(selectEl.options)
      .map((opt) => opt.value)
      .filter((v) => v && v !== "custom");
  }

  function defaultHostOf(selectEl) {
    const opts = hostOptionsOf(selectEl);
    return opts.length > 0 ? opts[0] : "custom";
  }

  function syncHostSelect(prefix) {
    const hostEl = document.getElementById(prefix + "-host");
    const customWrap = document.getElementById(prefix + "-custom-host-wrap");
    const customInput = document.getElementById(prefix + "-custom-host");
    if (!hostEl || !customWrap) { return; }
    const isCustom = hostEl.value === "custom";
    customWrap.hidden = !isCustom;
    if (customInput) {
      customInput.required = isCustom && !hostEl.disabled;
      if (!isCustom) { customInput.value = ""; }
    }
  }

  function populateSplitAuthor(prefix, fullAuthor, locked) {
    const authorHidden = document.getElementById(prefix + "-author");
    const hostEl = document.getElementById(prefix + "-host");
    const customInput = document.getElementById(prefix + "-custom-host");
    const sessionInput = document.getElementById(prefix + "-session-id");
    if (authorHidden) { authorHidden.value = fullAuthor || ""; }
    if (!hostEl || !sessionInput) { return; }
    const knownHosts = new Set(hostOptionsOf(hostEl));
    const fallbackHost = defaultHostOf(hostEl);
    const isLocked = Boolean(locked);
    if (!fullAuthor) {
      hostEl.disabled = false;
      hostEl.value = fallbackHost;
      if (customInput) { customInput.value = ""; customInput.disabled = false; customInput.readOnly = false; }
      sessionInput.value = "";
      sessionInput.disabled = false;
      sessionInput.readOnly = false;
      sessionInput.required = true;
      syncHostSelect(prefix);
      return;
    }
    const colon = fullAuthor.indexOf(":");
    const hostPart = colon > 0 ? fullAuthor.slice(0, colon) : "";
    const sessionPart = colon > 0 ? fullAuthor.slice(colon + 1) : fullAuthor;
    if (knownHosts.has(hostPart)) {
      hostEl.value = hostPart;
      if (customInput) { customInput.value = ""; }
    } else if (hostPart) {
      hostEl.value = "custom";
      if (customInput) { customInput.value = hostPart; }
    } else {
      hostEl.value = fallbackHost;
      if (customInput) { customInput.value = ""; }
    }
    sessionInput.value = sessionPart;
    // When locked (session-card action), disable all split fields so only the hidden combined author field submits.
    hostEl.disabled = isLocked;
    if (customInput) {
      customInput.disabled = isLocked;
      customInput.readOnly = isLocked;
    }
    sessionInput.disabled = isLocked;
    sessionInput.readOnly = isLocked;
    sessionInput.required = !isLocked;
    syncHostSelect(prefix);
  }

  document.querySelectorAll("[data-host-select]").forEach((sel) => {
    const prefix = sel.dataset.hostSelect;
    sel.addEventListener("change", () => syncHostSelect(prefix));
    syncHostSelect(prefix);
  });

  // Auto-split when user pastes "<host>:<session-id>" into the Session ID input
  document.querySelectorAll("[data-session-paste]").forEach((input) => {
    const prefix = input.dataset.sessionPaste;
    input.addEventListener("input", () => {
      if (input.readOnly || input.disabled) { return; }
      const raw = input.value.trim();
      const colon = raw.indexOf(":");
      if (colon <= 0 || colon === raw.length - 1) { return; }
      const hostEl = document.getElementById(prefix + "-host");
      if (!hostEl || hostEl.disabled) { return; }
      const hostPart = raw.slice(0, colon);
      const sessionPart = raw.slice(colon + 1);
      const knownHosts = new Set(hostOptionsOf(hostEl));
      if (knownHosts.has(hostPart)) {
        hostEl.value = hostPart;
        input.value = sessionPart;
        syncHostSelect(prefix);
      }
    });
  });

  // 9. Unified Modal Open Triggers & Context Population (Projects, Grants, Common Codes)
  document.addEventListener("click", (event) => {
    const trigger = event.target.closest("[data-modal-open]");
    if (!trigger) { return; }
    const modalId = trigger.dataset.modalOpen;
    const dialog = modalId ? document.getElementById(modalId) : null;
    if (!dialog) { return; }

    // Reset plain add form when requested
    if (trigger.dataset.modalReset === "true") {
      const form = dialog.querySelector("form");
      if (form) { form.reset(); }
    }

    if (modalId === "modal-project-rename") {
      setField("rename-project", trigger.dataset.project);
      setField("rename-project-version", trigger.dataset.version);
      setField("rename-project-name", trigger.dataset.name);
      const error = dialog.querySelector("[data-project-rename-error]");
      if (error) { error.hidden = true; }
    }

    // Grants: Edit project scopes matrix modal
    if (modalId === "modal-edit-scopes") {
      const form = document.getElementById("edit-scopes-form");
      const author = trigger.dataset.author || "";
      const project = trigger.dataset.project || "";
      const scopesCsv = trigger.dataset.scopes || "";
      const scopeSet = new Set(scopesCsv.split(",").map((s) => s.trim()).filter(Boolean));
      setField("edit-scopes-author", author);
      setField("edit-scopes-project", project);
      setText("edit-scopes-author-label", author);
      setText("edit-scopes-project-label", project);
      setField("edit-scopes-expires", trigger.dataset.expires || "");
      setField("edit-scopes-note", trigger.dataset.note || "");
      if (form) {
        form.dataset.initialScopes = scopesCsv;
        form.querySelectorAll('input[type="checkbox"][name="scopes"]').forEach((box) => {
          box.checked = scopeSet.has(box.value);
        });
        syncMatrix(form);
      }
    }

    // Grants: Add project permissions / Manual grant modal
    if (modalId === "modal-add-project") {
      const mode = trigger.dataset.grantMode || "manual";
      const titleEl = document.getElementById("modal-add-project-title");
      const form = document.getElementById("add-project-form");
      if (titleEl) {
        titleEl.textContent = mode === "session"
          ? (titleEl.dataset.titleSession || titleEl.textContent)
          : (titleEl.dataset.titleManual || titleEl.textContent);
      }
      if (form) { form.reset(); }
      if (mode === "session") {
        populateSplitAuthor("enrolled", trigger.dataset.author || "", true);
      } else {
        populateSplitAuthor("enrolled", "", false);
      }
      if (enrolledProjectSelect) {
        const allowed = mode === "session" && trigger.dataset.availableProjects
          ? trigger.dataset.availableProjects.split(",").map((s) => s.trim()).filter(Boolean)
          : allEnrolledProjectIds;
        enrolledProjectSelect.textContent = "";
        allowed.forEach((projId) => {
          const opt = document.createElement("option");
          opt.value = projId;
          opt.textContent = projId;
          enrolledProjectSelect.appendChild(opt);
        });
      }
      if (form) {
        form.querySelectorAll('input[type="checkbox"][name="scopes"]').forEach((box) => {
          box.checked = box.value !== "verify";
        });
      }
    }

    // Grants: Revoke permissions modal (session / project / manual)
    if (modalId === "modal-revoke-grant") {
      const mode = trigger.dataset.revokeMode || "manual";
      const helpEl = document.getElementById("modal-revoke-grant-help");
      const summaryBox = document.getElementById("revoke-grant-summary");
      const fieldsWrap = document.getElementById("revoke-grant-fields");
      const author = trigger.dataset.author || "";
      const project = trigger.dataset.project || "";

      if (helpEl) {
        if (mode === "session") { helpEl.textContent = helpEl.dataset.helpSession || helpEl.textContent; }
        else if (mode === "project") { helpEl.textContent = helpEl.dataset.helpProject || helpEl.textContent; }
        else { helpEl.textContent = helpEl.dataset.helpManual || helpEl.textContent; }
      }
      const isTargeted = mode === "session" || mode === "project";
      populateSplitAuthor("revoke", isTargeted ? author : "", isTargeted);
      setField("revoke-project", mode === "project" ? project : "");
      setField("revoke-scope", "");

      if (summaryBox) { summaryBox.hidden = !isTargeted; }
      if (fieldsWrap) { fieldsWrap.hidden = isTargeted; }
      if (isTargeted) {
        setText("revoke-summary-author", author);
        const projLabel = document.getElementById("revoke-summary-project");
        if (projLabel) {
          projLabel.textContent = mode === "project" ? project : (projLabel.dataset.allLabel || "All projects");
        }
      }
    }

    // Codes: Add / Edit modal
    if (modalId === "modal-code-editor") {
      const action = trigger.dataset.codeAction || "add";
      const titleEl = document.getElementById("modal-code-editor-title");
      const confirmBox = document.getElementById("confirmReactivation");
      if (titleEl) {
        titleEl.textContent = action === "edit"
          ? (titleEl.dataset.titleEdit || titleEl.textContent)
          : (titleEl.dataset.titleAdd || titleEl.textContent);
      }
      if (action === "add") {
        if (codeEditorForm) {
          codeEditorForm.reset();
          codeEditorForm.dataset.originalState = "ACTIVE";
        }
        setField("state", "ACTIVE");
        setField("direction", "");
        if (confirmBox) { confirmBox.checked = false; }
      } else {
        setField("group", trigger.dataset.group || "");
        setField("code", trigger.dataset.code || "");
        setField("nameKo", trigger.dataset.nameKo || "");
        setField("nameEn", trigger.dataset.nameEn || "");
        setField("descriptionKo", trigger.dataset.descriptionKo || "");
        setField("descriptionEn", trigger.dataset.descriptionEn || "");
        setField("sortOrder", trigger.dataset.sortOrder || "");
        setField("state", trigger.dataset.state || "ACTIVE");
        setField("direction", trigger.dataset.direction || "");
        if (codeEditorForm) { codeEditorForm.dataset.originalState = trigger.dataset.state || "ACTIVE"; }
        if (confirmBox) { confirmBox.checked = false; }
      }
      syncCodeFormState();
    }

    // Codes: Deactivate / Reactivate quick confirmation modal
    if (modalId === "modal-code-state") {
      const action = trigger.dataset.codeStateAction || "deactivate";
      const isDeactivate = action === "deactivate";
      const titleEl = document.getElementById("modal-code-state-title");
      const descEl = document.getElementById("modal-code-state-desc");
      const submitEl = document.getElementById("code-state-submit");
      const summaryBox = document.getElementById("code-state-summary");
      const reactivateWrap = document.getElementById("code-state-reactivate-wrap");
      const reactivateCheck = document.getElementById("code-state-confirm-reactivation");

      dialog.classList.toggle("modal-dialog-danger", isDeactivate);
      if (summaryBox) { summaryBox.classList.toggle("is-danger", isDeactivate); }
      if (titleEl) {
        titleEl.textContent = isDeactivate
          ? (titleEl.dataset.titleDeactivate || titleEl.textContent)
          : (titleEl.dataset.titleReactivate || titleEl.textContent);
      }
      if (descEl) {
        descEl.textContent = isDeactivate
          ? (descEl.dataset.descDeactivate || descEl.textContent)
          : (descEl.dataset.descReactivate || descEl.textContent);
      }
      if (submitEl) {
        submitEl.textContent = isDeactivate
          ? (submitEl.dataset.labelDeactivate || submitEl.textContent)
          : (submitEl.dataset.labelReactivate || submitEl.textContent);
        submitEl.classList.toggle("danger", isDeactivate);
      }
      setField("code-state-group", trigger.dataset.group || "");
      setField("code-state-code", trigger.dataset.code || "");
      setField("code-state-nameKo", trigger.dataset.nameKo || "");
      setField("code-state-nameEn", trigger.dataset.nameEn || "");
      setField("code-state-descriptionKo", trigger.dataset.descriptionKo || "");
      setField("code-state-descriptionEn", trigger.dataset.descriptionEn || "");
      setField("code-state-sortOrder", trigger.dataset.sortOrder || "");
      setField("code-state-direction", trigger.dataset.direction || "");
      setField("code-state-target", isDeactivate ? "INACTIVE" : "ACTIVE");

      setText("code-state-summary-group", trigger.dataset.groupLabel || trigger.dataset.group || "—");
      setText("code-state-summary-code", trigger.dataset.code || "—");
      setText("code-state-summary-name", trigger.dataset.displayName || trigger.dataset.nameKo || "—");

      if (reactivateWrap) { reactivateWrap.hidden = isDeactivate; }
      if (reactivateCheck) {
        reactivateCheck.checked = false;
        reactivateCheck.required = !isDeactivate;
      }
    }

    openModal(dialog, trigger);
  });

  // Auto-open any modal flagged with data-open-on-load="true" (e.g., 4xx recovery or fingerprint preview)
  document.querySelectorAll('dialog.modal-dialog[data-open-on-load="true"]').forEach((dialog) => {
    openModal(dialog, null);
  });

  // 10. Generic Table Search (Projects) — Korean IME-safe
  document.querySelectorAll("[data-table-search]").forEach((input) => {
    const targetId = input.dataset.tableSearch;
    const tbody = targetId ? document.getElementById(targetId) : null;
    if (!tbody) { return; }
    const rows = Array.from(tbody.querySelectorAll("[data-search-item]"));
    const wrap = document.querySelector('[data-table-wrap="' + targetId + '"]');
    const countEl = document.querySelector('[data-search-count="' + targetId + '"]');
    const emptyEl = document.getElementById(targetId + "-empty");
    const resetBtn = document.querySelector('[data-search-reset="' + targetId + '"]');
    let composing = false;

    function applyTableSearch() {
      const q = input.value.trim().toLowerCase();
      let visible = 0;
      rows.forEach((row) => {
        const text = (row.dataset.searchText || row.textContent || "").toLowerCase();
        const show = !q || text.includes(q);
        row.hidden = !show;
        if (show) { visible += 1; }
      });
      formatCount(countEl, visible, rows.length);
      if (wrap) { wrap.hidden = visible === 0 && rows.length > 0; }
      if (emptyEl) { emptyEl.hidden = visible > 0 || rows.length === 0; }
    }

    input.addEventListener("compositionstart", () => { composing = true; });
    input.addEventListener("compositionend", () => { composing = false; applyTableSearch(); });
    input.addEventListener("input", () => { if (!composing) { applyTableSearch(); } });
    if (resetBtn) {
      resetBtn.addEventListener("click", () => {
        input.value = "";
        applyTableSearch();
        input.focus();
      });
    }
  });

  // 11. Grants Search Toolbar — Korean IME-safe
  const sessionToolbar = document.querySelector("[data-session-toolbar]");
  if (sessionToolbar) {
    const projectInput = document.getElementById("grant-project-filter");
    const searchInput = document.getElementById("session-search");
    const hostSelect = document.getElementById("grant-host-filter");
    const scopeSelect = document.getElementById("grant-scope-filter");
    const cards = Array.from(document.querySelectorAll("[data-session-card]"), (element) => ({
      element,
      projects: Array.from(element.querySelectorAll("[data-project-grant]"))
    }));
    const emptyNotice = document.getElementById("session-filter-empty");
    const resetBtn = sessionToolbar.querySelector("[data-session-filter-reset]");
    const countEl = document.getElementById("session-filter-count");

    function applySessionFilters() {
      const project = projectInput.value.trim().toLowerCase();
      const session = searchInput.value.trim().toLowerCase();
      const host = hostSelect.value;
      const scope = scopeSelect.value;
      let visible = 0;
      cards.forEach(({ element, projects }) => {
        let matchingProject = false;
        projects.forEach((row) => {
          const matches = row.dataset.projectName.toLowerCase().includes(project)
            && (!scope || row.dataset.scopes.split(",").includes(scope));
          row.hidden = !matches;
          matchingProject ||= matches;
        });
        const show = matchingProject && (!host || element.dataset.host === host)
          && element.dataset.sessionId.toLowerCase().includes(session);
        element.hidden = !show;
        if (show) { visible += 1; }
      });
      formatCount(countEl, visible, cards.length);
      if (emptyNotice) { emptyNotice.hidden = visible > 0 || cards.length === 0; }
    }

    [projectInput, searchInput].forEach((input) => {
      input.addEventListener("compositionend", applySessionFilters);
      input.addEventListener("input", (event) => { if (!event.isComposing) { applySessionFilters(); } });
    });
    [hostSelect, scopeSelect].forEach((select) => select.addEventListener("change", applySessionFilters));
    resetBtn.addEventListener("click", () => {
      [projectInput, searchInput, hostSelect, scopeSelect].forEach((input) => { input.value = ""; });
      applySessionFilters();
      projectInput.focus();
    });
    applySessionFilters();
  }

  // 12. Common Codes Search + Group + State Filter — Korean IME-safe
  const codeToolbar = document.querySelector("[data-code-toolbar]");
  if (codeToolbar) {
    const searchInput = document.getElementById("code-search");
    const groupButtons = Array.from(codeToolbar.querySelectorAll("[data-filter-group]"));
    const stateButtons = Array.from(codeToolbar.querySelectorAll("[data-filter-state]"));
    const rows = Array.from(document.querySelectorAll("tr[data-code-row]"));
    const tableWrap = document.getElementById("codes-table-wrap");
    const emptyNotice = document.getElementById("code-filter-empty");
    const resetBtn = document.getElementById("code-filter-reset");
    const countEl = document.getElementById("code-filter-count");
    let activeGroup = "ALL";
    let activeState = "ALL";
    let composing = false;

    function applyCodeFilters() {
      const q = (searchInput ? searchInput.value : "").trim().toLowerCase();
      let visible = 0;
      rows.forEach((row) => {
        const text = (row.dataset.searchText || row.textContent || "").toLowerCase();
        const matchesQuery = !q || text.includes(q);
        const matchesGroup = activeGroup === "ALL" || row.dataset.codeGroup === activeGroup;
        const matchesState = activeState === "ALL" || row.dataset.codeState === activeState;
        const show = matchesQuery && matchesGroup && matchesState;
        row.hidden = !show;
        if (show) { visible += 1; }
      });
      formatCount(countEl, visible, rows.length);
      if (tableWrap) { tableWrap.hidden = visible === 0 && rows.length > 0; }
      if (emptyNotice) { emptyNotice.hidden = visible > 0 || rows.length === 0; }
    }

    if (searchInput) {
      searchInput.addEventListener("compositionstart", () => { composing = true; });
      searchInput.addEventListener("compositionend", () => { composing = false; applyCodeFilters(); });
      searchInput.addEventListener("input", () => { if (!composing) { applyCodeFilters(); } });
    }
    groupButtons.forEach((btn) => {
      btn.addEventListener("click", () => {
        activeGroup = btn.dataset.filterGroup || "ALL";
        groupButtons.forEach((b) => b.setAttribute("aria-pressed", b === btn ? "true" : "false"));
        applyCodeFilters();
      });
    });
    stateButtons.forEach((btn) => {
      btn.addEventListener("click", () => {
        activeState = btn.dataset.filterState || "ALL";
        stateButtons.forEach((b) => b.setAttribute("aria-pressed", b === btn ? "true" : "false"));
        applyCodeFilters();
      });
    });
    if (resetBtn) {
      resetBtn.addEventListener("click", () => {
        if (searchInput) { searchInput.value = ""; }
        activeGroup = "ALL";
        activeState = "ALL";
        groupButtons.forEach((b) => b.setAttribute("aria-pressed", b.dataset.filterGroup === "ALL" ? "true" : "false"));
        stateButtons.forEach((b) => b.setAttribute("aria-pressed", b.dataset.filterState === "ALL" ? "true" : "false"));
        applyCodeFilters();
        if (searchInput) { searchInput.focus(); }
      });
    }
  }

  // 13. History JSON diff progressive enhancement + modal JSON viewer
  const historyJsonDialog = document.getElementById("modal-history-json");
  const historyJsonPre = document.getElementById("history-json-pre");
  const historyJsonMeta = document.getElementById("history-json-meta");
  const historyJsonCopy = document.getElementById("history-json-copy");

  function formatDiffScalar(value) {
    if (value === null || value === undefined) { return "—"; }
    if (typeof value === "string") {
      return value.length > 44 ? value.slice(0, 44) + "…" : value;
    }
    const serialized = JSON.stringify(value);
    return serialized.length > 44 ? serialized.slice(0, 44) + "…" : serialized;
  }

  function openHistoryJsonModal(trigger, formattedJson, rowMeta) {
    if (!historyJsonDialog || !historyJsonPre) { return; }
    historyJsonPre.textContent = formattedJson;
    if (historyJsonMeta) {
      historyJsonMeta.textContent = rowMeta || "";
      historyJsonMeta.hidden = !rowMeta;
    }
    if (historyJsonCopy) {
      historyJsonCopy.dataset.copy = formattedJson;
    }
    openModal(historyJsonDialog, trigger);
    const closeBtn = historyJsonDialog.querySelector("[data-modal-close]");
    if (closeBtn) { closeBtn.focus(); }
  }

  document.querySelectorAll("[data-changes-json]").forEach((cell) => {
    const raw = cell.textContent.trim();
    if (!raw || raw === "{}") {
      const noneLabel = cell.dataset.noneLabel || "—";
      cell.textContent = "";
      const muted = document.createElement("span");
      muted.className = "muted";
      muted.textContent = noneLabel;
      cell.appendChild(muted);
      return;
    }
    let parsed = null;
    try { parsed = JSON.parse(raw); } catch (ignored) { return; }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) { return; }
    const keys = Object.keys(parsed);
    if (keys.length === 0) {
      const noneLabel = cell.dataset.noneLabel || "—";
      cell.textContent = "";
      const muted = document.createElement("span");
      muted.className = "muted";
      muted.textContent = noneLabel;
      cell.appendChild(muted);
      return;
    }

    const formatted = JSON.stringify(parsed, null, 2);
    const container = document.createElement("div");
    container.className = "changes-summary";

    const list = document.createElement("div");
    list.className = "changes-list";

    const maxPreview = 1;
    keys.slice(0, maxPreview).forEach((key) => {
      const val = parsed[key];
      const line = document.createElement("div");
      line.className = "diff-line";
      const keyEl = document.createElement("span");
      keyEl.className = "diff-key";
      keyEl.textContent = key;
      line.appendChild(keyEl);

      const valEl = document.createElement("span");
      valEl.className = "diff-val";
      if (Array.isArray(val) && val.length === 2) {
        const before = val[0];
        const after = val[1];
        if (before === null || before === undefined) {
          valEl.textContent = formatDiffScalar(after);
        } else {
          valEl.textContent = formatDiffScalar(before) + " → " + formatDiffScalar(after);
        }
      } else if (val && typeof val === "object" && ("from" in val || "to" in val)) {
        valEl.textContent = formatDiffScalar(val.from) + " → " + formatDiffScalar(val.to);
      } else {
        valEl.textContent = formatDiffScalar(val);
      }
      line.appendChild(valEl);
      list.appendChild(line);
    });
    container.appendChild(list);

    const actions = document.createElement("div");
    actions.className = "changes-actions";
    if (keys.length > maxPreview) {
      const more = document.createElement("span");
      more.className = "diff-more";
      const morePattern = cell.dataset.morePattern || "+{0} more";
      more.textContent = morePattern.replace("{0}", String(keys.length - maxPreview));
      actions.appendChild(more);
    }

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "secondary btn-sm";
    btn.setAttribute("data-open-history-json", "");
    btn.textContent = cell.dataset.rawLabel || "Raw JSON";
    const rowMeta = [cell.dataset.rowId, cell.dataset.rowTarget, cell.dataset.rowOp, cell.dataset.rowAuthor]
      .filter(Boolean).join(" · ");
    btn.addEventListener("click", () => {
      openHistoryJsonModal(btn, formatted, rowMeta);
    });
    actions.appendChild(btn);
    container.appendChild(actions);

    cell.textContent = "";
    cell.appendChild(container);
  });
  document.querySelectorAll("select[data-reset-filter]").forEach((source) => {
    const dependent = document.getElementById(source.dataset.resetFilter);
    if (!dependent) return;
    const renderedValue = source.value;
    source.addEventListener("change", () => {
      dependent.value = "";
      dependent.disabled = source.value !== renderedValue;
    });
  });
})();
