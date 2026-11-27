"""Direct Java snapshot codec regressions, without third-party test dependencies.

Build :server-backend:installDist first and run unittest discovery. Override
SHUTTLE_SERVER_LIB_DIR when the distribution is outside the checkout.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


HARNESS = r'''
import com.shuttle.server.storage.CsvStorage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class StorageCodecRegression {
    private static final int LIMIT = 32 * 1024 * 1024;
    private static final int COMMIT_BYTES = 36;

    interface Checked { void run() throws Exception; }

    private static void rejectIOException(Checked operation) throws Exception {
        try {
            operation.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Invalid snapshot was accepted");
    }

    private static Map<String, String> tables(String workers) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : CsvStorage.TABLES) result.put(table, "");
        result.put("workers.txt", workers);
        return result;
    }

    private static byte[] archive(byte[] workers) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (String table : CsvStorage.TABLES) {
                zip.putNextEntry(new ZipEntry(table));
                if (table.equals("workers.txt")) zip.write(workers);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("commit.txt"));
            zip.write("00000000-0000-0000-0000-000000000000".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "create_fixture": {
                java.nio.file.Path dir = java.nio.file.Path.of(args[1]);
                java.nio.file.Files.createDirectories(dir);
                new com.shuttle.server.repository.WorkerRepository(dir.resolve("workers.txt"));
                new com.shuttle.server.repository.ShuttleRepository(dir.resolve("shuttles.txt"));
                new com.shuttle.server.repository.ShiftRepository(dir.resolve("shifts.txt"));
                new com.shuttle.server.repository.ScheduleRepository(dir.resolve("schedule.txt"));
                new com.shuttle.server.repository.TerminalRepository(dir.resolve("terminals.txt"));
                break;
            }
            case "at_limit": {
                Map<String, String> original = tables("A".repeat(LIMIT - COMMIT_BYTES));
                byte[] encoded = CsvStorage.encode(original);
                if (encoded.length >= LIMIT) throw new AssertionError("Fixture must be highly compressible");
                if (!original.equals(CsvStorage.decode(encoded))) throw new AssertionError("Boundary roundtrip failed");
                break;
            }
            case "over_limit_marker":
                // CSV bytes alone fit; adding the UUID commit marker exceeds the aggregate limit by one.
                rejectIOException(() -> CsvStorage.encode(tables("A".repeat(LIMIT - COMMIT_BYTES + 1))));
                break;
            case "over_limit_utf8":
                // Character count is half the limit, while UTF-8 bytes plus metadata exceed it.
                rejectIOException(() -> CsvStorage.encode(tables("é".repeat(LIMIT / 2))));
                break;
            case "malformed_utf8":
                rejectIOException(() -> CsvStorage.decode(archive(new byte[] {(byte) 0xc3, (byte) 0x28})));
                break;
            case "inflated_zip": {
                byte[] encoded = archive("A".repeat(LIMIT - COMMIT_BYTES + 1).getBytes(StandardCharsets.UTF_8));
                if (encoded.length >= LIMIT) throw new AssertionError("Fixture must be highly compressible");
                rejectIOException(() -> CsvStorage.decode(encoded));
                break;
            }
            case "unicode_roundtrip": {
                Map<String, String> original = tables("employeeID,fullName\nEMP-1,José 李\n");
                original.put("terminals.txt", "terminalId,name\nEMP-2,Terminal 🚐\n");
                if (!original.equals(CsvStorage.decode(CsvStorage.encode(original)))) {
                    throw new AssertionError("UTF-8 snapshot roundtrip failed");
                }
                break;
            }
            case "legacy_table_names": {
                Map<String, String> original = tables("employeeID,fullName\nEMP-1,\"Last, First\"\n");
                original.put("dated-shifts.txt", "shiftDate,startTime\n2030-01-01,09:00\n");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                    for (var entry : original.entrySet()) {
                        zip.putNextEntry(new ZipEntry(entry.getKey().replace(".txt", ".csv")));
                        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (!original.equals(CsvStorage.decode(bytes.toByteArray()))) {
                    throw new AssertionError("Legacy table names must resolve to the original contents");
                }
                break;
            }
            default:
                throw new AssertionError("Unknown regression case");
        }
        System.out.println("PASS " + args[0]);
    }
}
'''


class StorageCodecTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        default = Path(__file__).resolve().parents[1] / "build/install/server-backend/lib"
        cls.lib_dir = Path(os.environ.get("SHUTTLE_SERVER_LIB_DIR", default)).resolve()
        if not list(cls.lib_dir.glob("*.jar")):
            raise RuntimeError("Build :server-backend:installDist or set SHUTTLE_SERVER_LIB_DIR first")
        java_home = os.environ.get("JAVA_HOME")
        cls.java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
        if not cls.java or not javac:
            raise RuntimeError("A full JDK is required for storage codec regression tests")
        cls.temp = tempfile.TemporaryDirectory(prefix="shuttle-codec-test-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        source = cls.root / "StorageCodecRegression.java"
        source.write_text(HARNESS, encoding="utf-8")
        compilation = subprocess.run(
            [javac, "-encoding", "UTF-8", "-cp", str(cls.lib_dir / "*"), str(source)],
            cwd=cls.root, capture_output=True, text=True, timeout=30,
        )
        if compilation.returncode:
            raise RuntimeError("Codec harness compilation failed: " + compilation.stderr)
        cls.classpath = os.pathsep.join((str(cls.root), str(cls.lib_dir / "*")))

    def run_case(self, name):
        result = subprocess.run(
            [self.java, "-Xmx384m", "-cp", self.classpath, "StorageCodecRegression", name],
            cwd=self.root, capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "PASS " + name)

    def test_exact_expanded_limit_including_commit_marker_roundtrips(self):
        self.run_case("at_limit")

    def test_encode_counts_commit_marker_against_expanded_limit(self):
        self.run_case("over_limit_marker")

    def test_encode_bounds_utf8_bytes_instead_of_character_count(self):
        self.run_case("over_limit_utf8")

    def test_decode_rejects_malformed_utf8_in_table(self):
        self.run_case("malformed_utf8")

    def test_decode_rejects_highly_compressed_overexpanded_snapshot(self):
        self.run_case("inflated_zip")

    def test_unicode_tables_roundtrip_without_replacement(self):
        self.run_case("unicode_roundtrip")

    def test_old_archive_including_dated_shifts_keeps_all_table_contents(self):
        self.run_case("legacy_table_names")

    def admin_fixture(self):
        directory = self.root / self._testMethodName
        result = subprocess.run(
            [self.java, "-cp", self.classpath, "StorageCodecRegression", "create_fixture", str(directory)],
            cwd=self.root, capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(list(directory.glob("*.txt"))), 5)
        return directory

    def run_admin(self, directory):
        environment = {name: os.environ[name] for name in ("PATH", "JAVA_HOME", "LANG", "LC_ALL") if name in os.environ}
        environment["STORAGE_MODE"] = "local"
        return subprocess.run(
            [self.java, "-cp", self.classpath, "com.shuttle.server.storage.StorageAdmin", "validate", str(directory)],
            cwd=self.root, env=environment, capture_output=True, text=True, timeout=10,
        )

    @staticmethod
    def directory_contents(directory):
        return {str(path.relative_to(directory)): path.read_bytes() for path in directory.rglob("*") if path.is_file()}

    def test_admin_validate_accepts_five_tables_without_changing_files(self):
        directory = self.admin_fixture()
        before = self.directory_contents(directory)
        result = self.run_admin(directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Validated 5 CSV tables", result.stdout)
        self.assertEqual(self.directory_contents(directory), before)

    def test_admin_validate_rejects_missing_table_without_creating_it(self):
        directory = self.admin_fixture()
        (directory / "terminals.txt").unlink()
        before = self.directory_contents(directory)
        result = self.run_admin(directory)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.directory_contents(directory), before)

    def test_admin_validate_rejects_invalid_schema_without_changing_files(self):
        directory = self.admin_fixture()
        (directory / "workers.txt").write_text("not_a_worker_header\n", encoding="utf-8")
        before = self.directory_contents(directory)
        result = self.run_admin(directory)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.directory_contents(directory), before)


if __name__ == "__main__":
    unittest.main()
