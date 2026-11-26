"use strict";
// The passkey ceremonies of the admin pages, with the browser's WebAuthn API and the endpoints of Spring Security's WebAuthn
// support (/webauthn/authenticate/options, /login/webauthn, /webauthn/register/options, /webauthn/register) and of this
// application (/approval/intent). Every byte string travels as base64url. The script is a file of the application: the content
// security policy forbids inline script.
(() => {
  const b64 = {
    encode(buffer) {
      let text = "";
      new Uint8Array(buffer).forEach((byte) => { text += String.fromCharCode(byte); });
      return window.btoa(text).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
    },
    decode(value) {
      const text = window.atob(value.replace(/-/g, "+").replace(/_/g, "/"));
      const bytes = new Uint8Array(text.length);
      for (let i = 0; i < text.length; i++) { bytes[i] = text.charCodeAt(i); }
      return bytes.buffer;
    }
  };

  const meta = (name) => {
    const element = document.querySelector('meta[name="' + name + '"]');
    return element ? element.content : null;
  };
  const status = document.getElementById("passkey-status");

  function modalStatusFor(form) {
    if (!form) { return null; }
    const dialog = form.closest("dialog");
    return dialog ? dialog.querySelector("[data-modal-status]") : null;
  }

  function clearStatus(contextForm) {
    const modalStatus = modalStatusFor(contextForm);
    if (modalStatus) {
      modalStatus.hidden = true;
      modalStatus.textContent = "";
    }
    if (!status) { return; }
    status.hidden = true;
    status.textContent = "";
  }

  function show(message, contextForm) {
    const resolved = message || (status ? status.dataset.failed : "Failed");
    const modalStatus = modalStatusFor(contextForm);
    if (modalStatus) {
      modalStatus.textContent = resolved;
      modalStatus.hidden = false;
      modalStatus.scrollIntoView({ behavior: "smooth", block: "nearest" });
      modalStatus.focus();
      return;
    }
    if (!status) { return; }
    status.textContent = resolved;
    status.hidden = false;
    status.scrollIntoView({ behavior: "smooth", block: "nearest" });
    status.focus();
  }

  function friendlyMessage(failure) {
    if (failure && (failure.name === "NotAllowedError" || failure.name === "AbortError")) {
      return (status && status.dataset.cancelled) || (status && status.dataset.failed) || "Cancelled";
    }
    if (failure && failure.message) {
      return failure.message;
    }
    return status ? status.dataset.failed : null;
  }

  async function post(url, body) {
    const headers = { "Content-Type": "application/json", "Accept": "application/json" };
    if (meta("_csrf")) { headers[meta("_csrf_header")] = meta("_csrf"); }
    const response = await fetch(url, {
      method: "POST",
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "same-origin"
    });
    if (!response.ok) {
      let detail = null;
      try { detail = (await response.json()).detail; } catch (ignored) { detail = null; }
      throw new Error(detail || "HTTP " + response.status);
    }
    return response.json();
  }

  const descriptors = (list) => (list || []).map((entry) => ({ ...entry, id: b64.decode(entry.id) }));

  async function signIn() {
    const options = await post("/webauthn/authenticate/options");
    const credential = await navigator.credentials.get({
      publicKey: {
        ...options,
        challenge: b64.decode(options.challenge),
        allowCredentials: descriptors(options.allowCredentials)
      }
    });
    const response = credential.response;
    const result = await post("/login/webauthn", {
      id: credential.id,
      rawId: b64.encode(credential.rawId),
      response: {
        authenticatorData: b64.encode(response.authenticatorData),
        clientDataJSON: b64.encode(response.clientDataJSON),
        signature: b64.encode(response.signature),
        userHandle: response.userHandle ? b64.encode(response.userHandle) : undefined
      },
      credType: credential.type,
      clientExtensionResults: credential.getClientExtensionResults(),
      authenticatorAttachment: credential.authenticatorAttachment
    });
    if (!(result && result.authenticated && result.redirectUrl)) { throw new Error(); }
    window.location.href = result.redirectUrl;
  }

  async function register(label) {
    const options = await post("/webauthn/register/options");
    const credential = await navigator.credentials.create({
      publicKey: {
        ...options,
        user: { ...options.user, id: b64.decode(options.user.id) },
        challenge: b64.decode(options.challenge),
        excludeCredentials: descriptors(options.excludeCredentials)
      }
    });
    const response = credential.response;
    const result = await post("/webauthn/register", {
      publicKey: {
        credential: {
          id: credential.id,
          rawId: b64.encode(credential.rawId),
          response: {
            attestationObject: b64.encode(response.attestationObject),
            clientDataJSON: b64.encode(response.clientDataJSON),
            transports: response.getTransports ? response.getTransports() : []
          },
          type: credential.type,
          clientExtensionResults: credential.getClientExtensionResults(),
          authenticatorAttachment: credential.authenticatorAttachment
        },
        label
      }
    });
    if (!(result && result.success)) { throw new Error(); }
    window.location.href = "/passkeys?success";
  }

  async function approve(form) {
    const fields = [];
    new FormData(form).forEach((value, name) => {
      if (name !== "_csrf" && name !== "approval") { fields.push({ name, value: String(value) }); }
    });
    const options = await post("/approval/intent", {
      method: "POST",
      path: new URL(form.action, window.location.href).pathname,
      fields
    });
    const credential = await navigator.credentials.get({
      publicKey: {
        challenge: b64.decode(options.challenge),
        rpId: options.rpId,
        allowCredentials: options.allowCredentials.map((id) => ({ type: "public-key", id: b64.decode(id) })),
        userVerification: options.userVerification,
        timeout: options.timeout
      }
    });
    const response = credential.response;
    let field = form.querySelector('input[name="approval"]');
    if (!field) {
      field = document.createElement("input");
      field.type = "hidden";
      field.name = "approval";
      form.appendChild(field);
    }
    field.value = JSON.stringify({
      credentialId: b64.encode(credential.rawId),
      authenticatorData: b64.encode(response.authenticatorData),
      clientDataJson: b64.encode(response.clientDataJSON),
      signature: b64.encode(response.signature)
    });
  }

  async function withBusy(button, task, busyLabel, contextForm) {
    if (button && button.dataset.busy === "true") { return; }
    clearStatus(contextForm);
    const originalText = button ? button.textContent : null;
    if (button) {
      button.dataset.busy = "true";
      button.disabled = true;
      button.setAttribute("aria-busy", "true");
      if (busyLabel) { button.textContent = busyLabel; }
    }
    try {
      await task();
    } catch (failure) {
      console.error(failure);
      show(friendlyMessage(failure), contextForm);
      if (button) {
        button.dataset.busy = "false";
        button.disabled = false;
        button.removeAttribute("aria-busy");
        if (originalText !== null) { button.textContent = originalText; }
      }
    }
  }

  const signInButton = document.getElementById("passkey-signin");
  const registerButton = document.getElementById("passkey-register");
  const approveForms = document.querySelectorAll("form[data-approve]");
  const needsPasskey = Boolean(signInButton || registerButton || approveForms.length > 0);

  if (!window.PublicKeyCredential) {
    if (needsPasskey) {
      show(status ? (status.dataset.unsupported || status.dataset.failed) : null);
    }
    return;
  }

  const busyText = status ? status.dataset.busy : null;
  const submittingText = status ? status.dataset.submitting : null;

  if (signInButton) {
    signInButton.addEventListener("click", () => withBusy(signInButton, signIn, busyText));
  }
  if (registerButton) {
    registerButton.addEventListener("click", () => withBusy(registerButton, () => {
      const labelInput = document.getElementById("passkey-label");
      return register(labelInput ? labelInput.value : "");
    }, busyText));
  }
  approveForms.forEach((form) => {
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      const submitBtn = event.submitter || form.querySelector('button[type="submit"]');
      withBusy(submitBtn, async () => {
        await approve(form);
        if (submitBtn && submittingText) { submitBtn.textContent = submittingText; }
        form.submit();
      }, busyText, form);
    });
  });
})();
