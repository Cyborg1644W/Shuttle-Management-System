package com.shuttle.mobile.client;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class OfflineQueueManager {

    private static final String FILE_NAME = "offline_queue.txt";
    private static final int MAX_ENTRIES = 1000; // keeps the file from growing forever

    public interface Sender {
        boolean send(String json);
    }

    private final File file;

    public OfflineQueueManager(Context context) {
        this.file = new File(context.getFilesDir(), FILE_NAME);
    }

    /** Adds one entry to the end of the queue file. */
    public synchronized void enqueue(String json) {
        List<String> entries = readAll();
        entries.add(json.replace("\n", " ")); // keep one entry per line
        // Drop the oldest if too many
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        writeAll(entries);
    }

    /** Returns all queued entries, oldest first. */
    public synchronized List<String> readAll() {
        List<String> list = new ArrayList<>();
        if (!file.exists()) return list;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) list.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }

    public synchronized int size() {
        return readAll().size();
    }

    public synchronized boolean isEmpty() {
        return size() == 0;
    }

    public synchronized void clear() {
        if (file.exists()) file.delete();
    }

    public synchronized int flush(Sender sender) {
        List<String> entries = readAll();
        if (entries.isEmpty()) return 0;

        int sent = 0;
        for (String entry : entries) {
            if (!sender.send(entry)) break;
            sent++;
        }

        if (sent == entries.size()) {
            clear();
        } else if (sent > 0) {
            writeAll(new ArrayList<>(entries.subList(sent, entries.size())));
        }
        return sent;
    }

    private void writeAll(List<String> entries) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
            for (String e : entries) {
                writer.write(e);
                writer.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}