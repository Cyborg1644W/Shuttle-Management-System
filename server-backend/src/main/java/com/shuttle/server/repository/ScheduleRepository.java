package com.shuttle.server.repository;

import com.shuttle.server.io.CsvTable;

import java.io.IOException;
import com.shuttle.server.storage.CsvStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ScheduleRepository {

    public static final String FILE_NAME = "schedule.txt";

    public static final List<String> HEADER = List.of("employeeID", "shift", "days");

    public static final class Entry {
        public final String employeeId;
        public final int shift;      // 0 = morning, 1 = afternoon, 2 = night
        public final String days;    // 7 characters, Monday first, '1' = works that day

        public Entry(String employeeId, int shift, String days) {
            this.employeeId = employeeId;
            this.shift = shift;
            this.days = days;
        }
    }

    private final Path file;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private List<Entry> entries = new ArrayList<>();

    public ScheduleRepository(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        load();
    }

    private void load() throws IOException {
        List<Entry> fresh = new ArrayList<>();
        if (!CsvStorage.exists(file)) {
            new CsvTable(HEADER).save(file);
        } else {
            CsvTable table = CsvTable.load(file, HEADER);
            for (CsvTable.Row row : table.getRows()) {
                try {
                    fresh.add(parse(row));
                } catch (IllegalArgumentException e) {
                    throw new CsvTable.CsvFormatException(file + " line " + row.lineNumber() + ": " + e.getMessage());
                }
            }
        }
        lock.writeLock().lock();
        try {
            entries = fresh;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Entry> getAll() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(entries);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void replaceAll(List<Entry> fresh) throws IOException {
        for (Entry e : fresh) {
            validate(e);
        }
        lock.writeLock().lock();
        try {
            List<Entry> next = new ArrayList<>(fresh);
            persist(next);
            entries = next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeEmployee(String employeeId) throws IOException {
        lock.writeLock().lock();
        try {
            List<Entry> next = new ArrayList<>();
            for (Entry e : entries) {
                if (!e.employeeId.equals(employeeId)) {
                    next.add(e);
                }
            }
            if (next.size() != entries.size()) {
                persist(next);
                entries = next;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void renameEmployee(String oldId, String newId) throws IOException {
        lock.writeLock().lock();
        try {
            List<Entry> next = new ArrayList<>();
            boolean changed = false;
            for (Entry e : entries) {
                if (e.employeeId.equals(oldId)) {
                    next.add(new Entry(newId, e.shift, e.days));
                    changed = true;
                } else {
                    next.add(e);
                }
            }
            if (changed) {
                persist(next);
                entries = next;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void validate(Entry e) {
        if (e.employeeId == null || e.employeeId.trim().isEmpty()) {
            throw new IllegalArgumentException("employeeId is required");
        }
        if (e.shift < 0 || e.shift > 2) {
            throw new IllegalArgumentException("shift must be 0 (morning), 1 (afternoon) or 2 (night)");
        }
        if (e.days == null || !e.days.matches("[01]{7}")) {
            throw new IllegalArgumentException("days must be 7 characters of 0/1, Monday first");
        }
    }

    private static Entry parse(CsvTable.Row row) {
        int shift;
        try {
            shift = Integer.parseInt(row.get("shift"));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("shift is not a whole number: '" + row.get("shift") + "'");
        }
        Entry e = new Entry(row.get("employeeID"), shift, row.get("days").toLowerCase(Locale.ROOT));
        validate(e);
        return e;
    }

    private void persist(List<Entry> data) throws IOException {
        CsvTable table = new CsvTable(HEADER);
        for (Entry e : data) {
            table.addRow(e.employeeId, String.valueOf(e.shift), e.days);
        }
        table.save(file);
    }
}
