package com.shuttle.mobile.client;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** The server selected on this phone. Accounts and invitations belong to that server. */
public final class ServerConnection {
    private static final String PREFERENCES = "server_connection";
    private static final String ADDRESS = "api_address";
    private static volatile String baseUrl = ApiClient.BASE_URL;

    private ServerConnection() { }

    public static synchronized void initialize(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        try {
            baseUrl = normalize(prefs.getString(ADDRESS, ApiClient.BASE_URL));
        } catch (IllegalArgumentException e) {
            baseUrl = ApiClient.BASE_URL;
        }
    }

    public static String getBaseUrl() {
        return baseUrl;
    }

    public static synchronized void save(Context context, String address) {
        String normalized = normalize(address);
        context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(ADDRESS, normalized).apply();
        baseUrl = normalized;
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
