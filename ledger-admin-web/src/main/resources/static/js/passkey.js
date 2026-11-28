"use strict";
// The passkey ceremonies of the admin pages, with the browser's WebAuthn API and the endpoints of Spring Security's WebAuthn
// support (/webauthn/authenticate/options, /login/webauthn, /webauthn/register/options, /webauthn/register).
// Every byte string travels as base64url. The script is a file of the application: the content security policy forbids inline script.
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
  const passkeyStatus = () => document.getElementById("passkey-status");

  function clearStatus() {
    const status = passkeyStatus();
    if (!status) { return; }
    status.hidden = true;
    status.textContent = "";
  }

  function show(message) {
    const status = passkeyStatus();
    const resolved = message || (status ? status.dataset.failed : "Failed");
    if (!status) { return; }
    status.textContent = resolved;
    status.hidden = false;
    status.scrollIntoView({ behavior: "smooth", block: "nearest" });
    status.focus();
  }

  function friendlyMessage(failure) {
    const status = passkeyStatus();
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

  async function withBusy(button, task, busyLabel) {
    if (button && button.dataset.busy === "true") { return; }
    clearStatus();
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
      show(friendlyMessage(failure));
      if (button) {
        button.dataset.busy = "false";
        button.disabled = false;
        button.removeAttribute("aria-busy");
        if (originalText !== null) { button.textContent = originalText; }
      }
    }
  }

  function initializePasskeys() {
    const status = passkeyStatus();
    const signInButton = document.getElementById("passkey-signin");
    const registerButton = document.getElementById("passkey-register");
    const needsPasskey = Boolean(signInButton || registerButton);

    if (!window.PublicKeyCredential) {
      if (needsPasskey) {
        show(status ? (status.dataset.unsupported || status.dataset.failed) : null);
      }
      return;
    }

    const busyText = status ? status.dataset.busy : null;

    if (signInButton) {
      signInButton.addEventListener("click", () => withBusy(signInButton, signIn, busyText));
    }
    if (registerButton) {
      registerButton.addEventListener("click", () => withBusy(registerButton, () => {
        const labelInput = document.getElementById("passkey-label");
        return register(labelInput ? labelInput.value : "");
      }, busyText));
    }
  }

  initializePasskeys();
  document.addEventListener("veridger:page", initializePasskeys);
})();
