package com.shuttle.server.storage;

import com.shuttle.server.ServerConfig;
import com.shuttle.server.io.AtomicFileWriter;
import com.shuttle.server.repository.DatedShiftRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** One conditional R2 object write commits every CSV changed by a business operation. */
public final class CsvStorage {
    public static final List<String> TABLES = List.of("workers.txt", "shuttles.txt", "shifts.txt", "schedule.txt", "terminals.txt");
    private static final int MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024;
    private static final String COMMIT_ENTRY = "commit.txt";
    private static ObjectStore cloud;
    private static Map<Path, String> managed = Map.of();
    private static Map<String, String> committed = new LinkedHashMap<>();
    private static Map<String, String> staged;
    private static Thread owner;
    private static Path localJournal;
    private static boolean localRecoveryNeeded;
    private static String etag;
    private static boolean initialized;

    private CsvStorage() { }

    public static void configure() throws IOException {
        if (initialized) return;
        Map<Path, String> paths = new LinkedHashMap<>();
        paths.put(normalize(ServerConfig.getWorkersCsvPath()), "workers.txt");
        paths.put(normalize(ServerConfig.getShuttlesCsvPath()), "shuttles.txt");
        paths.put(normalize(ServerConfig.getShiftsCsvPath()), "shifts.txt");
        paths.put(normalize(ServerConfig.getScheduleCsvPath()), "schedule.txt");
        paths.put(normalize(ServerConfig.getDatedShiftsCsvPath()), DatedShiftRepository.FILE_NAME);
        paths.put(normalize(ServerConfig.getWorkersCsvPath().resolveSibling("terminals.txt")), "terminals.txt");
        if (ServerConfig.isR2()) {
            cloud = new R2ObjectClient(ServerConfig.getR2Endpoint(), ServerConfig.getR2Bucket(),
                    ServerConfig.getR2ObjectKey(), ServerConfig.getR2AccessKeyId(), ServerConfig.getR2SecretAccessKey(),
                    java.time.Clock.systemUTC(), java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build(),
                    ServerConfig.allowInsecureR2Localhost());
        } else {
            for (Path path : paths.keySet()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".txt")) continue;
                Path legacy = path.resolveSibling(name.substring(0, name.length() - 4) + ".csv");
                if (!Files.notExists(legacy)) {
                    // Same-directory rename preserves the exact bytes and refuses to overwrite a table.
                    Files.move(legacy, path);
                }
            }
        }
        managed = paths;
        if (!isCloud()) {
            localJournal = normalize(ServerConfig.getShiftsCsvPath()).resolveSibling("pending-tables.properties");
            localRecoveryNeeded = Files.exists(localJournal);
        }
        initialized = true;
    }

    public static boolean isCloud() { return cloud != null; }

    /** Called under Repositories.LOCK, before resolving request authentication. */
    public static boolean refresh() throws IOException {
        if (!isCloud()) {
            if (!localRecoveryNeeded) return false;
            java.util.Properties writes = new java.util.Properties();
            try (java.io.Reader reader = Files.newBufferedReader(localJournal)) {
                writes.load(reader);
            }
            for (var entry : managed.entrySet()) {
                String contents = writes.getProperty(entry.getValue());
                if (contents != null) AtomicFileWriter.writeAtomically(entry.getKey(), contents);
            }
            Files.delete(localJournal);
            localRecoveryNeeded = false;
            return true;
        }
        if (staged != null) throw new IllegalStateException("Cannot refresh during a storage transaction");
        ObjectStore.Versioned version = cloud.get();
        if (version == null) {
            if (etag != null) throw new IOException("Cloud state disappeared; refusing to initialize over missing data");
            return false;
        }
        if (version.etag().equals(etag)) return false;
        Map<String, String> fresh = decode(version.data());
        committed = fresh;
        etag = version.etag();
        return true;
    }

    public static void begin() {
        if (staged != null) throw new IllegalStateException("Nested storage transaction");
        staged = isCloud() ? new LinkedHashMap<>(committed) : new LinkedHashMap<>();
        owner = Thread.currentThread();
    }

    public static void commit() throws IOException {
        requireOwner();
        if (!isCloud()) {
            // Publish the complete write set before replacing any of its individual CSV files.
            // After an interruption refresh() finishes that same commit before servicing a reader.
            if (staged.size() > 1) {
                java.util.Properties writes = new java.util.Properties();
                writes.putAll(staged);
                java.io.StringWriter journal = new java.io.StringWriter();
                writes.store(journal, null);
                AtomicFileWriter.writeAtomically(localJournal, journal.toString());
                localRecoveryNeeded = true;
                refresh();
            } else if (!staged.isEmpty()) {
                for (var entry : managed.entrySet()) {
                    String contents = staged.get(entry.getValue());
                    if (contents != null) AtomicFileWriter.writeAtomically(entry.getKey(), contents);
                }
            }
            rollback();
            return;
        }
        try {
            if (staged.equals(committed)) return;
            if (!complete(staged)) {
                throw new IOException("Refusing an incomplete cloud state");
            }
            byte[] snapshot = encode(staged);
            String newEtag;
            try {
                newEtag = cloud.put(snapshot, etag);
            } catch (ObjectStore.ConflictException conflict) {
                throw conflict;
            } catch (IOException uncertain) {
                // PUT may have reached R2 before the connection failed. Never replay the mutation.
                // Exact snapshot equality includes a random commit ID, proving this was our write.
                try {
                    ObjectStore.Versioned observed = cloud.get();
                    if (observed == null || !Arrays.equals(snapshot, observed.data())) throw uncertain;
                    newEtag = observed.etag();
                } catch (IOException reconciliation) {
                    if (reconciliation != uncertain) uncertain.addSuppressed(reconciliation);
                    throw uncertain;
                }
            }
            committed = new LinkedHashMap<>(staged);
            etag = newEtag;
        } finally {
            staged = null;
            owner = null;
        }
    }

    public static boolean hasWrites() {
        return staged != null && (isCloud() ? !staged.equals(committed) : !staged.isEmpty());
    }

    public static void rollback() {
        staged = null;
        owner = null;
    }

    public static boolean exists(Path path) {
        String name = managed.get(normalize(path));
        if (name == null) return Files.exists(path);
        return current().containsKey(name) || (!isCloud() && Files.exists(path));
    }

    public static String read(Path path) throws IOException {
        String name = managed.get(normalize(path));
        if (name == null || (!isCloud() && !current().containsKey(name))) return AtomicFileWriter.readString(path);
        String text = current().get(name);
        if (text == null) throw new IOException("Missing cloud table: " + name);
        return text;
    }

    public static void write(Path path, String contents) throws IOException {
        String name = managed.get(normalize(path));
        if (name == null || (!isCloud() && staged == null)) {
            AtomicFileWriter.writeAtomically(path, contents);
            return;
        }
        requireOwner();
        staged.put(name, contents);
    }

    private static void requireOwner() {
        if (staged == null || Thread.currentThread() != owner) {
            throw new IllegalStateException("Cloud CSV writes require a repository transaction");
        }
    }

    private static Map<String, String> current() {
        return staged != null && Thread.currentThread() == owner ? staged : committed;
    }

    private static Path normalize(Path path) { return path.toAbsolutePath().normalize(); }

    private static boolean isTable(String name) {
        return TABLES.contains(name) || DatedShiftRepository.FILE_NAME.equals(name);
    }

    private static boolean complete(Map<String, String> tables) {
        return tables.keySet().containsAll(TABLES) && tables.keySet().stream().allMatch(CsvStorage::isTable);
    }

    public static byte[] encode(Map<String, String> tables) throws IOException {
        if (!complete(tables)) throw new IOException("Incomplete or unexpected cloud state");
        List<String> entries = new java.util.ArrayList<>(TABLES);
        if (tables.containsKey(DatedShiftRepository.FILE_NAME)) entries.add(DatedShiftRepository.FILE_NAME);
        String commitId = UUID.randomUUID().toString();
        long expandedBytes = commitId.getBytes(StandardCharsets.UTF_8).length;
        for (String name : entries) {
            String text = tables.get(name);
            if (text == null) throw new IOException("Missing table: " + name);
            expandedBytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (expandedBytes > MAX_SNAPSHOT_BYTES) throw new IOException("Expanded cloud state too large");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (String name : entries) {
                String text = tables.get(name);
                if (text == null) throw new IOException("Missing table: " + name);
                zip.putNextEntry(new ZipEntry(name));
                zip.write(text.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(COMMIT_ENTRY));
            zip.write(commitId.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        if (bytes.size() > MAX_SNAPSHOT_BYTES) throw new IOException("Cloud snapshot exceeds prototype storage limit");
        return bytes.toByteArray();
    }

    public static Map<String, String> decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_SNAPSHOT_BYTES) throw new IOException("Cloud snapshot too large");
        Map<String, String> tables = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                // Old R2 snapshots use the same table contents under .csv names.
                if (name.endsWith(".csv")) {
                    name = name.substring(0, name.length() - 4) + ".txt";
                    if (!isTable(name)) throw new IOException("Unexpected legacy table in cloud state");
                }
                if ((!isTable(name) && !COMMIT_ENTRY.equals(name)) || !seen.add(name) || entry.isDirectory()) {
                    throw new IOException("Unexpected or duplicate entry in cloud state");
                }
                byte[] data = zip.readNBytes(MAX_SNAPSHOT_BYTES + 1);
                total += data.length;
                if (total > MAX_SNAPSHOT_BYTES) throw new IOException("Expanded cloud state too large");
                if (isTable(name)) {
                    String text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(data)).toString();
                    tables.put(name, text);
                }
            }
        }
        if (!complete(tables)) throw new IOException("Incomplete cloud state");
        return tables;
    }
}
