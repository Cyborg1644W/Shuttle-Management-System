package com.shuttle.server.repository;

import com.shuttle.common.Role;
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

public class InviteRepository {

    public static final String FILE_NAME = "invites.csv";

    public static final List<String> HEADER = List.of(
            "token", "invitedEmail", "role", "createdBy",
            "createdAt", "expiresAt", "status", "usedAt", "usedBy");

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REVOKED = "REVOKED";
    private static final Set<String> VALID_STATUSES = new HashSet<>(Arrays.asList(
            STATUS_PENDING, STATUS_USED, STATUS_EXPIRED, STATUS_REVOKED));

    public static final class Invite {
        private final String token;
        private final String invitedEmail;
        private final Role role;
        private final String createdBy;
        private final long createdAt;
        private final long expiresAt;
        private final String status;
        private final long usedAt;     // 0 = never
        private final String usedBy;   // "" = nobody

        public Invite(String token, String invitedEmail, Role role, String createdBy,
                      long createdAt, long expiresAt, String status, long usedAt, String usedBy) {
            this.token = token;
            this.invitedEmail = invitedEmail;
            this.role = role;
            this.createdBy = createdBy;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.status = status;
            this.usedAt = usedAt;
            this.usedBy = usedBy == null ? "" : usedBy;
        }

        public static Invite pending(String token, String invitedEmail, Role role,
                                     String createdBy, long createdAt, long expiresAt) {
            return new Invite(token, invitedEmail, role, createdBy, createdAt, expiresAt,
                    STATUS_PENDING, 0L, "");
        }

        public String getToken() { return token; }
        public String getInvitedEmail() { return invitedEmail; }
        public Role getRole() { return role; }
        public String getCreatedBy() { return createdBy; }
        public long getCreatedAt() { return createdAt; }
        public long getExpiresAt() { return expiresAt; }
        public String getStatus() { return status; }
        public long getUsedAt() { return usedAt; }
        public String getUsedBy() { return usedBy; }

        public boolean isUsable(long nowMillis) {
            return STATUS_PENDING.equals(status) && nowMillis < expiresAt;
        }

        public Invite withStatus(String newStatus) {
            return new Invite(token, invitedEmail, role, createdBy, createdAt, expiresAt, newStatus, usedAt, usedBy);
        }

        public Invite asUsed(long nowMillis, String redeemedBy) {
            return new Invite(token, invitedEmail, role, createdBy, createdAt, expiresAt,
                    STATUS_USED, nowMillis, redeemedBy);
        }
    }

    private final Path file;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<String, Invite> byToken = new LinkedHashMap<>(); // guarded by lock, replaced on write

    public InviteRepository(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        loadAllInvites();
    }


    public List<Invite> loadAllInvites() throws IOException {
        Map<String, Invite> fresh = readFromDisk();
        lock.writeLock().lock();
        try {
            byToken = fresh;
            return new ArrayList<>(fresh.values());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Invite> getAllInvites() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(byToken.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Invite> findByToken(String token) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(byToken.get(token));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Invite> findByInvitedEmail(String email) {
        lock.readLock().lock();
        try {
            List<Invite> result = new ArrayList<>();
            for (Invite i : byToken.values()) {
                if (i.getInvitedEmail().equalsIgnoreCase(email)) {
                    result.add(i);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Invite> findByStatus(String status) {
        lock.readLock().lock();
        try {
            List<Invite> result = new ArrayList<>();
            for (Invite i : byToken.values()) {
                if (i.getStatus().equalsIgnoreCase(status)) {
                    result.add(i);
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
            return byToken.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public CsvTable toTable() {
        lock.readLock().lock();
        try {
            return buildTable(byToken.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveInvite(Invite invite) throws IOException {
        Invite checked = validated(invite);
        lock.writeLock().lock();
        try {
            if (byToken.containsKey(checked.getToken())) {
                throw new IllegalStateException("Token already exists");
            }
            Map<String, Invite> next = new LinkedHashMap<>(byToken);
            next.put(checked.getToken(), checked);
            persist(next);
            byToken = next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void saveAll(Collection<Invite> invites) throws IOException {
        List<Invite> checked = new ArrayList<>();
        for (Invite i : invites) {
            checked.add(validated(i));
        }
        lock.writeLock().lock();
        try {
            Map<String, Invite> next = new LinkedHashMap<>(byToken);
            for (Invite i : checked) {
                next.put(i.getToken(), i);
            }
            persist(next);
            byToken = next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<Invite> consume(String token, String usedBy, long nowMillis) throws IOException {
        lock.writeLock().lock();
        try {
            Invite current = byToken.get(token);
            if (current == null || !current.isUsable(nowMillis)) {
                return Optional.empty();
            }
            Invite used = current.asUsed(nowMillis, usedBy);
            Map<String, Invite> next = new LinkedHashMap<>(byToken);
            next.put(token, used);
            persist(next);
            byToken = next;
            return Optional.of(used);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean revoke(String token) throws IOException {
        lock.writeLock().lock();
        try {
            Invite current = byToken.get(token);
            if (current == null || !STATUS_PENDING.equals(current.getStatus())) {
                return false;
            }
            Map<String, Invite> next = new LinkedHashMap<>(byToken);
            next.put(token, current.withStatus(STATUS_REVOKED));
            persist(next);
            byToken = next;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int expireStale(long nowMillis) throws IOException {
        lock.writeLock().lock();
        try {
            Map<String, Invite> next = new LinkedHashMap<>(byToken);
            int changed = 0;
            for (Invite i : byToken.values()) {
                if (STATUS_PENDING.equals(i.getStatus()) && nowMillis >= i.getExpiresAt()) {
                    next.put(i.getToken(), i.withStatus(STATUS_EXPIRED));
                    changed++;
                }
            }
            if (changed > 0) {
                persist(next);
                byToken = next;
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static Invite fromRow(CsvTable.Row row) {
        String token = row.get("token");
        if (!ValidationUtil.isNotEmpty(token)) {
            throw new IllegalArgumentException("token is empty");
        }
        String email = row.get("invitedEmail");
        if (!ValidationUtil.isValidEmail(email)) {
            throw new IllegalArgumentException("invitedEmail is not valid: '" + email + "'");
        }
        Role role;
        try {
            role = Role.valueOf(row.get("role").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("role must be DRIVER or OPERATOR but was '" + row.get("role") + "'");
        }
        if (!ValidationUtil.isNotEmpty(row.get("createdBy"))) {
            throw new IllegalArgumentException("createdBy is empty");
        }
        long createdAt = parseMillis("createdAt", row.get("createdAt"), true);
        long expiresAt = parseMillis("expiresAt", row.get("expiresAt"), true);
        if (expiresAt <= createdAt) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        String status = row.get("status").toUpperCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + VALID_STATUSES + " but was '" + row.get("status") + "'");
        }
        long usedAt = parseMillis("usedAt", row.get("usedAt"), false);
        if (STATUS_USED.equals(status) && usedAt == 0) {
            throw new IllegalArgumentException("a USED invite needs usedAt");
        }
        return new Invite(token, email, role, row.get("createdBy"), createdAt, expiresAt,
                status, usedAt, row.get("usedBy"));
    }

    public static String[] toRow(Invite i) {
        return new String[]{
                i.getToken(),
                i.getInvitedEmail(),
                i.getRole() == null ? "" : i.getRole().name(),
                i.getCreatedBy() == null ? "" : i.getCreatedBy(),
                String.valueOf(i.getCreatedAt()),
                String.valueOf(i.getExpiresAt()),
                i.getStatus() == null ? "" : i.getStatus(),
                i.getUsedAt() == 0 ? "" : String.valueOf(i.getUsedAt()),
                i.getUsedBy()
        };
    }

    private Map<String, Invite> readFromDisk() throws IOException {
        if (!Files.exists(file)) {
            new CsvTable(HEADER).save(file);
            return new LinkedHashMap<>();
        }
        CsvTable table = CsvTable.load(file, HEADER);
        Map<String, Invite> map = new LinkedHashMap<>();
        for (CsvTable.Row row : table.getRows()) {
            Invite i;
            try {
                i = fromRow(row);
            } catch (IllegalArgumentException e) {
                throw new CsvTable.CsvFormatException(file + " line " + row.lineNumber() + ": " + e.getMessage());
            }
            if (map.containsKey(i.getToken())) {
                throw new CsvTable.CsvFormatException(file + " line " + row.lineNumber() + ": duplicate token");
            }
            map.put(i.getToken(), i);
        }
        return map;
    }

    private void persist(Map<String, Invite> data) throws IOException {
        buildTable(data.values()).save(file);
    }

    private static CsvTable buildTable(Collection<Invite> invites) {
        CsvTable table = new CsvTable(HEADER);
        for (Invite i : invites) {
            table.addRow(toRow(i));
        }
        return table;
    }

    private static Invite validated(Invite invite) {
        Objects.requireNonNull(invite, "invite");
        CsvTable t = new CsvTable(HEADER);
        t.addRow(toRow(invite));
        return fromRow(t.getRows().get(0));
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
}
