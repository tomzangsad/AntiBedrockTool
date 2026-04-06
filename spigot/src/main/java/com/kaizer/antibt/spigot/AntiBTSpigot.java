package com.kaizer.antibt.spigot;

import com.kaizer.antibt.AntiBTConfig;
import com.kaizer.antibt.DetectionEngine;
import com.kaizer.antibt.FloodgateHelper;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AntiBTSpigot extends JavaPlugin implements Listener {

    private AntiBTConfig config;
    private DetectionEngine engine;

    // Pack verification
    private final ConcurrentHashMap<UUID, Long> bedrockJoinTimes = new ConcurrentHashMap<>();
    private final Set<String> verifiedUsernames = ConcurrentHashMap.newKeySet();
    private static final long VERIFY_DELAY_MS = 30_000;

    @Override
    public void onEnable() {
        config = new AntiBTConfig(getDataFolder().toPath());
        config.load();

        engine = new DetectionEngine(config, getLogger(), result -> {
            // Handle async detection results
            Bukkit.getScheduler().runTask(this, () -> {
                Player player = Bukkit.getPlayer(result.playerUuid());
                if (player != null && player.isOnline()) {
                    player.kickPlayer(config.getKickMessage());
                }
            });
        });

        Bukkit.getPluginManager().registerEvents(this, this);
        loadVerifiedPlayers();

        // Tick every second for Java player detection
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            List<DetectionEngine.DetectionResult> results = engine.tick();
            if (!results.isEmpty()) {
                Bukkit.getScheduler().runTask(this, () -> {
                    for (DetectionEngine.DetectionResult result : results) {
                        Player player = Bukkit.getPlayer(result.playerUuid());
                        if (player != null && player.isOnline()) {
                            player.kickPlayer(config.getKickMessage());
                            engine.removeSession(result.playerUuid());
                        }
                    }
                });
            }
        }, 20L, 20L);

        // Verification tick every 5 seconds for Bedrock players
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::verificationTick, 100L, 100L);

        getLogger().info("AntiBedrockTool v2.0.0 enabled - Spigot/Paper");
        getLogger().info("[AntiBedrockTool] Headless client blocking: " + (config.isBlockHeadlessClients() ? "ON" : "OFF"));
        getLogger().info("[AntiBedrockTool] First-offense IP block: " + (config.isFirstOffenseBlock() ? "ON (" + config.getFirstOffenseBlockMinutes() + "min)" : "OFF"));
        getLogger().info("[AntiBedrockTool] Persistent bans: " + (config.isPersistBans() ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        config.save();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String ip = event.getAddress().getHostAddress();
        FloodgateHelper fh = engine.getFloodgateHelper();
        boolean isBedrock = fh.isBedrockPlayer(player.getUniqueId());

        // Bedrock: skip DetectionEngine, only track for verification
        if (isBedrock) {
            if (!verifiedUsernames.contains(player.getName().toLowerCase())) {
                bedrockJoinTimes.put(player.getUniqueId(), System.currentTimeMillis());
                getLogger().info("[AntiBedrockTool] Bedrock player " + player.getName() + " joined - will verify in " + (VERIFY_DELAY_MS / 1000) + "s");
            }
            return;
        }

        // Java only: DetectionEngine
        if (!engine.onJoin(player.getUniqueId(), player.getName(), ip, false)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, config.getKickMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Only track spawn for Java players
        if (!engine.getFloodgateHelper().isBedrockPlayer(player.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                engine.onSpawn(player.getUniqueId());
            }, 5L);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition()) {
            engine.onMove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        bedrockJoinTimes.remove(player.getUniqueId());
        if (!engine.getFloodgateHelper().isBedrockPlayer(player.getUniqueId())) {
            engine.onQuit(player.getUniqueId());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("antibt")) return false;
        if (!sender.hasPermission("antibt.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e/antibt reload §7- Reload config");
            sender.sendMessage("§e/antibt status §7- Show status");
            sender.sendMessage("§e/antibt unban §7- Clear all IP bans");
            sender.sendMessage("§e/antibt reset §7- Clear verified players");
            sender.sendMessage("§e/antibt verify <player> §7- Manually verify");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                config.load();
                loadVerifiedPlayers();
                sender.sendMessage("§aAntiBedrockTool config reloaded.");
            }
            case "status" -> {
                sender.sendMessage("§bAntiBedrockTool §7v2.0.0");
                FloodgateHelper fh = engine.getFloodgateHelper();
                long bedrockCount = Bukkit.getOnlinePlayers().stream()
                    .filter(p -> fh.isBedrockPlayer(p.getUniqueId())).count();
                sender.sendMessage("§7Bedrock players online: §f" + bedrockCount);
                sender.sendMessage("§7Active sessions: §f" + engine.getActiveSessionCount());
                sender.sendMessage("§cBanned IPs: §f" + engine.getBannedIpCount());
                sender.sendMessage("§6Flagged IPs: §f" + engine.getFlaggedIpCount());
                sender.sendMessage("§aVerified players: §f" + verifiedUsernames.size());
                sender.sendMessage("§7Headless block: §f" + (config.isBlockHeadlessClients() ? "ON" : "OFF"));
                sender.sendMessage("§7First-offense block: §f" + (config.isFirstOffenseBlock() ? "ON" : "OFF"));
            }
            case "unban" -> {
                engine.clearAllBans();
                try {
                    Path banFile = getDataFolder().toPath().resolve("banned-ips.txt");
                    if (Files.exists(banFile)) Files.writeString(banFile, "# Cleared\n");
                } catch (IOException ignored) {}
                sender.sendMessage("§aCleared all IP bans.");
            }
            case "reset" -> {
                verifiedUsernames.clear();
                try {
                    Files.writeString(getVerifiedFilePath(), "# Cleared\n");
                } catch (IOException ignored) {}
                sender.sendMessage("§aCleared all verified players.");
            }
            case "verify" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /antibt verify <player>");
                    return true;
                }
                String targetName = args[1];
                String targetIp = "0.0.0.0";
                Player tp = Bukkit.getPlayer(targetName);
                if (tp != null) targetIp = tp.getAddress().getAddress().getHostAddress();
                writeVerifiedPlayer(targetName, targetIp);
                verifiedUsernames.add(targetName.toLowerCase());
                sender.sendMessage("§aVerified: " + targetName);
            }
            default -> sender.sendMessage("§cUnknown subcommand.");
        }
        return true;
    }

    // ==================== Pack Verification ====================

    private void verificationTick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : bedrockJoinTimes.entrySet()) {
            UUID uuid = entry.getKey();
            if (now - entry.getValue() >= VERIFY_DELAY_MS) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    String username = player.getName();
                    String ip = player.getAddress().getAddress().getHostAddress();
                    writeVerifiedPlayer(username, ip);
                    verifiedUsernames.add(username.toLowerCase());
                    bedrockJoinTimes.remove(uuid);
                    getLogger().info("[AntiBedrockTool] VERIFIED: " + username + " (IP: " + ip + ")");
                    Bukkit.getScheduler().runTask(this, () ->
                        player.kickPlayer("§aYou have been verified!\n§ePlease reconnect to load resource packs."));
                } else {
                    bedrockJoinTimes.remove(uuid);
                }
            }
        }
    }

    private Path getVerifiedFilePath() {
        return getDataFolder().toPath().resolve("verified-players.txt");
    }

    private void loadVerifiedPlayers() {
        Path file = getVerifiedFilePath();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("ip:")) continue;
                verifiedUsernames.add(line.toLowerCase());
            }
            getLogger().info("[AntiBedrockTool] Loaded " + verifiedUsernames.size() + " verified players");
        } catch (IOException e) {
            getLogger().warning("[AntiBedrockTool] Failed to load verified players: " + e.getMessage());
        }
    }

    private void writeVerifiedPlayer(String username, String ip) {
        Path file = getVerifiedFilePath();
        try {
            Files.createDirectories(file.getParent());
            Set<String> existing = new HashSet<>();
            if (Files.exists(file)) existing.addAll(Files.readAllLines(file));
            StringBuilder sb = new StringBuilder();
            if (!existing.contains(username.toLowerCase())) sb.append(username.toLowerCase()).append(System.lineSeparator());
            String ipEntry = "ip:" + ip;
            if (!existing.contains(ipEntry)) sb.append(ipEntry).append(System.lineSeparator());
            if (sb.length() > 0) Files.writeString(file, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().warning("[AntiBedrockTool] Failed to write verified player: " + e.getMessage());
        }
    }
}
