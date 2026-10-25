package com.shuttle.server.io;

import com.shuttle.common.CsvUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public final class CsvTable {

    public static final class CsvFormatException extends IOException {
        private static final long serialVersionUID = 1L;

        public CsvFormatException(String message) {
            super(message);
        }
    }

    public static final class Row {
        private final Map<String, Integer> index;
        private final String[] cells;
        private final int lineNumber;

        private Row(Map<String, Integer> index, String[] cells, int lineNumber) {
            this.index = index;
            this.cells = cells;
            this.lineNumber = lineNumber;
        }

        public String get(String column) {
            Integer i = index.get(column);
            if (i == null) {
                throw new IllegalArgumentException("Unknown column: " + column);
            }
            String value = cells[i];
            return value == null ? "" : value.trim();
        }

        public String getOrDefault(String column, String fallback) {
            String value = get(column);
            return value.isEmpty() ? fallback : value;
        }

        public int lineNumber() {
            return lineNumber;
        }

        public String[] toArray() {
            return cells.clone();
        }
    }


    private final List<String> header;
    private final Map<String, Integer> index = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();

    public CsvTable(List<String> header) {
        Objects.requireNonNull(header, "header");
        if (header.isEmpty()) {
            throw new IllegalArgumentException("Header must have at least one column");
        }
        List<String> copy = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i) == null ? "" : header.get(i).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Header column " + (i + 1) + " is blank");
            }
            if (index.put(name, i) != null) {
                throw new IllegalArgumentException("Duplicate header column: " + name);
            }
            copy.add(name);
        }
        this.header = Collections.unmodifiableList(copy);
    }

    public static CsvTable load(Path filePath) throws IOException {
        return load(filePath, Collections.<String>emptyList());
    }

    public static CsvTable load(Path filePath, List<String> requiredColumns) throws IOException {
        String text = AtomicFileWriter.readString(filePath);
        return parse(text, filePath.toString(), requiredColumns);
    }

    public static CsvTable parse(String text, String sourceName, List<String> requiredColumns)
            throws IOException {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1); // strip BOM written by Excel
        }

        CsvTable table = null;
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] cells = CsvUtil.parseCsvLine(line);

                if (table == null) { // first non-blank line == header
                    try {
                        table = new CsvTable(java.util.Arrays.asList(cells));
                    } catch (IllegalArgumentException e) {
                        throw new CsvFormatException(sourceName + " line " + lineNo + ": " + e.getMessage());
                    }
                    List<String> missing = new ArrayList<>();
                    for (String required : requiredColumns) {
                        if (!table.index.containsKey(required)) {
                            missing.add(required);
                        }
                    }
                    if (!missing.isEmpty()) {
                        throw new CsvFormatException(sourceName + ": missing required column(s) " + missing);
                    }
                } else {
                    if (cells.length != table.header.size()) {
                        throw new CsvFormatException(sourceName + " line " + lineNo + ": expected "
                                + table.header.size() + " columns but found " + cells.length);
                    }
                    table.rows.add(new Row(table.index, cells, lineNo));
                }
            }
        }
        if (table == null) {
            throw new CsvFormatException(sourceName + ": file is empty (header row missing)");
        }
        return table;
    }


    public List<String> getHeader() {
        return header;
    }

    public List<Row> getRows() {
        return Collections.unmodifiableList(rows);
    }

    public int size() {
        return rows.size();
    }

    public Row getRow(int index) {
        return rows.get(index);
    }

    public CsvTable addRow(String... cells) {
        if (cells.length != header.size()) {
            throw new IllegalArgumentException(
                    "Row has " + cells.length + " cells but the header has " + header.size());
        }
        String[] copy = new String[cells.length];
        for (int i = 0; i < cells.length; i++) {
            String value = cells[i] == null ? "" : cells[i];
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(
                        "Column '" + header.get(i) + "' contains a line break, which CSV rows cannot hold");
            }
            copy[i] = value;
        }
        rows.add(new Row(index, copy, rows.size() + 2)); // +2: header is line 1
        return this;
    }


    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, header.toArray(new String[0]));
        for (Row row : rows) {
            appendLine(sb, row.cells);
        }
        return sb.toString();
    }

    public void save(Path filePath) throws IOException {
        AtomicFileWriter.writeAtomically(filePath, toCsv());
    }

    private static void appendLine(StringBuilder sb, String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(CsvUtil.escapeCsv(cells[i]));
        }
        sb.append('\n');
    }
}