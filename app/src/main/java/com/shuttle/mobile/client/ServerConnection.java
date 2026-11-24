package com.shuttle.mobile.client;

import android.content.Context;
import android.content.SharedPreferences;

import com.shuttle.mobile.BuildConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** The server selected on this phone. Accounts and invitations belong to that server. */
public final class ServerConnection {
    private static final String PREFERENCES = "server_connection";
    private static final String ADDRESS = "api_address";
    private static final String USE_CUSTOM = "use_custom_server";
    public static final String DEFAULT_URL = normalize(BuildConfig.API_BASE_URL);
    private static volatile String baseUrl = DEFAULT_URL;

    private ServerConnection() { }

    public static synchronized void initialize(Context context) {
        SharedPreferences prefs = preferences(context);
        // Older releases saved only ADDRESS. Keep it available in the dialog, but start in Automatic.
        if (!prefs.getBoolean(USE_CUSTOM, false)) {
            baseUrl = DEFAULT_URL;
            return;
        }
        try {
            baseUrl = normalize(prefs.getString(ADDRESS, ""));
        } catch (IllegalArgumentException | ClassCastException e) {
            prefs.edit().remove(ADDRESS).remove(USE_CUSTOM).apply();
            baseUrl = DEFAULT_URL;
        }
    }

    public static String getBaseUrl() {
        return baseUrl;
    }

    public static synchronized void save(Context context, String address) {
        String normalized = normalize(address);
        if (normalized.equals(DEFAULT_URL)) {
            useAutomatic(context);
            return;
        }
        preferences(context).edit().putString(ADDRESS, normalized).putBoolean(USE_CUSTOM, true).apply();
        baseUrl = normalized;
    }

    public static synchronized void useAutomatic(Context context) {
        preferences(context).edit().remove(USE_CUSTOM).apply();
        baseUrl = DEFAULT_URL;
    }

    public static String rememberedAddress(Context context) {
        try {
            return normalize(preferences(context).getString(ADDRESS, ""));
        } catch (IllegalArgumentException | ClassCastException e) {
            return "";
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Accept a server root or /api URL, retaining only a valid HTTP(S) server address. */
    public static String normalize(String address) {
        String value = address == null ? "" : address.trim();
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))
                    || host == null || host.isEmpty() || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw invalidAddress();
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (!path.isEmpty() && !path.equals("/api")) {
                throw new IllegalArgumentException("Enter the server address, ending with /api or without a path.");
            }
            return new URI(scheme.toLowerCase(Locale.ROOT), null, host.toLowerCase(Locale.ROOT),
                    uri.getPort(), "/api", null, null).toString();
        } catch (URISyntaxException e) {
            throw invalidAddress();
        }
    }

    private static IllegalArgumentException invalidAddress() {
        return new IllegalArgumentException("Enter a valid address such as https://your-server.example or http://192.168.1.10:8080.");
    }
}
