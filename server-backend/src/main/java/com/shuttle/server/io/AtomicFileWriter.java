package com.shuttle.server.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public final class AtomicFileWriter {

    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private static final String TMP_SUFFIX = ".tmp";
    private static final String LOCK_SUFFIX = ".lock";

    private AtomicFileWriter() {

    }


    @SuppressWarnings("try")
    public static void writeAtomically(Path targetPath, String content) throws IOException {
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(content, "content");

        Path target = targetPath.toAbsolutePath().normalize();
        Path dir = target.getParent();
        Files.createDirectories(dir);

        Path lockFile = dir.resolve(target.getFileName() + LOCK_SUFFIX);
        Path tmpFile = dir.resolve(target.getFileName() + TMP_SUFFIX);

        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(target, p -> new ReentrantLock());
        jvmLock.lock();
        try (FileChannel lockChannel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock osLock = lockChannel.lock()) {
            try {
                writeAndSync(tmpFile, content);
                swap(tmpFile, target);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        } finally {
            jvmLock.unlock();
        }
    }

    public static String readString(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path target = path.toAbsolutePath().normalize();

        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(target, p -> new ReentrantLock());
        jvmLock.lock();
        try {
            return new String(Files.readAllBytes(target), StandardCharsets.UTF_8); // NoSuchFileException if absent
        } finally {
            jvmLock.unlock();
        }
    }

    private static void writeAndSync(Path tmpFile, String content) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(tmpFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void swap(Path tmpFile, Path target) throws IOException {
        try {
            Files.move(tmpFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
