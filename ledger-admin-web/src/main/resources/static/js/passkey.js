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

  const meta = (name) => { const element = document.querySelector('meta[name="' + name + '"]'); return element ? element.content : null; };
  const status = document.getElementById("passkey-status");

  function show(message) {
    if (!status) { return; }
    status.textContent = message || status.dataset.failed;
    status.hidden = false;
  }

  async function post(url, body) {
    const headers = { "Content-Type": "application/json", "Accept": "application/json" };
    if (meta("_csrf")) { headers[meta("_csrf_header")] = meta("_csrf"); }
    const response = await fetch(url, { method: "POST", headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: "same-origin" });
    if (!response.ok) {
      let detail = null;
      try { detail = (await response.json()).detail; } catch (ignored) { detail = null; }
      throw new Error(detail || "HTTP " + response.status);
    }
    return response.json();
  }

  const descriptors = (list) => (list || []).map((entry) => ({ ...entry, id: b64.decode(entry.id) }));

  // sign-in with a passkey: the options and the login are the framework's endpoints
  async function signIn() {
    const options = await post("/webauthn/authenticate/options");
    const credential = await navigator.credentials.get({ publicKey: { ...options, challenge: b64.decode(options.challenge), allowCredentials: descriptors(options.allowCredentials) } });
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

  // registration of a passkey (after the sign-in with the one-time code): the framework's endpoints
  async function register(label) {
    const options = await post("/webauthn/register/options");
    const credential = await navigator.credentials.create({ publicKey: { ...options,
      user: { ...options.user, id: b64.decode(options.user.id) }, challenge: b64.decode(options.challenge),
      excludeCredentials: descriptors(options.excludeCredentials) } });
    const response = credential.response;
    const result = await post("/webauthn/register", { publicKey: { credential: {
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
    }, label } });
    if (!(result && result.success)) { throw new Error(); }
    window.location.href = "/passkeys?success";
  }

  // the approval of an owner write: the server is told what is about to be sent, makes the challenge from it, the passkey signs
  // the challenge, and the assertion goes with the form as the field "approval"
  async function approve(form) {
    const fields = [];
    new FormData(form).forEach((value, name) => { if (name !== "_csrf" && name !== "approval") { fields.push({ name, value: String(value) }); } });
    const options = await post("/approval/intent", { method: "POST", path: new URL(form.action, window.location.href).pathname, fields });
    const credential = await navigator.credentials.get({ publicKey: { challenge: b64.decode(options.challenge), rpId: options.rpId,
      allowCredentials: options.allowCredentials.map((id) => ({ type: "public-key", id: b64.decode(id) })),
      userVerification: options.userVerification, timeout: options.timeout } });
    const response = credential.response;
    let field = form.querySelector('input[name="approval"]');
    if (!field) {
      field = document.createElement("input");
      field.type = "hidden";
      field.name = "approval";
      form.appendChild(field);
    }
    field.value = JSON.stringify({ credentialId: b64.encode(credential.rawId), authenticatorData: b64.encode(response.authenticatorData),
      clientDataJson: b64.encode(response.clientDataJSON), signature: b64.encode(response.signature) });
  }

  function run(task) { task().catch((failure) => { console.error(failure); show(failure && failure.message ? failure.message : null); }); }

  if (!window.PublicKeyCredential) {
    show(status ? status.dataset.failed : null);
    return;
  }
  const signInButton = document.getElementById("passkey-signin");
  if (signInButton) { signInButton.addEventListener("click", () => run(signIn)); }
  const registerButton = document.getElementById("passkey-register");
  if (registerButton) {
    registerButton.addEventListener("click", () => run(() => register(document.getElementById("passkey-label").value)));
  }
  document.querySelectorAll("form[data-approve]").forEach((form) => {
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      run(async () => { await approve(form); form.submit(); });
    });
  });
})();
