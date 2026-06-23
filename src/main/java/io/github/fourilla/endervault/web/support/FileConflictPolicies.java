package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.storage.ConflictPolicy;
import jakarta.servlet.http.HttpServletRequest;

public final class FileConflictPolicies {

    private FileConflictPolicies() {
    }

    public static boolean asks(String conflictPolicy) {
        return "ask".equalsIgnoreCase(clean(conflictPolicy));
    }

    public static boolean asksForJson(String conflictPolicy, HttpServletRequest request) {
        return asks(conflictPolicy) && ActionResponseSupport.wantsJson(request);
    }

    public static boolean cancels(String conflictPolicy, ConflictPolicy defaultPolicy) {
        String cleanPolicy = clean(conflictPolicy);
        return "cancel".equalsIgnoreCase(cleanPolicy)
                || ("default".equalsIgnoreCase(cleanPolicy) && defaultPolicy == ConflictPolicy.CANCEL);
    }

    public static ConflictPolicy mutationPolicy(String conflictPolicy, ConflictPolicy defaultPolicy) {
        if (asks(conflictPolicy)) {
            return ConflictPolicy.CANCEL;
        }
        if ("default".equalsIgnoreCase(clean(conflictPolicy))) {
            return defaultPolicy;
        }
        return ConflictPolicy.from(conflictPolicy);
    }

    public static ConflictPolicy transferPolicy(String conflictPolicy, ConflictPolicy defaultPolicy) {
        if (asks(conflictPolicy) || "default".equalsIgnoreCase(clean(conflictPolicy))) {
            return defaultPolicy;
        }
        return ConflictPolicy.from(conflictPolicy);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
