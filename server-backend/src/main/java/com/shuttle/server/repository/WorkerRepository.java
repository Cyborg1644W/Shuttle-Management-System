package com.shuttle.server.repository;

import com.shuttle.common.Driver;
import com.shuttle.common.Operator;
import com.shuttle.common.Role;
import com.shuttle.common.ValidationUtil;
import com.shuttle.common.Worker;
import com.shuttle.server.io.CsvTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

public class WorkerRepository {

    public static final String FILE_NAME = "workers.csv";

    public static final List<String> HEADER = List.of(
            "employeeID", "fullName", "email", "passwordHash", "role",
            "employmentStatus", "shiftStatus",
            // Driver-only
            "licenseNumber", "licenseExpirationDate", "assignedShuttleId", "activeShiftId",
            // Operator-only
            "terminalZone", "permissionsMask", "managedShuttleIds");

    // Allowed values

    public static final String EMPLOYMENT_ACTIVE = "ACTIVE";
    public static final String EMPLOYMENT_INACTIVE = "INACTIVE";
    public static final String EMPLOYMENT_SUSPENDED = "SUSPENDED";
    private static final Set<String> EMPLOYMENT_STATUSES = new HashSet<>(Arrays.asList(
            EMPLOYMENT_ACTIVE, EMPLOYMENT_INACTIVE, EMPLOYMENT_SUSPENDED));

    public static final String SHIFT_ON = "ON_SHIFT";
    public static final String SHIFT_OFF = "OFF_SHIFT";
    private static final Set<String> SHIFT_STATUSES = new HashSet<>(Arrays.asList(SHIFT_ON, SHIFT_OFF));

    private static final String LIST_SEPARATOR = ";";
    private static final String ID_PREFIX = "EMP-";

    private final Path file;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<String, Worker> byId = new LinkedHashMap<>();

    public WorkerRepository(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        loadAllWorkers();
    }

    public List<Worker> loadAllWorkers() throws IOException {
        Map<String, Worker> fresh = readFromDisk();
        lock.writeLock().lock();
        try {
            byId = fresh;
            return copyAll(fresh.values());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Worker> getAllWorkers() {
        lock.readLock().lock();
        try {
            return copyAll(byId.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Worker> findById(String employeeId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(byId.get(employeeId)).map(WorkerRepository::copyOf);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Worker> findByEmail(String email) {
        lock.readLock().lock();
        try {
            return findEmailIn(byId, email).map(WorkerRepository::copyOf);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Worker> findByRole(Role role) {
        lock.readLock().lock();
        try {
            List<Worker> result = new ArrayList<>();
            for (Worker w : byId.values()) {
                if (roleOf(w) == role) {
                    result.add(copyOf(w));
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean existsById(String employeeId) {
        lock.readLock().lock();
        try {
            return byId.containsKey(employeeId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean existsByEmail(String email) {
        lock.readLock().lock();
        try {
            return findEmailIn(byId, email).isPresent();
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

    public String nextEmployeeId() {
        lock.readLock().lock();
        try {
            int max = 0;
            for (String id : byId.keySet()) {
                if (id.startsWith(ID_PREFIX)) {
                    try {
                        max = Math.max(max, Integer.parseInt(id.substring(ID_PREFIX.length())));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return String.format(Locale.ROOT, "%s%04d", ID_PREFIX, max + 1);
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


    public void saveWorker(Worker worker) throws IOException {
        saveAll(List.of(worker));
    }

    public void saveAll(Collection<Worker> workers) throws IOException {
        List<Worker> validated = new ArrayList<>();
        for (Worker w : workers) {
            validated.add(copyOf(Objects.requireNonNull(w, "worker"))); // round-trip == validation
        }
        lock.writeLock().lock();
        try {
            Map<String, Worker> next = new LinkedHashMap<>(byId);
            for (Worker w : validated) {
                Optional<Worker> sameEmail = findEmailIn(next, w.getEmail());
                if (sameEmail.isPresent() && !sameEmail.get().getEmployeeID().equals(w.getEmployeeID())) {
                    throw new IllegalStateException("E-mail already in use by "
                            + sameEmail.get().getEmployeeID() + ": " + w.getEmail());
                }
                next.put(w.getEmployeeID(), w);
            }
            persist(next);
            byId = next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean deleteWorker(String employeeId) throws IOException {
        lock.writeLock().lock();
        try {
            if (!byId.containsKey(employeeId)) {
                return false;
            }
            Map<String, Worker> next = new LinkedHashMap<>(byId);
            next.remove(employeeId);
            persist(next);
            byId = next;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static Worker fromRow(CsvTable.Row row) {
        String id = row.get("employeeID");
        if (!ValidationUtil.isNotEmpty(id) || id.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("employeeID is empty or contains spaces");
        }
        if (!ValidationUtil.isNotEmpty(row.get("fullName"))) {
            throw new IllegalArgumentException("fullName is empty");
        }
        String email = row.get("email");
        if (!ValidationUtil.isValidEmail(email)) {
            throw new IllegalArgumentException("email is not valid: '" + email + "'");
        }
        if (!ValidationUtil.isNotEmpty(row.get("passwordHash"))) {
            throw new IllegalArgumentException("passwordHash is empty");
        }

        Role role;
        try {
            role = Role.valueOf(row.get("role").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("role must be DRIVER or OPERATOR but was '" + row.get("role") + "'");
        }

        Worker worker;
        if (role == Role.DRIVER) {
            Driver d = new Driver();
            String license = row.get("licenseNumber");
            if (!ValidationUtil.isNotEmpty(license)) {
                throw new IllegalArgumentException("licenseNumber is required for a DRIVER");
            }
            d.setLicenseNumber(license);
            String expiry = row.get("licenseExpirationDate");
            if (!expiry.isEmpty()) {
                try {
                    LocalDate.parse(expiry); // ISO-8601 yyyy-MM-dd
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("licenseExpirationDate must be yyyy-MM-dd but was '" + expiry + "'");
                }
            }
            d.setLicenseExpirationDate(emptyToNull(expiry));
            d.setAssignedShuttleId(emptyToNull(row.get("assignedShuttleId")));
            d.setActiveShiftId(emptyToNull(row.get("activeShiftId")));
            worker = d;
        } else {
            Operator o = new Operator();
            o.setTerminalZone(emptyToNull(row.get("terminalZone")));
            String mask = row.getOrDefault("permissionsMask", "0");
            try {
                o.setPermissionsMask(Integer.parseInt(mask));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("permissionsMask is not a whole number: '" + mask + "'");
            }
            List<String> managed = new ArrayList<>();
            for (String part : row.get("managedShuttleIds").split(LIST_SEPARATOR)) {
                if (!part.trim().isEmpty()) {
                    managed.add(part.trim());
                }
            }
            o.setManagedShuttleIds(managed);
            worker = o;
        }

        worker.setEmployeeID(id);
        worker.setFullName(row.get("fullName"));
        worker.setEmail(email);
        worker.setPasswordHash(row.get("passwordHash"));
        worker.setEmploymentStatus(checkedStatus("employmentStatus",
                row.getOrDefault("employmentStatus", EMPLOYMENT_ACTIVE), EMPLOYMENT_STATUSES));
        worker.setShiftStatus(checkedStatus("shiftStatus",
                row.getOrDefault("shiftStatus", SHIFT_OFF), SHIFT_STATUSES));
        return worker;
    }

    public static String[] toRow(Worker w) {
        String[] c = new String[HEADER.size()];
        Arrays.fill(c, "");
        c[0] = nullToEmpty(w.getEmployeeID());
        c[1] = nullToEmpty(w.getFullName());
        c[2] = nullToEmpty(w.getEmail());
        c[3] = nullToEmpty(w.getPasswordHash());
        c[4] = roleOf(w).name();
        c[5] = nullToEmpty(w.getEmploymentStatus());
        c[6] = nullToEmpty(w.getShiftStatus());
        if (w instanceof Driver) {
            Driver d = (Driver) w;
            c[7] = nullToEmpty(d.getLicenseNumber());
            c[8] = nullToEmpty(d.getLicenseExpirationDate());
            c[9] = nullToEmpty(d.getAssignedShuttleId());
            c[10] = nullToEmpty(d.getActiveShiftId());
        } else {
            Operator o = (Operator) w;
            c[11] = nullToEmpty(o.getTerminalZone());
            c[12] = String.valueOf(o.getPermissionsMask());
            c[13] = o.getManagedShuttleIds() == null ? ""
                    : String.join(LIST_SEPARATOR, o.getManagedShuttleIds());
        }
        return c;
    }


    private Map<String, Worker> readFromDisk() throws IOException {
        if (!Files.exists(file)) {
            new CsvTable(HEADER).save(file);
            return new LinkedHashMap<>();
        }
        CsvTable table = CsvTable.load(file, HEADER);
        Map<String, Worker> map = new LinkedHashMap<>();
        for (CsvTable.Row row : table.getRows()) {
            Worker w;
            try {
                w = fromRow(row);
            } catch (IllegalArgumentException e) {
                throw new CsvTable.CsvFormatException(file + " line " + row.lineNumber() + ": " + e.getMessage());
            }
            if (map.containsKey(w.getEmployeeID())) {
                throw new CsvTable.CsvFormatException(
                        file + " line " + row.lineNumber() + ": duplicate employeeID " + w.getEmployeeID());
            }
            if (findEmailIn(map, w.getEmail()).isPresent()) {
                throw new CsvTable.CsvFormatException(
                        file + " line " + row.lineNumber() + ": duplicate email " + w.getEmail());
            }
            map.put(w.getEmployeeID(), w);
        }
        return map;
    }

    private void persist(Map<String, Worker> data) throws IOException {
        buildTable(data.values()).save(file);
    }

    private static CsvTable buildTable(Collection<Worker> workers) {
        CsvTable table = new CsvTable(HEADER);
        for (Worker w : workers) {
            table.addRow(toRow(w));
        }
        return table;
    }

    private static Optional<Worker> findEmailIn(Map<String, Worker> map, String email) {
        if (email == null) {
            return Optional.empty();
        }
        for (Worker w : map.values()) {
            if (w.getEmail().equalsIgnoreCase(email.trim())) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    private static Role roleOf(Worker w) {
        if (w instanceof Driver) {
            return Role.DRIVER;
        }
        if (w instanceof Operator) {
            return Role.OPERATOR;
        }
        throw new IllegalArgumentException("Unsupported Worker subclass: " + w.getClass().getName());
    }

    private static String checkedStatus(String column, String raw, Set<String> allowed) {
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(column + " must be one of " + allowed + " but was '" + raw + "'");
        }
        return v;
    }

    private static Worker copyOf(Worker w) {
        CsvTable t = new CsvTable(HEADER);
        t.addRow(toRow(w));
        return fromRow(t.getRows().get(0));
    }

    private static List<Worker> copyAll(Collection<Worker> source) {
        List<Worker> out = new ArrayList<>(source.size());
        for (Worker w : source) {
            out.add(copyOf(w));
        }
        return out;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
