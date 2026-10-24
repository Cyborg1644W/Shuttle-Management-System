package com.shuttle.server.repository;

import com.shuttle.common.ShuttleUnit;
import com.shuttle.common.ValidationUtil;
import com.shuttle.server.io.CsvTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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

public class ShuttleRepository {

    public static final String FILE_NAME = "shuttles.csv";

    public static final List<String> HEADER =
            List.of("shuttleId", "plateNumber", "capacity", "currentStatus");

    // Allowed values of currentStatus
    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_IN_SERVICE = "IN_SERVICE";
    public static final String STATUS_MAINTENANCE = "MAINTENANCE";
    public static final String STATUS_OFFLINE = "OFFLINE";
    private static final Set<String> VALID_STATUSES = new HashSet<>(Arrays.asList(
            STATUS_AVAILABLE, STATUS_IN_SERVICE, STATUS_MAINTENANCE, STATUS_OFFLINE));

    private final Path file;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<String, ShuttleUnit> byId = new LinkedHashMap<>();


    public ShuttleRepository(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        loadAllShuttles();
    }

    public List<ShuttleUnit> loadAllShuttles() throws IOException {
        Map<String, ShuttleUnit> fresh = readFromDisk();
        lock.writeLock().lock();
        try {
            byId = fresh;
            return copyAll(fresh.values());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ShuttleUnit> getAllShuttles() {
        lock.readLock().lock();
        try {
            return copyAll(byId.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<ShuttleUnit> findById(String shuttleId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(byId.get(shuttleId)).map(ShuttleRepository::copyOf);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<ShuttleUnit> findByPlateNumber(String plateNumber) {
        lock.readLock().lock();
        try {
            return findByPlateLocked(plateNumber, null).map(ShuttleRepository::copyOf);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ShuttleUnit> findByStatus(String status) {
        lock.readLock().lock();
        try {
            List<ShuttleUnit> result = new ArrayList<>();
            for (ShuttleUnit s : byId.values()) {
                if (s.getCurrentStatus().equalsIgnoreCase(status)) {
                    result.add(copyOf(s));
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean existsById(String shuttleId) {
        lock.readLock().lock();
        try {
            return byId.containsKey(shuttleId);
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

    public CsvTable toTable() {
        lock.readLock().lock();
        try {
            return buildTable(byId.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveShuttle(ShuttleUnit shuttle) throws IOException {
        saveAll(List.of(shuttle));
    }

    public void saveAll(Collection<ShuttleUnit> shuttles) throws IOException {
        List<ShuttleUnit> validated = new ArrayList<>();
        for (ShuttleUnit s : shuttles) {
            validated.add(copyOf(Objects.requireNonNull(s, "shuttle"))); // round-trip == validation
        }
        lock.writeLock().lock();
        try {
            Map<String, ShuttleUnit> next = new LinkedHashMap<>(byId);
            for (ShuttleUnit s : validated) {
                Optional<ShuttleUnit> samePlate = findPlateIn(next, s.getPlateNumber());
                if (samePlate.isPresent() && !samePlate.get().getShuttleId().equals(s.getShuttleId())) {
                    throw new IllegalStateException("Plate number already used by "
                            + samePlate.get().getShuttleId() + ": " + s.getPlateNumber());
                }
                next.put(s.getShuttleId(), s);
            }
            persist(next);
            byId = next;
        } finally {
            lock.writeLock().unlock();
        }
    }
    public Optional<ShuttleUnit> updateStatus(String shuttleId, String newStatus) throws IOException {
        String status = normaliseStatus(newStatus);
        lock.writeLock().lock();
        try {
            ShuttleUnit current = byId.get(shuttleId);
            if (current == null) {
                return Optional.empty();
            }
            ShuttleUnit updated = copyOf(current);
            updated.setCurrentStatus(status);
            Map<String, ShuttleUnit> next = new LinkedHashMap<>(byId);
            next.put(shuttleId, updated);
            persist(next);
            byId = next;
            return Optional.of(copyOf(updated));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean deleteShuttle(String shuttleId) throws IOException {
        lock.writeLock().lock();
        try {
            if (!byId.containsKey(shuttleId)) {
                return false;
            }
            Map<String, ShuttleUnit> next = new LinkedHashMap<>(byId);
            next.remove(shuttleId);
            persist(next);
            byId = next;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static ShuttleUnit fromRow(CsvTable.Row row) {
        String id = row.get("shuttleId");
        if (!ValidationUtil.isNotEmpty(id)) {
            throw new IllegalArgumentException("shuttleId is empty");
        }
        String plate = row.get("plateNumber");
        if (!ValidationUtil.isNotEmpty(plate)) {
            throw new IllegalArgumentException("plateNumber is empty");
        }
        int capacity;
        try {
            capacity = Integer.parseInt(row.get("capacity"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("capacity is not a whole number: '" + row.get("capacity") + "'");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be greater than 0");
        }
        ShuttleUnit s = new ShuttleUnit();
        s.setShuttleId(id);
        s.setPlateNumber(plate);
        s.setCapacity(capacity);
        s.setCurrentStatus(normaliseStatus(row.getOrDefault("currentStatus", STATUS_AVAILABLE)));
        return s;
    }

    public static String[] toRow(ShuttleUnit s) {
        return new String[]{
                nullToEmpty(s.getShuttleId()),
                nullToEmpty(s.getPlateNumber()),
                String.valueOf(s.getCapacity()),
                nullToEmpty(s.getCurrentStatus())
        };
    }

    private Map<String, ShuttleUnit> readFromDisk() throws IOException {
        if (!Files.exists(file)) {
            new CsvTable(HEADER).save(file); // first run: create an empty file with the header
            return new LinkedHashMap<>();
        }
        CsvTable table = CsvTable.load(file, HEADER);
        Map<String, ShuttleUnit> map = new LinkedHashMap<>();
        for (CsvTable.Row row : table.getRows()) {
            ShuttleUnit s;
            try {
                s = fromRow(row);
            } catch (IllegalArgumentException e) {
                throw new CsvTable.CsvFormatException(file + " line " + row.lineNumber() + ": " + e.getMessage());
            }
            if (map.containsKey(s.getShuttleId())) {
                throw new CsvTable.CsvFormatException(
                        file + " line " + row.lineNumber() + ": duplicate shuttleId " + s.getShuttleId());
            }
            if (findPlateIn(map, s.getPlateNumber()).isPresent()) {
                throw new CsvTable.CsvFormatException(
                        file + " line " + row.lineNumber() + ": duplicate plateNumber " + s.getPlateNumber());
            }
            map.put(s.getShuttleId(), s);
        }
        return map;
    }

    private void persist(Map<String, ShuttleUnit> data) throws IOException {
        buildTable(data.values()).save(file);
    }

    private static CsvTable buildTable(Collection<ShuttleUnit> shuttles) {
        CsvTable table = new CsvTable(HEADER);
        for (ShuttleUnit s : shuttles) {
            table.addRow(toRow(s));
        }
        return table;
    }

    private Optional<ShuttleUnit> findByPlateLocked(String plate, String ignoreId) {
        return findPlateIn(byId, plate).filter(s -> !s.getShuttleId().equals(ignoreId));
    }

    private static Optional<ShuttleUnit> findPlateIn(Map<String, ShuttleUnit> map, String plate) {
        if (plate == null) {
            return Optional.empty();
        }
        for (ShuttleUnit s : map.values()) {
            if (s.getPlateNumber().equalsIgnoreCase(plate.trim())) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static String normaliseStatus(String raw) {
        String status = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("currentStatus must be one of " + VALID_STATUSES + " but was '" + raw + "'");
        }
        return status;
    }

    private static ShuttleUnit copyOf(ShuttleUnit s) {
        CsvTable t = new CsvTable(HEADER);
        t.addRow(toRow(s));
        return fromRow(t.getRows().get(0));
    }

    private static List<ShuttleUnit> copyAll(Collection<ShuttleUnit> source) {
        List<ShuttleUnit> out = new ArrayList<>(source.size());
        for (ShuttleUnit s : source) {
            out.add(copyOf(s));
        }
        return out;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
