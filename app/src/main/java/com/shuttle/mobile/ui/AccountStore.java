package com.shuttle.mobile.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.shuttle.common.Role;

// TEMPORARY: saves accounts on this phone only.
// Replace with the backend when it's ready.
public class AccountStore {
    // userId is an email or a full phone number like +639175521092
    private String keyFor(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        if (userId.startsWith("+")) {
            return prefs.getString("phone:" + userId, null);
        }
        return emailKey(userId);
    }

    public String getName(String userId) {
        String key = keyFor(userId);
        return key == null ? null : prefs.getString("name:" + key, null);
    }

    public String getEmployeeId(String userId) {
        String key = keyFor(userId);
        return key == null ? null : prefs.getString("emp:" + key, null);
    }

    public String getEmail(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        if (userId.startsWith("+")) {
            return prefs.getString("phone:" + userId, null);
        }
        return emailKey(userId);
    }
    private final SharedPreferences prefs;

    public AccountStore(Context context) {
        prefs = context.getSharedPreferences("fleetflow_accounts", Context.MODE_PRIVATE);
    }

    private String emailKey(String email) {
        return email.trim().toLowerCase();
    }

    public boolean phoneExists(String fullPhone) {
        return prefs.contains("phone:" + fullPhone);
    }

    public boolean emailExists(String email) {
        return prefs.contains("pw:" + emailKey(email));
    }

    public void save(String name, String employeeId, String fullPhone,
                     String email, String password, Role role) {
        String key = emailKey(email);
        prefs.edit()
                .putString("phone:" + fullPhone, key)
                .putString("pw:" + key, password)
                .putString("role:" + key, role.name())
                .putString("name:" + key, name)
                .putString("emp:" + key, employeeId)
                .apply();
    }

    // Returns the role if this phone number is registered, otherwise null
    public Role getRoleByPhone(String fullPhone) {
        String key = prefs.getString("phone:" + fullPhone, null);
        if (key == null) {
            return null;
        }
        return roleFor(key);
    }

    // Returns the role if the email and password match, otherwise null
    public Role getRoleByEmail(String email, String password) {
        String key = emailKey(email);
        String savedPassword = prefs.getString("pw:" + key, null);
        if (savedPassword != null && savedPassword.equals(password)) {
            return roleFor(key);
        }
        return null;
    }

    private Role roleFor(String key) {
        String role = prefs.getString("role:" + key, null);
        if (role == null) {
            return null;
        }
        return Role.valueOf(role);
    }

    public void setName(String userId, String name) {
        String key = keyFor(userId);
        if (key != null) {
            prefs.edit().putString("name:" + key, name).apply();
        }
    }
}