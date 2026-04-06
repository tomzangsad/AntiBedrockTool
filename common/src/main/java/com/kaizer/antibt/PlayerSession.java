package com.kaizer.antibt;

import java.util.UUID;

/**
 * Tracks a single player session for suspicious behavior detection.
 */
public class PlayerSession {

    private final UUID uuid;
    private final String name;
    private final String ip;
    private final long joinTime;
    private boolean spawned;
    private boolean moved;
    private boolean bedrockPlayer;
    private long lastMoveTime;
    private long disconnectTime;

    public PlayerSession(UUID uuid, String name, String ip, boolean bedrockPlayer) {
        this.uuid = uuid;
        this.name = name;
        this.ip = ip;
        this.joinTime = System.currentTimeMillis();
        this.bedrockPlayer = bedrockPlayer;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public String getIp() { return ip; }
    public long getJoinTime() { return joinTime; }

    public boolean hasSpawned() { return spawned; }
    public void setSpawned(boolean spawned) { this.spawned = spawned; }

    public boolean hasMoved() { return moved; }
    public void setMoved(boolean moved) {
        this.moved = moved;
        this.lastMoveTime = System.currentTimeMillis();
    }

    public boolean isBedrockPlayer() { return bedrockPlayer; }

    public long getLastMoveTime() { return lastMoveTime; }

    public long getDisconnectTime() { return disconnectTime; }
    public void setDisconnectTime(long t) { this.disconnectTime = t; }

    public long getSessionDuration() {
        long end = disconnectTime > 0 ? disconnectTime : System.currentTimeMillis();
        return end - joinTime;
    }
}
