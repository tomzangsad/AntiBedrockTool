package com.kaizer.antibt;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Centralized Floodgate/Geyser API helper for detecting Bedrock players
 * and fingerprinting headless clients like bedrocktool.
 */
public class FloodgateHelper {

    public FloodgateHelper(Logger logger) {
        // Logger reserved for future debug output
    }

    /**
     * Check if UUID belongs to a Bedrock player via Floodgate or Geyser API.
     */
    public boolean isBedrockPlayer(UUID uuid) {
        // Check Floodgate API first
        try {
            Class<?> floodgateApi = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = floodgateApi.getMethod("getInstance").invoke(null);
            return (boolean) floodgateApi.getMethod("isFloodgatePlayer", UUID.class).invoke(instance, uuid);
        } catch (Exception ignored) {}

        // Check Geyser API
        try {
            Class<?> geyserApi = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object instance = geyserApi.getMethod("api").invoke(null);
            return (boolean) geyserApi.getMethod("isBedrockPlayer", UUID.class).invoke(instance, uuid);
        } catch (Exception ignored) {}

        // Fallback: Floodgate UUID prefix check (first 8 chars are 00000000)
        return uuid.getMostSignificantBits() == 0;
    }

    /**
     * Comprehensive headless/bedrocktool fingerprint check.
     * Returns a suspicion score (0 = clean, higher = more suspicious).
     * Score >= 3 is almost certainly bedrocktool headless mode.
     */
    public int getSuspicionScore(UUID uuid) {
        int score = 0;
        try {
            Class<?> floodgateApi = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = floodgateApi.getMethod("getInstance").invoke(null);
            Object fgPlayer = floodgateApi.getMethod("getPlayer", UUID.class).invoke(instance, uuid);
            if (fgPlayer == null) return 0;

            // --- DeviceOs check ---
            // bedrocktool headless sends zero-value ClientData → DeviceOs = UNKNOWN (ordinal 0)
            try {
                Object deviceOs = fgPlayer.getClass().getMethod("getDeviceOs").invoke(fgPlayer);
                if (deviceOs != null && "UNKNOWN".equals(deviceOs.toString())) {
                    score += 3; // Very strong indicator
                }
            } catch (Exception ignored) {}

            // --- InputMode check ---
            // bedrocktool headless: CurrentInputMode = UNKNOWN (ordinal 0)
            try {
                Object inputMode = fgPlayer.getClass().getMethod("getInputMode").invoke(fgPlayer);
                if (inputMode != null && "UNKNOWN".equals(inputMode.toString())) {
                    score += 2;
                }
            } catch (Exception ignored) {}

            // --- UiProfile check ---
            // bedrocktool headless: UiProfile = CLASSIC (ordinal 0, default zero-value)
            // Not scored highly since legitimate players can also use CLASSIC
            try {
                Object uiProfile = fgPlayer.getClass().getMethod("getUiProfile").invoke(fgPlayer);
                if (uiProfile != null && "CLASSIC".equals(uiProfile.toString())) {
                    // Only suspicious in combination with other flags
                    score += 1;
                }
            } catch (Exception ignored) {}

            // --- LanguageCode check ---
            // bedrocktool headless sends empty or "en_US" default language
            try {
                Object langCode = fgPlayer.getClass().getMethod("getLanguageCode").invoke(fgPlayer);
                if (langCode == null || langCode.toString().isEmpty()) {
                    score += 2;
                }
            } catch (Exception ignored) {}

            // --- DeviceId check ---
            // bedrocktool headless sends empty DeviceId
            try {
                Object deviceId = fgPlayer.getClass().getMethod("getDeviceId").invoke(fgPlayer);
                if (deviceId == null || deviceId.toString().isEmpty()) {
                    score += 2;
                }
            } catch (Exception ignored) {}

        } catch (Exception ignored) {}
        return score;
    }

    /**
     * Quick check: is this definitely a headless bedrocktool client?
     * Uses getSuspicionScore with a threshold.
     */
    public boolean isHeadlessClient(UUID uuid) {
        return getSuspicionScore(uuid) >= 3;
    }

    /**
     * Get a detailed fingerprint string for logging.
     */
    public String getClientFingerprint(UUID uuid) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> floodgateApi = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = floodgateApi.getMethod("getInstance").invoke(null);
            Object fgPlayer = floodgateApi.getMethod("getPlayer", UUID.class).invoke(instance, uuid);
            if (fgPlayer == null) return "FloodgatePlayer=null";

            sb.append("DeviceOs=");
            try { sb.append(fgPlayer.getClass().getMethod("getDeviceOs").invoke(fgPlayer)); }
            catch (Exception e) { sb.append("?"); }

            sb.append(", InputMode=");
            try { sb.append(fgPlayer.getClass().getMethod("getInputMode").invoke(fgPlayer)); }
            catch (Exception e) { sb.append("?"); }

            sb.append(", UiProfile=");
            try { sb.append(fgPlayer.getClass().getMethod("getUiProfile").invoke(fgPlayer)); }
            catch (Exception e) { sb.append("?"); }

            sb.append(", LanguageCode=");
            try { sb.append(fgPlayer.getClass().getMethod("getLanguageCode").invoke(fgPlayer)); }
            catch (Exception e) { sb.append("?"); }

            sb.append(", DeviceId=");
            try { sb.append(fgPlayer.getClass().getMethod("getDeviceId").invoke(fgPlayer)); }
            catch (Exception e) { sb.append("?"); }

        } catch (Exception e) {
            return "FloodgateAPI unavailable";
        }
        return sb.toString();
    }
}
