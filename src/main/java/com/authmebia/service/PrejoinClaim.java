package com.authmebia.service;

import io.papermc.paper.connection.PlayerConfigurationConnection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PrejoinClaim {

    private final Object lock = new Object();
    private final Map<String, Entry> byName = new HashMap<>();
    private final Map<PlayerConfigurationConnection, String> nameByConnection = new HashMap<>();

    public Session acquire(String name, PlayerConfigurationConnection connection) {
        if (connection == null) return null;
        String key = normalize(name);
        synchronized (lock) {
            Entry current = byName.get(key);
            if (current != null && current.connection != connection && isLive(current.connection)) {
                return null;
            }
            if (current != null && current.connection != connection) {
                nameByConnection.remove(current.connection, key);
            }

            String previousName = nameByConnection.put(connection, key);
            if (previousName != null && !previousName.equals(key)) {
                Entry previous = byName.get(previousName);
                if (previous != null && previous.connection == connection) {
                    byName.remove(previousName);
                }
            }

            Entry entry = new Entry(connection);
            byName.put(key, entry);
            return entry.session;
        }
    }

    public Session session(PlayerConfigurationConnection connection) {
        if (connection == null) return null;
        synchronized (lock) {
            String key = nameByConnection.get(connection);
            if (key == null) return null;
            Entry entry = byName.get(key);
            return entry != null && entry.connection == connection ? entry.session : null;
        }
    }

    public PendingState consume(String name) {
        String key = normalize(name);
        synchronized (lock) {
            Entry entry = byName.remove(key);
            if (entry == null) return PendingState.NONE;
            nameByConnection.remove(entry.connection, key);
            return entry.session.snapshot();
        }
    }

    public void releaseIfStale(String name) {
        String key = normalize(name);
        synchronized (lock) {
            Entry entry = byName.get(key);
            if (entry == null || isLive(entry.connection)) return;
            byName.remove(key);
            nameByConnection.remove(entry.connection, key);
        }
    }

    public void retire(PlayerConfigurationConnection connection) {
        if (connection == null) return;
        synchronized (lock) {
            String key = nameByConnection.remove(connection);
            if (key == null) return;
            Entry entry = byName.get(key);
            if (entry != null && entry.connection == connection) {
                byName.remove(key);
            }
        }
    }

    public void retireDeadConnections() {
        synchronized (lock) {
            List<String> dead = new ArrayList<>();
            for (Map.Entry<String, Entry> item : byName.entrySet()) {
                if (!isLive(item.getValue().connection)) {
                    dead.add(item.getKey());
                }
            }
            for (String key : dead) {
                Entry entry = byName.remove(key);
                if (entry != null) {
                    nameByConnection.remove(entry.connection, key);
                }
            }
        }
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static boolean isLive(PlayerConfigurationConnection connection) {
        return connection != null && ConnectionState.isConnected(connection);
    }

    private static final class Entry {

        private final PlayerConfigurationConnection connection;
        private final Session session = new Session();

        private Entry(PlayerConfigurationConnection connection) {
            this.connection = connection;
        }
    }

    public static final class Session {

        private volatile String registerPassword;
        private volatile boolean forceLogin;
        private volatile boolean autoLogin;
        private volatile boolean recovered;
        private volatile String email;

        public String registerPassword() {
            return registerPassword;
        }

        public void setRegisterPassword(String registerPassword) {
            this.registerPassword = registerPassword;
        }

        public void clearRegisterPassword() {
            this.registerPassword = null;
        }

        public boolean forceLogin() {
            return forceLogin;
        }

        public void setForceLogin(boolean forceLogin) {
            this.forceLogin = forceLogin;
        }

        public boolean autoLogin() {
            return autoLogin;
        }

        public void setAutoLogin(boolean autoLogin) {
            this.autoLogin = autoLogin;
        }

        public boolean recovered() {
            return recovered;
        }

        public void setRecovered(boolean recovered) {
            this.recovered = recovered;
        }

        public String email() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        private PendingState snapshot() {
            return new PendingState(registerPassword, forceLogin, autoLogin, recovered, email);
        }
    }

    public record PendingState(String registerPassword, boolean forceLogin, boolean autoLogin,
                               boolean recovered, String email) {

        public static final PendingState NONE = new PendingState(null, false, false, false, null);
    }
}
