package com.shuttle.server.repository;

import com.shuttle.common.ShiftRecord;
import com.shuttle.common.ValidationUtil;
import com.shuttle.server.io.CsvTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ShiftRepository {

    public static final String FILE_NAME = "shifts.csv";

    public static final List<String> HEADER =
            List.of("shiftId", "driverId", "shuttleId", "startTime", "endTime", "status");

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";

    public static final String STATUS_TIMED_OUT = "TIMED_OUT";
    private static final Set<String> VALID_STATUSES = new HashSet<>(Arrays.asList(
            STATUS_ACTIVE, STATUS_COMPLETED, STATUS_TIMED_OUT));

    private static final String ID_PREFIX = "SHF-";

    private final Path file;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<String, ShiftRecord> byId = new LinkedHashMap<>(); // guarded by lock, replaced on write

    public ShiftRepository(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        loadAllShifts();
    }

    public List<ShiftRecord> loadAllShifts() throws IOException {
        Map<String, ShiftRecord> fresh = readFromDisk();
        lock.writeLock().lock();
        try {
            byId = fresh;
            return copyAll(fresh.values());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ShiftRecord> getAllShifts() {
        lock.readLock().lock();
        try {
            return copyAll(byId.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<ShiftRecord> findById(String shiftId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(byId.get(shiftId)).map(ShiftRepository::copyOf);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ShiftRecord> findByDriverId(String driverId) {
        lock.readLock().lock();
        try {
            List<ShiftRecord> result = new ArrayList<>();
            for (ShiftRecord s : byId.values()) {
                if (s.getDriverId().equals(driverId)) {
                    result.add(copyOf(s));
                }
            }
            result.sort(Comparator.comparingLong(ShiftRecord::getStartTime).reversed());
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<ShiftRecord> findActiveByDriverId(String driverId) {
        lock.readLock().lock();
        try {
            return activeShiftOf(byId, driverId).map(ShiftRepository::copyOf);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ShiftRecord> findAllActive() {
        return findByStatus(STATUS_ACTIVE);
    }

    public List<ShiftRecord> findByStatus(String status) {
        lock.readLock().lock();
        try {
            List<ShiftRecord> result = new ArrayList<>();
            for (ShiftRecord s : byId.values()) {
                if (s.getStatus().equalsIgnoreCase(status)) {
                    result.add(copyOf(s));
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int count() {
        lock.readLock().lock();
        try {
            return byId.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public String nextShiftId() {
        lock.readLock().lock();
        try {
            int max = 0;
            for (String id : byId.keySet()) {
                if (id.startsWith(ID_PREFIX)) {
                    try {
                        max = Math.max(max, Integer.parseInt(id.substring(ID_PREFIX.length())));
                    } catch (NumberFormatException ignored) {
                        // other id shapes are not part of the numbering
                    }
                }
            }
            return String.format(Locale.ROOT, "%s%06d", ID_PREFIX, max + 1);
        } finally {
            lock.readLock().unlock();
        }
    }

    public CsvTable toTable() {
        lock.readLock().lock();
        try {
            return buildTable(byId.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveShift(ShiftRecord shift) throws IOException {
        saveAll(List.of(shift));
    }

    public void saveAll(Collection<ShiftRecord> shifts) throws IOException {
        List<ShiftRecord> validated = new ArrayList<>();
        for (ShiftRecord s : shifts) {
            validated.add(copyOf(Objects.requireNonNull(s, "shift")));
        }
        lock.writeLock().lock();
        try {
            Map<String, ShiftRecord> next = new LinkedHashMap<>(byId);
            for (ShiftRecord s : validated) {
                next.put(s.getShiftId(), s);
                checkSingleActive(next, s);
            }
            persist(next);
            byId = next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<ShiftRecord> closeShift(String shiftId, long endMillis, String finalStatus) throws IOException {
        if (STATUS_ACTIVE.equalsIgnoreCase(finalStatus)) {
            throw new IllegalArgumentException("finalStatus cannot be ACTIVE");
        }
        lock.writeLock().lock();
        try {
            ShiftRecord current = byId.get(shiftId);
            if (current == null || !STATUS_ACTIVE.equals(current.getStatus())) {
                return Optional.empty();
            }
            ShiftRecord closed = copyOf(current);
            closed.setEndTime(endMillis);
            closed.setStatus(finalStatus);
            closed = copyOf(closed); // validates end >= start and the status value
            Map<String, ShiftRecord> next = new LinkedHashMap<>(byId);
            next.put(shiftId, closed);
            persist(next);
            byId = next;
            return Optional.of(copyOf(closed));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static ShiftRecord fromRow(CsvTable.Row row) {
        String id = row.get("shiftId");
        if (!ValidationUtil.isNotEmpty(id)) {
            throw new IllegalArgumentException("shiftId is empty");
        }
        String driverId = row.get("driverId");
        if (!ValidationUtil.isNotEmpty(driverId)) {
            throw new IllegalArgumentException("driverId is empty");
        }
        String shuttleId = row.get("shuttleId");
        if (!ValidationUtil.isNotEmpty(shuttleId)) {
            throw new IllegalArgumentException("shuttleId is empty");
        }
        long start = parseMillis("startTime", row.get("startTime"), true);
        long end = parseMillis("endTime", row.get("endTime"), false);
        if (end != 0 && end < start) {
            throw new IllegalArgumentException("endTime is before startTime");
        }
        String status = row.get("status").toUpperCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + VALID_STATUSES + " but was '" + row.get("status") + "'");
        }
        if (STATUS_ACTIVE.equals(status) && end != 0) {
            throw new IllegalArgumentException("an ACTIVE shift cannot have an endTime");
        }
        if (!STATUS_ACTIVE.equals(status) && end == 0) {
            throw new IllegalArgumentException("a " + status + " shift needs an endTime");
        }
        ShiftRecord s = new ShiftRecord();
        s.setShiftId(id);
        s.setDriverId(driverId);
        s.setShuttleId(shuttleId);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setStatus(status);
        return s;
    }

    public static String[] toRow(ShiftRecord s) {
        return new String[]{
                nullToEmpty(s.getShiftId()),
                nullToEmpty(s.getDriverId()),
                nullToEmpty(s.getShuttleId()),
                String.valueOf(s.getStartTime()),
                s.getEndTime() == 0 ? "" : String.valueOf(s.getEndTime()),
                nullToEmpty(s.getStatus())
        };
    }

    private Map<String, ShiftRecord> readFromDisk() throws IOException {
        if (!Files.exists(file)) {
            new CsvTable(HEADER).save(file);
            return new LinkedHashMap<>();
        }
        CsvTable table = CsvTable.load(file, HEADER);
        Map<String, ShiftRecord> map = new LinkedHashMap<>();
        for (CsvTable.Row row : table.getRows()) {
            ShiftRecord s;
            try {
                s = fromRow(row);
            } catch (IllegalArgumentException e) {
                throw new CsvTable.CsvFormatException(file + " line " + row.lineNumber() + ": " + e.getMessage());
            }
            if (map.containsKey(s.getShiftId())) {
                throw new CsvTable.CsvFormatException(
                        file + " line " + row.lineNumber() + ": duplicate shiftId " + s.getShiftId());
            }
            map.put(s.getShiftId(), s);
        }
        return map;
    }

    private void persist(Map<String, ShiftRecord> data) throws IOException {
        buildTable(data.values()).save(file);
    }

    private static CsvTable buildTable(Collection<ShiftRecord> shifts) {
        CsvTable table = new CsvTable(HEADER);
        for (ShiftRecord s : shifts) {
            table.addRow(toRow(s));
        }
        return table;
    }

    private static Optional<ShiftRecord> activeShiftOf(Map<String, ShiftRecord> map, String driverId) {
        for (ShiftRecord s : map.values()) {
            if (s.getDriverId().equals(driverId) && STATUS_ACTIVE.equals(s.getStatus())) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static void checkSingleActive(Map<String, ShiftRecord> map, ShiftRecord added) {
        if (!STATUS_ACTIVE.equals(added.getStatus())) {
            return;
        }
        for (ShiftRecord other : map.values()) {
            if (!other.getShiftId().equals(added.getShiftId())
                    && other.getDriverId().equals(added.getDriverId())
                    && STATUS_ACTIVE.equals(other.getStatus())) {
                throw new IllegalStateException("Driver " + added.getDriverId()
                        + " already has an ACTIVE shift: " + other.getShiftId());
            }
        }
    }

    private static long parseMillis(String column, String raw, boolean required) {
        if (raw.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException(column + " is empty");
            }
            return 0;
        }
        try {
            long v = Long.parseLong(raw);
            if (v <= 0) {
                throw new IllegalArgumentException(column + " must be a positive epoch-millisecond value");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " is not a number: '" + raw + "'");
        }
    }

    private static ShiftRecord copyOf(ShiftRecord s) {
        CsvTable t = new CsvTable(HEADER);
        t.addRow(toRow(s));
        return fromRow(t.getRows().get(0));
    }

    private static List<ShiftRecord> copyAll(Collection<ShiftRecord> source) {
        List<ShiftRecord> out = new ArrayList<>(source.size());
        for (ShiftRecord s : source) {
            out.add(copyOf(s));
        }
        return out;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
