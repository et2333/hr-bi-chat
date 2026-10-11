package com.hrchat.report.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * 规范化 viewKey（I0）：根视图为 {@code root}；组织下钻为 {@code dim=org|value=...}。
 */
public final class ViewKeySupport {

    public static final String ROOT = "root";
    public static final String STATUS_NOT_DERIVABLE = "VIEW_NOT_DERIVABLE";

    private ViewKeySupport() {
    }

    public static String root() {
        return ROOT;
    }

    public static String forOrgDrill(String dimValue) {
        if (dimValue == null || dimValue.isBlank()) {
            return ROOT;
        }
        String encoded = URLEncoder.encode(dimValue.trim(), StandardCharsets.UTF_8);
        return "dim=org|value=" + encoded;
    }

    public static String normalize(String dimValue) {
        return forOrgDrill(dimValue);
    }

    public static boolean isRoot(String viewKey) {
        return viewKey == null || viewKey.isBlank() || ROOT.equals(viewKey);
    }

    public static String orgDrillValue(String viewKey) {
        if (isRoot(viewKey) || !viewKey.startsWith("dim=org|value=")) {
            return null;
        }
        String raw = viewKey.substring("dim=org|value=".length());
        return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    public static boolean same(String a, String b) {
        return Objects.equals(normalizeKey(a), normalizeKey(b));
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return ROOT;
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
