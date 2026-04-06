package com.kaizer.antibt;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class AntiBTConfig {

    private final Path configFile;

    // Detection settings
    private int noMoveKickSeconds = 15;
    private int noSpawnKickSeconds = 10;
    private int reconnectWindowSeconds = 60;
    private int maxReconnectsInWindow = 3;
    private int ipBanThreshold = 2;
    private int ipBanDurationMinutes = 60;
    private int flagCooldownMinutes = 10;
    private boolean kickOnSuspicious = true;
    private boolean logSuspicious = true;
    private String kickMessage = "§cSuspicious connection detected.";
    private List<String> whitelistedIPs = new ArrayList<>();

    // Aggressive anti-bedrocktool settings
    private boolean blockHeadlessClients = true;
    private boolean firstOffenseBlock = true;
    private int firstOffenseBlockMinutes = 30;
    private boolean persistBans = true;
    private int headlessSuspicionThreshold = 3;
    private String headlessKickMessage = "§cConnection rejected.";

    public AntiBTConfig(Path dataFolder) {
        this.configFile = dataFolder.resolve("config.properties");
    }

    public void load() {
        if (!Files.exists(configFile)) {
            save();
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        noMoveKickSeconds = Integer.parseInt(props.getProperty("no-move-kick-seconds", "15"));
        noSpawnKickSeconds = Integer.parseInt(props.getProperty("no-spawn-kick-seconds", "10"));
        reconnectWindowSeconds = Integer.parseInt(props.getProperty("reconnect-window-seconds", "60"));
        maxReconnectsInWindow = Integer.parseInt(props.getProperty("max-reconnects-in-window", "3"));
        ipBanThreshold = Integer.parseInt(props.getProperty("ip-ban-threshold", "2"));
        ipBanDurationMinutes = Integer.parseInt(props.getProperty("ip-ban-duration-minutes", "60"));
        flagCooldownMinutes = Integer.parseInt(props.getProperty("flag-cooldown-minutes", "10"));
        kickOnSuspicious = Boolean.parseBoolean(props.getProperty("kick-on-suspicious", "true"));
        logSuspicious = Boolean.parseBoolean(props.getProperty("log-suspicious", "true"));
        kickMessage = props.getProperty("kick-message", kickMessage);

        blockHeadlessClients = Boolean.parseBoolean(props.getProperty("block-headless-clients", "true"));
        firstOffenseBlock = Boolean.parseBoolean(props.getProperty("first-offense-block", "true"));
        firstOffenseBlockMinutes = Integer.parseInt(props.getProperty("first-offense-block-minutes", "30"));
        persistBans = Boolean.parseBoolean(props.getProperty("persist-bans", "true"));
        headlessSuspicionThreshold = Integer.parseInt(props.getProperty("headless-suspicion-threshold", "3"));
        headlessKickMessage = props.getProperty("headless-kick-message", headlessKickMessage);

        String wl = props.getProperty("whitelisted-ips", "");
        whitelistedIPs.clear();
        if (!wl.isEmpty()) {
            whitelistedIPs.addAll(Arrays.asList(wl.split(",")));
        }
    }

    public void save() {
        try {
            Files.createDirectories(configFile.getParent());
        } catch (IOException e) {
            e.printStackTrace();
        }
        Properties props = new Properties();
        props.setProperty("no-move-kick-seconds", String.valueOf(noMoveKickSeconds));
        props.setProperty("no-spawn-kick-seconds", String.valueOf(noSpawnKickSeconds));
        props.setProperty("reconnect-window-seconds", String.valueOf(reconnectWindowSeconds));
        props.setProperty("max-reconnects-in-window", String.valueOf(maxReconnectsInWindow));
        props.setProperty("ip-ban-threshold", String.valueOf(ipBanThreshold));
        props.setProperty("ip-ban-duration-minutes", String.valueOf(ipBanDurationMinutes));
        props.setProperty("flag-cooldown-minutes", String.valueOf(flagCooldownMinutes));
        props.setProperty("kick-on-suspicious", String.valueOf(kickOnSuspicious));
        props.setProperty("log-suspicious", String.valueOf(logSuspicious));
        props.setProperty("kick-message", kickMessage);
        props.setProperty("block-headless-clients", String.valueOf(blockHeadlessClients));
        props.setProperty("first-offense-block", String.valueOf(firstOffenseBlock));
        props.setProperty("first-offense-block-minutes", String.valueOf(firstOffenseBlockMinutes));
        props.setProperty("persist-bans", String.valueOf(persistBans));
        props.setProperty("headless-suspicion-threshold", String.valueOf(headlessSuspicionThreshold));
        props.setProperty("headless-kick-message", headlessKickMessage);
        props.setProperty("whitelisted-ips", String.join(",", whitelistedIPs));
        try (OutputStream out = Files.newOutputStream(configFile)) {
            props.store(out, "AntiBedrockTool Configuration");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int getNoMoveKickSeconds() { return noMoveKickSeconds; }
    public int getNoSpawnKickSeconds() { return noSpawnKickSeconds; }
    public int getReconnectWindowSeconds() { return reconnectWindowSeconds; }
    public int getMaxReconnectsInWindow() { return maxReconnectsInWindow; }
    public int getIpBanThreshold() { return ipBanThreshold; }
    public int getIpBanDurationMinutes() { return ipBanDurationMinutes; }
    public int getFlagCooldownMinutes() { return flagCooldownMinutes; }
    public boolean isKickOnSuspicious() { return kickOnSuspicious; }
    public boolean isLogSuspicious() { return logSuspicious; }
    public String getKickMessage() { return kickMessage; }
    public List<String> getWhitelistedIPs() { return whitelistedIPs; }

    public boolean isBlockHeadlessClients() { return blockHeadlessClients; }
    public boolean isFirstOffenseBlock() { return firstOffenseBlock; }
    public int getFirstOffenseBlockMinutes() { return firstOffenseBlockMinutes; }
    public boolean isPersistBans() { return persistBans; }
    public int getHeadlessSuspicionThreshold() { return headlessSuspicionThreshold; }
    public String getHeadlessKickMessage() { return headlessKickMessage; }
    public Path getDataFolder() { return configFile.getParent(); }
}
