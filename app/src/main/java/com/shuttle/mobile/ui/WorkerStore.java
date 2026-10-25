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
 * Stand-in worker storage. Replace with the Worker Management version if your teammate has one.
 * Saves workers in workers.txt (internal storage), one per line:
 * name|operator|vehicle|terminal|status|empId|phone
 */
public class WorkerStore {

    private static final String FILE_NAME = "workers.txt";

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("|", " ").replace("\n", " ");
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
                StaffMember m = new StaffMember(p[0], Boolean.parseBoolean(p[1]), p[2], p[3], p[4]);
                if (p.length >= 7) {
                    m.empId = p[5];
                    m.phone = p[6];
                }
                list.add(m);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }

    public static synchronized void saveAll(Context c, ArrayList<StaffMember> list) {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file(c), false))) {
            for (StaffMember m : list) {
                w.write(clean(m.name) + "|" + m.operator + "|" + clean(m.value1) + "|"
                        + clean(m.value2) + "|" + clean(m.status) + "|"
                        + clean(m.empId) + "|" + clean(m.phone));
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

    // Keeps the Worker Management list in sync when someone edits their own name
    public static synchronized void rename(Context context, String oldName, String newName) {
        ArrayList<StaffMember> list = load(context);
        for (StaffMember w : list) {
            if (w.name.equalsIgnoreCase(oldName.trim())) {
                w.name = newName;
                saveAll(context, list);
                return;
            }
        }
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

    // ---------- Used by RegisterActivity ----------

    /** True if a worker with this employee ID already exists (case-insensitive). */
    public static synchronized boolean empIdExists(Context c, String empId) {
        if (empId == null) return false;
        String wanted = empId.trim();
        for (StaffMember m : load(c)) {
            if (m.empId != null && m.empId.equalsIgnoreCase(wanted)) return true;
        }
        return false;
    }

    /** Adds a newly registered account to the worker list, off duty and unassigned. */
    public static synchronized void addRegistered(Context c, String name, String empId,
                                                  String phone, boolean operator) {
        add(c, new StaffMember(name, operator, "Unassigned",
                operator ? "Not set" : "Unassigned", "OFF DUTY", empId, phone));
    }

    /** "+63", "9171234567" -> "+63 917 123 4567". Singapore (8 digits): "+65 9123 4567". */
    public static String formatPhone(String countryCode, String digits) {
        if (digits == null) digits = "";
        if (digits.length() == 10) {
            return countryCode + " " + digits.substring(0, 3) + " "
                    + digits.substring(3, 6) + " " + digits.substring(6);
        }
        if (digits.length() == 8) {
            return countryCode + " " + digits.substring(0, 4) + " " + digits.substring(4);
        }
        return countryCode + " " + digits;
    }
}