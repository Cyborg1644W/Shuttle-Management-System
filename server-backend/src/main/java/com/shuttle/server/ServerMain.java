package com.shuttle.server;

import com.shuttle.server.service.InviteService;

public class ServerMain {
    public static void main(String[] args) {
        try {
            Repositories.init();

            String bootstrapEmail = null;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--bootstrap-admin") && i + 1 < args.length) {
                    bootstrapEmail = args[++i];
                }
            }

            ShuttleServer server = new ShuttleServer();
            server.start();

            if (bootstrapEmail != null) {
                String code = InviteService.createInvite("OPERATOR");
                System.out.println("==============================================");
                System.out.println(" First operator invite code: " + code);
                System.out.println(" Register with email " + bootstrapEmail + " (valid 48 hours, single use)");
                System.out.println("==============================================");
            } else if (Repositories.workers().count() == 0) {
                System.out.println("No workers yet. Restart with  --bootstrap-admin you@example.com  to get an invite code.");
            }
        } catch (Exception e) {
            Log.error("Failed to start server: " + e.getMessage());
        }
    }
}