package com.fundamentum.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;

/**
 * The JSON5 subset our config files use: {@code //} and {@code /* *}{@code /} comments, trailing commas, unquoted keys
 * and single-quoted strings. Comments and trailing commas are stripped here; the rest is Gson's lenient reader.
 */
public final class Json5 {
    private Json5() { }

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Parses JSON5 text. Throws {@link IllegalArgumentException} with the reason when it is not valid. */
    public static JsonElement parse(String text) {
        try {
            JsonReader reader = new JsonReader(new StringReader(strip(text)));
            return JsonParser.parseReader(reader);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /** One value written as JSON (strings quoted and escaped). */
    public static String write(JsonElement value) {
        return GSON.toJson(value);
    }

    /** Removes comments and commas that are followed only by whitespace and a closing bracket. */
    static String strip(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        char quote = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(text.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                out.append(c);
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        // second pass: trailing commas (comments are gone, strings still need skipping)
        String s = out.toString();
        StringBuilder res = new StringBuilder(s.length());
        quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                res.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    res.append(s.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ',') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length() && (s.charAt(j) == '}' || s.charAt(j) == ']')) continue;
            }
            res.append(c);
        }
        return res.toString();
    }
}
