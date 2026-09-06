(() => {
    const SAMPLE_BYTES = 64 * 1024;
    const RETRY_DELAYS = [0, 1000, 3000, 5000, 10000];
    const RESUME_STORAGE_PREFIX = "endervault-resumable-upload:";
    const claimedResumeSessions = new Set();

    const csrfHeaders = () => {
        const token = document.querySelector('meta[name="_csrf"]')?.content || "";
        const header = document.querySelector('meta[name="_csrf_header"]')?.content || "X-CSRF-TOKEN";
        return token ? { [header]: token } : {};
    };

    const bytesToHex = (bytes) => Array.from(bytes)
        .map(value => value.toString(16).padStart(2, "0"))
        .join("");

    const fallbackFingerprint = (bytes) => {
        const states = [
            0x811c9dc5, 0x9e3779b9, 0x85ebca6b, 0xc2b2ae35,
            0x27d4eb2f, 0x165667b1, 0xd3a2646c, 0xfd7046c5
        ];
        bytes.forEach((value, index) => {
            states.forEach((state, stateIndex) => {
                const mixed = value ^ ((index + stateIndex * 31) & 0xff);
                states[stateIndex] = Math.imul(state ^ mixed, 0x01000193) >>> 0;
            });
        });
        return states.map((state, index) => {
            let value = (state ^ bytes.length ^ (index * 0x9e3779b9)) >>> 0;
            value = Math.imul(value ^ (value >>> 16), 0x85ebca6b) >>> 0;
            value = Math.imul(value ^ (value >>> 13), 0xc2b2ae35) >>> 0;
            value = (value ^ (value >>> 16)) >>> 0;
            return value.toString(16).padStart(8, "0");
        }).join("");
    };

    const fileFingerprint = async (file, context) => {
        const first = await file.slice(0, Math.min(file.size, SAMPLE_BYTES)).arrayBuffer();
        const lastStart = Math.max(0, file.size - SAMPLE_BYTES);
        const last = lastStart === 0 ? new ArrayBuffer(0) : await file.slice(lastStart).arrayBuffer();
        const descriptor = new TextEncoder().encode([
            "endervault-upload-v1",
            context || "",
            file.name,
            file.type || "",
            String(file.size),
            String(file.lastModified || 0)
        ].join("\n"));
        const input = new Uint8Array(descriptor.byteLength + first.byteLength + last.byteLength);
        input.set(descriptor, 0);
        input.set(new Uint8Array(first), descriptor.byteLength);
        input.set(new Uint8Array(last), descriptor.byteLength + first.byteLength);
        if (window.crypto?.subtle) {
            return bytesToHex(new Uint8Array(await window.crypto.subtle.digest("SHA-256", input)));
        }
        return fallbackFingerprint(input);
    };

    const parseJson = async (response) => {
        try {
            return await response.json();
        } catch (error) {
            return { ok: false, message: "The server response could not be read." };
        }
    };

    const detailedStatus = (error) => {
        const response = error?.originalResponse;
        return typeof response?.getStatus === "function" ? response.getStatus() : 0;
    };

    const shouldRetry = (error) => {
        const status = detailedStatus(error);
        return status === 0 || status === 409 || status === 423 || status === 429 || status >= 500;
    };

    const uploadError = (error) => {
        const response = error?.originalResponse;
        const body = typeof response?.getBody === "function" ? response.getBody() : "";
        if (body) {
            try {
                const parsed = JSON.parse(body);
                if (parsed.message) {
                    return new Error(parsed.message);
                }
            } catch (ignored) {
                // Fall back to the tus client error below.
            }
        }
        return new Error(error?.message || "Upload failed.");
    };

    const resumeStorageKey = (fingerprint) => `${RESUME_STORAGE_PREFIX}${fingerprint}`;

    const storedResumeSessions = (fingerprint) => {
        try {
            const parsed = JSON.parse(window.localStorage.getItem(resumeStorageKey(fingerprint)) || "[]");
            return Array.isArray(parsed)
                ? parsed.filter(value => typeof value === "string" && value.length <= 64)
                : [];
        } catch (ignored) {
            return [];
        }
    };

    const writeResumeSessions = (fingerprint, sessionIds) => {
        try {
            const key = resumeStorageKey(fingerprint);
            if (sessionIds.length === 0) {
                window.localStorage.removeItem(key);
            } else {
                window.localStorage.setItem(key, JSON.stringify(Array.from(new Set(sessionIds))));
            }
        } catch (ignored) {
            // Uploading still works when local storage is unavailable; only restart resume is lost.
        }
    };

    const claimResumeSession = (fingerprint) => {
        const sessionId = storedResumeSessions(fingerprint)
            .find(candidate => !claimedResumeSessions.has(candidate));
        if (sessionId) {
            claimedResumeSessions.add(sessionId);
        }
        return sessionId || null;
    };

    const rememberResumeSession = (fingerprint, sessionId) => {
        if (!sessionId) return;
        claimedResumeSessions.add(sessionId);
        writeResumeSessions(fingerprint, [...storedResumeSessions(fingerprint), sessionId]);
    };

    const forgetResumeSession = (fingerprint, sessionId) => {
        if (!sessionId) return;
        claimedResumeSessions.delete(sessionId);
        writeResumeSessions(
            fingerprint,
            storedResumeSessions(fingerprint).filter(candidate => candidate !== sessionId)
        );
    };

    const admit = async (url, file, fingerprint, resumeSessionId, uploaderName) => {
        const response = await fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: {
                "Accept": "application/json",
                "Content-Type": "application/json",
                ...csrfHeaders()
            },
            body: JSON.stringify({
                filename: file.name,
                contentType: file.type || "",
                size: file.size,
                lastModified: file.lastModified || 0,
                fingerprint,
                resumeSessionId,
                uploaderName: uploaderName || null
            })
        });
        const body = await parseJson(response);
        if (!response.ok || body.ok === false) {
            const failure = new Error(body.message || "Upload could not be reserved.");
            failure.status = response.status;
            failure.retryAfterSeconds = body.retryAfterSeconds;
            throw failure;
        }
        return body;
    };

    const readStatus = async (url) => {
        const response = await fetch(url, {
            credentials: "same-origin",
            headers: { "Accept": "application/json", ...csrfHeaders() }
        });
        const body = await parseJson(response);
        if (!response.ok || body.ok === false) {
            throw new Error(body.message || "Upload status could not be read.");
        }
        return body;
    };

    class UploadHandle {
        constructor(options) {
            this.options = options;
            this.upload = null;
            this.admission = null;
            this.canceled = false;
            this.fingerprint = null;
            this.resumeSessionId = null;
            this.settleCanceled = null;
        }

        async start() {
            const { file, context, admissionUrl } = this.options;
            this.options.onState?.("fingerprinting", "Preparing upload");
            const fingerprint = await fileFingerprint(file, context);
            this.fingerprint = fingerprint;
            if (this.canceled) return null;

            this.options.onState?.("reserving", "Reserving upload");
            const resumeSessionId = claimResumeSession(fingerprint);
            this.resumeSessionId = resumeSessionId;
            let admission;
            try {
                admission = await admit(
                    admissionUrl,
                    file,
                    fingerprint,
                    resumeSessionId,
                    this.options.uploaderName
                );
            } catch (error) {
                if (resumeSessionId) {
                    claimedResumeSessions.delete(resumeSessionId);
                }
                throw error;
            }
            if (resumeSessionId && admission.sessionId !== resumeSessionId) {
                forgetResumeSession(fingerprint, resumeSessionId);
            }
            rememberResumeSession(fingerprint, admission.sessionId);
            this.admission = admission;
            if (this.canceled) {
                await this.cancelAdmission();
                return { status: "CANCELED", message: "Upload was canceled." };
            }

            if (admission.ready === true) {
                try {
                    const result = await readStatus(admission.statusUrl);
                    forgetResumeSession(fingerprint, admission.sessionId);
                    this.options.onProgress?.(file.size, file.size);
                    return result;
                } finally {
                    claimedResumeSessions.delete(admission.sessionId);
                }
            }

            return await new Promise((resolve, reject) => {
                this.settleCanceled = () => resolve({ status: "CANCELED", message: "Upload was canceled." });
                const upload = new window.tus.Upload(file, {
                    endpoint: admission.endpoint,
                    uploadUrl: admission.uploadUrl || null,
                    chunkSize: admission.chunkSizeBytes,
                    retryDelays: RETRY_DELAYS,
                    headers: { ...csrfHeaders(), "X-Requested-With": "XMLHttpRequest" },
                    metadata: {
                        filename: file.name,
                        filetype: file.type || "application/octet-stream",
                        sessionId: admission.sessionId
                    },
                    fingerprint: async () => `endervault-${fingerprint}`,
                    storeFingerprintForResuming: false,
                    uploadDataDuringCreation: false,
                    onShouldRetry: shouldRetry,
                    onProgress: (sent, total) => this.options.onProgress?.(sent, total),
                    onError: error => {
                        claimedResumeSessions.delete(admission.sessionId);
                        if (this.canceled) {
                            resolve({ status: "CANCELED", message: "Upload was canceled." });
                            return;
                        }
                        reject(uploadError(error));
                    },
                    onSuccess: async () => {
                        try {
                            const result = await readStatus(admission.statusUrl);
                            forgetResumeSession(fingerprint, admission.sessionId);
                            resolve(result);
                        } catch (error) {
                            claimedResumeSessions.delete(admission.sessionId);
                            reject(error);
                        }
                    }
                });
                this.upload = upload;
                this.options.onState?.("uploading", admission.uploadUrl ? "Resuming upload" : "Uploading");
                if (this.canceled) {
                    resolve(null);
                    return;
                }
                upload.start();
            });
        }

        async abort() {
            this.canceled = true;
            if (this.upload) {
                await this.upload.abort(true);
            }
            this.settleCanceled?.();
            if (this.admission) {
                await this.cancelAdmission();
            }
        }

        async cancelAdmission() {
            const statusUrl = this.admission?.statusUrl;
            if (!statusUrl) {
                return;
            }
            try {
                const response = await fetch(statusUrl, {
                    method: "DELETE",
                    credentials: "same-origin",
                    headers: { "Accept": "application/json", ...csrfHeaders() }
                });
                if (response.ok || response.status === 404 || response.status === 410) {
                    forgetResumeSession(this.fingerprint, this.admission.sessionId);
                }
            } catch (ignored) {
                // Expiration cleanup remains the fallback for an interrupted cancel request.
            }
        }
    }

    window.EnderVaultResumableUpload = {
        create(options) {
            if (!window.tus?.Upload) {
                throw new Error("Resumable upload support is unavailable.");
            }
            return new UploadHandle(options);
        },
        fingerprint: fileFingerprint
    };
})();
