"use strict";
(() => {
  // 1. Theme switcher (system -> light -> dark)
  const THEME_KEY = "veridger.theme";
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

  document.addEventListener("veridger:before-page", () => setDrawer(false, false));
})();
