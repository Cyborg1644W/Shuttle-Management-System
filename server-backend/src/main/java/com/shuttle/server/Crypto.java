package com.shuttle.server;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class Crypto {
    // A simple fixed salt for the prototype to keep the code short.
    private static final String SALT = "ShuttleSystemSecretSalt";

    /**
     * Hashes a password securely using SHA-256 with a salt.
     *
     * @param password The plaintext password to hash.
     * @return The hashed password as a Base64 string.
     */
    public static String hashPassword(String password) {
        try {
            // Use standard, battle-tested java.security libraries[cite: 19]
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Combine the password and the salt[cite: 12]
            String saltedPassword = password + SALT;

            // Generate the hash
            byte[] hashBytes = digest.digest(saltedPassword.getBytes());

            // Convert to a readable string format to save in CSV
            return Base64.getEncoder().encodeToString(hashBytes);

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    /**
     * Compares a plaintext login attempt against a saved hash[cite: 12].
     *
     * @param inputPassword The password the user just typed in.
     * @param storedHash The hash saved in workers.csv.
     * @return true if the passwords match, false otherwise.
     */
    public static boolean verifyPassword(String inputPassword, String storedHash) {
        // Hash the incoming password attempt and compare it to the stored hash
        String newHash = hashPassword(inputPassword);
        return newHash.equals(storedHash);
    }
}