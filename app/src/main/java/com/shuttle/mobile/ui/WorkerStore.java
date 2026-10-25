package com.shuttle.mobile.ui;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

/**
 * TEMPORARY stand-in. Replace with the Worker Management version if your teammate has one.
 * Saves workers in workers.txt (internal storage), one per line: name|operator|vehicle|terminal|status
 */
public class WorkerStore {

    private static final String FILE_NAME = "workers.txt";

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    public static synchronized ArrayList<StaffMember> load(Context c) {
        ArrayList<StaffMember> list = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return list;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|", -1);
                if (p.length < 5) continue;
                list.add(new StaffMember(p[0], Boolean.parseBoolean(p[1]), p[2], p[3], p[4]));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }

    public static synchronized void saveAll(Context c, ArrayList<StaffMember> list) {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file(c), false))) {
            for (StaffMember m : list) {
                w.write(m.name + "|" + m.operator + "|" + m.value1 + "|" + m.value2 + "|" + m.status);
                w.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void add(Context c, StaffMember m) {
        ArrayList<StaffMember> list = load(c);
        list.add(m);
        saveAll(c, list);
    }

    /** Returns the matching worker, or null if none (case-insensitive). */
    public static synchronized StaffMember findByName(Context c, String name) {
        if (name == null) return null;
        for (StaffMember m : load(c)) {
            if (m.name.equalsIgnoreCase(name.trim())) return m;
        }
        return null;
    }

    public static synchronized void setStatusByName(Context c, String name, String status) {
        ArrayList<StaffMember> list = load(c);
        boolean changed = false;
        for (StaffMember m : list) {
            if (m.name.equalsIgnoreCase(name.trim())) {
                m.status = status;
                changed = true;
            }
        }
        if (changed) saveAll(c, list);
    }
}
