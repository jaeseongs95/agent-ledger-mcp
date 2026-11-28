"use strict";
(() => {
  const status = document.getElementById("navigation-status");
  if (!status || !window.history.pushState) { return; }

  const navLinks = [...document.querySelectorAll("#sidebar .sidebar-nav a[href]")];
  const base = new URL(document.querySelector("#sidebar .brand-link").href);
  const detailPaths = ["specs/detail", "tasks/detail", "criteria/detail", "projects/versions", "codes"];
  const pages = new Set([...navLinks.map((link) => new URL(link.href).pathname),
    ...detailPaths.map((path) => new URL(path, base).pathname)]);
  const detailPrefixes = ["issues/", "projects/versions/"].map((path) => new URL(path, base).pathname);
  let displayed = new URL(window.location.href);
  let nextEntry = 0;
  let currentEntry = nextEntry;
  let pending;
  const scrollPositions = new Map();

  window.history.replaceState({ veridgerEntry: currentEntry }, "", displayed);
  window.history.scrollRestoration = "manual";

  function isPage(url) {
    return url.origin === base.origin && (pages.has(url.pathname)
      || detailPrefixes.some((prefix) => url.pathname.startsWith(prefix)
        && /^[^/]+$/.test(url.pathname.slice(prefix.length))));
  }

  function setBusy(busy) {
    document.getElementById("main").setAttribute("aria-busy", String(busy));
    status.textContent = busy ? status.dataset.loading : "";
  }

  function replacePage(incoming) {
    document.dispatchEvent(new Event("veridger:before-page"));
    document.title = incoming.title;
    document.documentElement.lang = incoming.documentElement.lang;
    for (const selector of ["#main", ".topbar-location", ".global-alerts", ".topbar .logout",
      'meta[name="_csrf"]', 'meta[name="_csrf_header"]']) {
      const previous = document.querySelector(selector);
      const fresh = incoming.querySelector(selector);
      if (previous && fresh) { previous.replaceWith(fresh); }
    }
    const activeHref = incoming.querySelector("#sidebar a[aria-current='page']")?.getAttribute("href");
    navLinks.forEach((link) => {
      if (link.getAttribute("href") === activeHref) { link.setAttribute("aria-current", "page"); }
      else { link.removeAttribute("aria-current"); }
    });
    document.dispatchEvent(new Event("veridger:page"));
  }

  function focusPage(url, position) {
    const main = document.getElementById("main");
    if (!main.contains(document.activeElement)) {
      const heading = main.querySelector("h1") || main;
      heading.setAttribute("tabindex", "-1");
      heading.focus({ preventScroll: true });
    }
    const anchor = url.hash && document.getElementById(decodeFragment(url.hash.slice(1)));
    if (position) { window.scrollTo(...position); }
    else if (anchor) { anchor.scrollIntoView(); }
    else if (!main.querySelector("dialog[open]")) { window.scrollTo(0, 0); }
  }

  function decodeFragment(fragment) {
    try { return decodeURIComponent(fragment); } catch { return fragment; }
  }

  async function navigate(url, { body, form, entry } = {}) {
    pending?.controller.abort();
    const request = { controller: new AbortController(), write: Boolean(body) };
    pending = request;
    scrollPositions.set(currentEntry, [window.scrollX, window.scrollY]);
    setBusy(true);
    try {
      const response = await fetch(url, {
        method: body ? "POST" : "GET",
        body,
        signal: request.controller.signal,
        cache: "no-store",
        headers: { Accept: "text/html" }
      });
      const destination = new URL(response.url || url.href);
      const incoming = new DOMParser().parseFromString(await response.text(), "text/html");
      if (pending !== request) { return; }
      if (destination.origin !== base.origin
          || !incoming.getElementById("navigation-status") || !incoming.getElementById("main")) {
        if (body && !response.redirected) { throw new Error("Expected an admin page or a redirect"); }
        if (entry === undefined) { window.location.assign(destination); }
        else { window.location.replace(destination); }
        return;
      }

      if (body && !response.redirected && destination.pathname !== displayed.pathname) {
        // The POST error template displays the submitted values, but the POST endpoint has no GET route.
        destination.href = displayed.href;
      }
      destination.hash = (body && response.redirected && form.dataset.spaAnchor) || url.hash;
      replacePage(incoming);
      if (entry === undefined && destination.href !== displayed.href) {
        currentEntry = ++nextEntry;
        window.history.pushState({ veridgerEntry: currentEntry }, "", destination);
      } else if (entry !== undefined) {
        currentEntry = entry;
        if (destination.href !== window.location.href) {
          window.history.replaceState({ veridgerEntry: entry }, "", destination);
        }
      }
      displayed = destination;
      focusPage(destination, entry === undefined ? undefined : scrollPositions.get(entry));
    } catch (failure) {
      if (pending !== request || failure.name === "AbortError") { return; }
      if (body) {
        const warning = document.createElement("p");
        warning.className = "alert alert-danger";
        warning.setAttribute("role", "alert");
        warning.textContent = status.dataset.writeFailed;
        form.querySelector("[data-spa-write-error]")?.remove();
        warning.dataset.spaWriteError = "";
        form.prepend(warning);
      } else if (entry === undefined) {
        window.location.assign(url);
      } else {
        window.location.reload();
      }
    } finally {
      if (pending === request) {
        pending = undefined;
        setBusy(false);
      }
    }
  }

  document.addEventListener("click", (event) => {
    if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) {
      return;
    }
    const link = event.target.closest("a[href]");
    if (!link || (link.target && link.target !== "_self") || link.hasAttribute("download")) { return; }
    const url = new URL(link.href, window.location.href);
    if (!isPage(url) || (url.hash && url.pathname === displayed.pathname && url.search === displayed.search)) { return; }
    event.preventDefault();
    if (!pending?.write) { navigate(url); }
  });

  document.addEventListener("submit", (event) => {
    const form = event.target;
    if (event.defaultPrevented || !form.closest("#main")) { return; }
    const button = event.submitter;
    const method = (button?.getAttribute("formmethod") || form.method).toLowerCase();
    const action = new URL(button?.getAttribute("formaction") || form.action, window.location.href);
    const target = button?.getAttribute("formtarget") || form.target;
    const encoding = button?.getAttribute("formenctype") || form.enctype;
    if ((target && target !== "_self") || action.origin !== base.origin
        || !["get", "post"].includes(method) || encoding !== "application/x-www-form-urlencoded"
        || form.querySelector('input[type="file"]') || (method === "get" && !isPage(action))) { return; }
    event.preventDefault();
    if (pending?.write) { return; }
    const fields = new URLSearchParams(new FormData(form, button));
    if (method === "get") {
      action.search = fields.toString();
      navigate(action);
    } else {
      navigate(action, { body: fields, form });
    }
  });

  window.addEventListener("popstate", (event) => {
    const url = new URL(window.location.href);
    if (!isPage(url)) { window.location.reload(); return; }
    let entry = event.state?.veridgerEntry;
    if (entry === undefined) {
      entry = ++nextEntry;
      window.history.replaceState({ veridgerEntry: entry }, "", url);
    }
    if (url.pathname === displayed.pathname && url.search === displayed.search && displayed.hash !== url.hash) {
      scrollPositions.set(currentEntry, [window.scrollX, window.scrollY]);
      currentEntry = entry;
      displayed = url;
      focusPage(url, scrollPositions.get(entry));
      return;
    }
    navigate(url, { entry });
  });
})();
