(function () {
    const setText = (id, value) => {
        const element = document.getElementById(id);
        if (element) {
            element.textContent = value || "-";
        }
    };

    document.addEventListener("DOMContentLoaded", () => {
        const modal = document.getElementById("logDetailModal");
        const buttons = Array.from(document.querySelectorAll(".log-detail-open"));
        if (!modal || buttons.length === 0) {
            return;
        }

        buttons.forEach((button) => {
            button.addEventListener("click", () => {
                const log = button.dataset;
                setText("logDetailTitle", log.logType || "Log Entry");
                setText("logDetailSubtitle", `${log.logTime || "-"} · ${log.logStatus || "-"}`);
                setText("logDetailLine", log.logLine);
                setText("logDetailId", log.logId);
                setText("logDetailActor", log.logActor);
                setText("logDetailIp", log.logIp);
                setText("logDetailMessage", log.logMessage);
                setText("logDetailPath", log.logPath);
                setText("logDetailTarget", log.logTarget);
                setText("logDetailMetadata", log.logMetadata);

                if (typeof modal.showModal === "function") {
                    modal.showModal();
                } else {
                    modal.setAttribute("open", "");
                }
            });
        });

        modal.addEventListener("click", (event) => {
            if (event.target === modal) {
                modal.close();
            }
        });
    });
})();
