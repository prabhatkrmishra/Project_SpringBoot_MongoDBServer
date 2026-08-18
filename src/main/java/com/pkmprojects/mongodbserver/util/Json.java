package com.pkmprojects.mongodbserver.util;

/**
 * Minimal JSON string helpers. The app deliberately does not depend on Jackson
 * (the classpath only carries the MongoDB driver's BSON serializer), so JSON
 * payloads that are not BSON documents are assembled by hand and this is the
 * single source of truth for string escaping.
 */
public final class Json {

    private Json() {
    }

    /**
     * Serializes a string as a JSON value (null-safe).
     */
    public static String jsonString(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    /**
     * Escapes a string for embedding inside a JSON string literal (control
     * characters, quotes and backslashes).
     */
    public static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
