package com.shuttle.server.repository;

import com.shuttle.server.io.CsvTable;

import java.io.IOException;
import com.shuttle.server.storage.CsvStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Terminal ownership, memberships and single-use invitations commit in one atomic file. */
public final class TerminalRepository {
    public static final List<String> HEADER = List.of("type", "id", "terminalId", "name", "expiresAt", "usedBy",
            "enabled", "terminalLatitude", "terminalLongitude");

    public static final class Entry {
        public final String type, id, terminalId, name, usedBy;
        public final long expiresAt;
        public final boolean enabled;
        public final String latitude, longitude;

        public Entry(String type, String id, String terminalId, String name, long expiresAt, String usedBy) {
            this(type, id, terminalId, name, expiresAt, usedBy, true);
        }

        public Entry(String type, String id, String terminalId, String name, long expiresAt, String usedBy,
                     boolean enabled) {
            this(type, id, terminalId, name, expiresAt, usedBy, enabled, "", "");
        }

        public Entry(String type, String id, String terminalId, String name, long expiresAt, String usedBy,
                     boolean enabled, String latitude, String longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.enabled = enabled;
            this.type = type;
            this.id = id;
            this.terminalId = terminalId;
            this.name = name;
            this.expiresAt = expiresAt;
            this.usedBy = usedBy;
        }
    }

    private final Path file;
    private Map<String, Entry> entries = new LinkedHashMap<>();

    public TerminalRepository(Path file) throws IOException {
        this.file = file;
        if (!CsvStorage.exists(file)) {
            new CsvTable(HEADER).save(file);
            return;
        }
        for (CsvTable.Row r : CsvTable.load(file, HEADER.subList(0, 6)).getRows()) {
            String type = r.get("type");
            if (!List.of("TERMINAL", "MEMBER", "SHUTTLE", "INVITE").contains(type)) {
                throw new IOException("Unknown terminal record type at line " + r.lineNumber());
            }
            try {
                Entry entry = new Entry(type, r.get("id"), r.get("terminalId"), r.get("name"),
                        Long.parseLong(r.getOrDefault("expiresAt", "0")), r.get("usedBy"),
                        !"false".equalsIgnoreCase(r.getOptional("enabled", "true")),
                        r.getOptional("terminalLatitude", ""), r.getOptional("terminalLongitude", ""));
                if (entry.id.isEmpty() || entry.terminalId.isEmpty()
                        || entries.putIfAbsent(type + "/" + entry.id, entry) != null) {
                    throw new IllegalArgumentException("Blank or duplicate record ID");
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid terminal record at line " + r.lineNumber(), e);
            }
        }
    }

    public synchronized Entry find(String type, String id) {
        return entries.get(type + "/" + id);
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    public synchronized void replaceAll(List<Entry> fresh) throws IOException {
        CsvTable table = new CsvTable(HEADER);
        Map<String, Entry> next = new LinkedHashMap<>();
        for (Entry e : fresh) {
            if (next.putIfAbsent(e.type + "/" + e.id, e) != null) throw new IllegalArgumentException("Duplicate terminal record");
            table.addRow(e.type, e.id, e.terminalId, e.name, String.valueOf(e.expiresAt), e.usedBy,
                    e.type.equals("INVITE") ? String.valueOf(e.enabled) : "", e.latitude, e.longitude);
        }
        table.save(file);
        entries = next;
    }
}
