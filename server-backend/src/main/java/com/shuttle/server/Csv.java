package com.shuttle.server;

import com.shuttle.common.CsvUtil;
import com.shuttle.server.io.CsvTable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Csv {

    private Csv() { }


    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }


    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public static boolean has(Map<String, Object> m, String key) {
        return m.containsKey(key);
    }

    public static boolean filled(Map<String, Object> m, String key) {
        return !str(m, key).trim().isEmpty();
    }

    public static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static long lng(Map<String, Object> m, String key) {
        return (long) num(m, key);
    }

    public static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        return "true".equalsIgnoreCase(String.valueOf(v).trim());
    }

    public static String toCsv(Object body) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (body instanceof Map) {
            rows.add(asMap(body));
        } else if (body instanceof Iterable) {
            for (Object o : (Iterable<?>) body) {
                rows.add(asMap(o));
            }
        } else if (body != null) {
            rows.add(obj("value", body));
        }
        if (rows.isEmpty()) {
            return "";
        }

        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        appendLine(sb, columns);
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String c : columns) {
                cells.add(cell(row.get(c)));
            }
            appendLine(sb, cells);
        }
        return sb.toString();
    }

    public static String error(String message) {
        return toCsv(obj("error", message));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return obj("value", o);
    }

    private static String cell(Object v) {
        if (v == null) return "";
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        }
        return String.valueOf(v).replace("\r", " ").replace("\n", " ");
    }

    private static void appendLine(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(CsvUtil.escape(cells.get(i)));
        }
        sb.append('\n');
    }

    public static List<Map<String, Object>> parseRows(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            CsvTable table = CsvTable.parse(text, "request", Collections.<String>emptyList());
            List<String> header = table.getHeader();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (CsvTable.Row row : table.getRows()) {
                String[] cells = row.toArray();
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    m.put(header.get(i), cells[i] == null ? "" : cells[i]);
                }
                rows.add(m);
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid CSV: " + e.getMessage());
        }
    }
}
