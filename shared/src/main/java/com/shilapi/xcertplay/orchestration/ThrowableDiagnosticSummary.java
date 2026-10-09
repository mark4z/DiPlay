package com.shilapi.xcertplay.orchestration;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Bounded cause metadata. Never read exception messages or protocol/credential data. */
final class ThrowableDiagnosticSummary {
    private static final int MAX_DEPTH = 5;
    private static final int MAX_CHARS = 650;
    private static final int MAX_FIELD_CHARS = 80;

    private ThrowableDiagnosticSummary() {}

    static String describe(Throwable error) {
        StringBuilder summary = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_DEPTH && summary.length() < MAX_CHARS) {
            if (!seen.add(current)) {
                append(summary, " <- <cycle>");
                return summary.toString();
            }
            if (depth > 0) append(summary, " <- ");
            append(summary, safeField(current.getClass().getSimpleName()));
            StackTraceElement[] frames = current.getStackTrace();
            if (frames.length > 0) {
                StackTraceElement frame = frames[0];
                String owner = frame.getClassName();
                owner = owner.substring(owner.lastIndexOf('.') + 1);
                String file = frame.getFileName();
                if (file != null) {
                    int separator = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
                    file = file.substring(separator + 1);
                }
                append(summary, " @ " + safeField(owner) + "." + safeField(frame.getMethodName())
                        + "(" + safeField(file) + ":" + frame.getLineNumber() + ")");
            }
            current = current.getCause();
            depth++;
        }
        if (current != null) append(summary, " <- <truncated>");
        return summary.toString();
    }

    private static String safeField(String value) {
        if (value == null || value.isEmpty()) return "?";
        int length = Math.min(value.length(), MAX_FIELD_CHARS);
        StringBuilder safe = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            safe.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '$' || c == '.' ? c : '?');
        }
        return safe.toString();
    }

    private static void append(StringBuilder output, String value) {
        int remaining = MAX_CHARS - output.length();
        if (remaining > 0) output.append(value, 0, Math.min(value.length(), remaining));
    }
}
