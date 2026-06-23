(function () {
    const base64UrlToArrayBuffer = (value) => {
        const padding = "=".repeat((4 - value.length % 4) % 4);
        const base64 = (value + padding).replace(/-/g, "+").replace(/_/g, "/");
        const binary = window.atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index += 1) {
            bytes[index] = binary.charCodeAt(index);
        }
        return bytes.buffer;
    };

    const arrayBufferToBase64Url = (buffer) => {
        if (buffer == null) {
            return null;
        }
        const bytes = new Uint8Array(buffer);
        let binary = "";
        for (const byte of bytes) {
            binary += String.fromCharCode(byte);
        }
        return window.btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
    };

    const csrfHeaders = () => {
        const pair = window.EnderVault?.csrfPair?.();
        return pair ? { "X-CSRF-TOKEN": pair.value } : {};
    };

    const postJson = async (url, body = null) => {
        const response = await fetch(url, {
            method: "POST",
            body: body == null ? null : JSON.stringify(body),
            headers: {
                "Accept": "application/json",
                "Content-Type": "application/json",
                "X-Requested-With": "fetch",
                ...csrfHeaders()
            },
            credentials: "same-origin"
        });
        const payload = await response.json();
        if (!response.ok || payload?.ok === false) {
            throw new Error(payload?.notification?.message || "Passkey action failed.");
        }
        return payload;
    };

    const loadOptions = async (url) => {
        const response = await fetch(url, {
            method: "POST",
            headers: {
                "Accept": "application/json",
                "X-Requested-With": "fetch",
                ...csrfHeaders()
            },
            credentials: "same-origin"
        });
        const payload = await response.json();
        if (!response.ok || payload?.ok === false) {
            throw new Error(payload?.notification?.message || "Passkey request failed.");
        }
        return payload;
    };

    const prepareCreationOptions = (options) => {
        const publicKey = options.publicKey || options;
        publicKey.challenge = base64UrlToArrayBuffer(publicKey.challenge);
        publicKey.user.id = base64UrlToArrayBuffer(publicKey.user.id);
        if (publicKey.excludeCredentials) {
            publicKey.excludeCredentials = publicKey.excludeCredentials.map((credential) => ({
                ...credential,
                id: base64UrlToArrayBuffer(credential.id)
            }));
        }
        return options.publicKey ? options : { publicKey };
    };

    const prepareRequestOptions = (options) => {
        const publicKey = options.publicKey || options;
        publicKey.challenge = base64UrlToArrayBuffer(publicKey.challenge);
        if (publicKey.allowCredentials) {
            publicKey.allowCredentials = publicKey.allowCredentials.map((credential) => ({
                ...credential,
                id: base64UrlToArrayBuffer(credential.id)
            }));
        }
        return options.publicKey ? options : { publicKey };
    };

    const credentialToJson = (credential) => {
        const response = credential.response;
        const json = {
            id: credential.id,
            rawId: arrayBufferToBase64Url(credential.rawId),
            type: credential.type,
            authenticatorAttachment: credential.authenticatorAttachment || null,
            clientExtensionResults: credential.getClientExtensionResults(),
            response: {
                clientDataJSON: arrayBufferToBase64Url(response.clientDataJSON)
            }
        };

        if (response.attestationObject) {
            json.response.attestationObject = arrayBufferToBase64Url(response.attestationObject);
            if (typeof response.getTransports === "function") {
                json.response.transports = response.getTransports();
            }
        }

        if (response.authenticatorData) {
            json.response.authenticatorData = arrayBufferToBase64Url(response.authenticatorData);
            json.response.signature = arrayBufferToBase64Url(response.signature);
            json.response.userHandle = arrayBufferToBase64Url(response.userHandle);
        }

        return json;
    };

    const setButtonBusy = (button, busy) => {
        if (!button) {
            return;
        }
        button.disabled = busy;
        button.classList.toggle("is-busy", busy);
    };

    const showError = (error) => {
        window.EnderVault?.showToast?.("error", error.message || "Passkey action failed.");
    };

    const canUsePasskeys = () => {
        if (!window.PublicKeyCredential) {
            window.EnderVault?.showToast?.("error", "This browser does not support passkeys.");
            return false;
        }
        if (!window.isSecureContext) {
            window.EnderVault?.showToast?.("error", "Passkeys require HTTPS or localhost.");
            return false;
        }
        return true;
    };

    const registerPasskey = async (button) => {
        const form = button.closest("[data-passkey-register-form]");
        const label = form?.querySelector('input[name="label"]')?.value || "";
        setButtonBusy(button, true);
        try {
            const options = prepareCreationOptions(await loadOptions(button.dataset.optionsUrl));
            const credential = await navigator.credentials.create(options);
            const body = await postJson(button.dataset.finishUrl, {
                label,
                credential: credentialToJson(credential)
            });
            if (!window.EnderVault?.navigateWithNotification?.(body)) {
                window.location.reload();
            }
        } catch (error) {
            showError(error);
        } finally {
            setButtonBusy(button, false);
        }
    };

    const loginWithPasskey = async (button) => {
        setButtonBusy(button, true);
        try {
            const options = prepareRequestOptions(await loadOptions(button.dataset.optionsUrl));
            const credential = await navigator.credentials.get(options);
            const body = await postJson(button.dataset.finishUrl, {
                credential: credentialToJson(credential)
            });
            if (!window.EnderVault?.navigateWithNotification?.(body)) {
                window.location.assign(body.redirectUrl || "/files");
            }
        } catch (error) {
            showError(error);
        } finally {
            setButtonBusy(button, false);
        }
    };

    document.addEventListener("click", (event) => {
        const registerButton = event.target.closest("[data-passkey-register]");
        if (registerButton) {
            event.preventDefault();
            if (canUsePasskeys()) {
                registerPasskey(registerButton);
            }
            return;
        }

        const loginButton = event.target.closest("[data-passkey-login]");
        if (loginButton) {
            event.preventDefault();
            if (canUsePasskeys()) {
                loginWithPasskey(loginButton);
            }
        }
    });
})();
