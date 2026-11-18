package com.shuttle.server.repository;

import com.shuttle.server.io.CsvTable;

import java.io.IOException;
import com.shuttle.server.storage.CsvStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Terminal ownership, memberships and single-use invitations commit in one atomic file. */
public final class TerminalRepository {
    public static final List<String> HEADER = List.of("type", "id", "terminalId", "name", "expiresAt", "usedBy");

    public static final class Entry {
        public final String type, id, terminalId, name, usedBy;
        public final long expiresAt;

        public Entry(String type, String id, String terminalId, String name, long expiresAt, String usedBy) {
            this.type = type;
            this.id = id;
            this.terminalId = terminalId;
            this.name = name;
            this.expiresAt = expiresAt;
            this.usedBy = usedBy;
        }
    }

    private final Path file;
    private List<Entry> entries = new ArrayList<>();

    public TerminalRepository(Path file) throws IOException {
        this.file = file;
        if (!CsvStorage.exists(file)) {
            new CsvTable(HEADER).save(file);
            return;
        }
        for (CsvTable.Row r : CsvTable.load(file, HEADER).getRows()) {
            String type = r.get("type");
            if (!List.of("TERMINAL", "MEMBER", "SHUTTLE", "INVITE").contains(type)) {
                throw new IOException("Unknown terminal record type at line " + r.lineNumber());
            }
            try {
                Entry entry = new Entry(type, r.get("id"), r.get("terminalId"), r.get("name"),
                        Long.parseLong(r.getOrDefault("expiresAt", "0")), r.get("usedBy"));
                if (entry.id.isEmpty() || entry.terminalId.isEmpty()
                        || entries.stream().anyMatch(e -> e.type.equals(type) && e.id.equals(entry.id))) {
                    throw new IllegalArgumentException("Blank or duplicate record ID");
                }
                entries.add(entry);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid terminal record at line " + r.lineNumber(), e);
            }
        }
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries);
    }

    public synchronized void replaceAll(List<Entry> fresh) throws IOException {
        CsvTable table = new CsvTable(HEADER);
        for (Entry e : fresh) {
            table.addRow(e.type, e.id, e.terminalId, e.name, String.valueOf(e.expiresAt), e.usedBy);
        }
        table.save(file);
        entries = new ArrayList<>(fresh);
    }
}
