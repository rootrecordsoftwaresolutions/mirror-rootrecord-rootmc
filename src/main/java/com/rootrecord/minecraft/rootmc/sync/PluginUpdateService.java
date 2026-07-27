package com.rootrecord.minecraft.rootmc.sync;

import com.rootrecord.minecraft.rootmc.RootMcPlugin;
import com.rootrecord.minecraft.common.RootRecordFolders;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Pulls plugin jar updates from heartbeat responses (remote server, no SSH). */
public final class PluginUpdateService {

    private final RootMcPlugin plugin;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public PluginUpdateService(RootMcPlugin plugin) {
        this.plugin = plugin;
    }

    public void apply(HeartbeatResult result) {
        if (result.configDefaults() != null && !result.configDefaults().isEmpty()) {
            patchRootMcConfig(result.configDefaults());
        }

        List<String> downloadedNotes = new ArrayList<>();
        for (PluginUpdate update : result.updates()) {
            if ("rootmc".equals(update.plugin())) {
                if (tryDownload(update)) {
                    downloadedNotes.add(update.plugin() + " → " + update.version());
                }
            }
        }

        FileConfiguration live = plugin.rootMcYaml();
        boolean restartInMaint = live.getBoolean("plugin-updates.restart-in-maintenance", true);

        if (!downloadedNotes.isEmpty()) {
            setPendingRestart(true, downloadedNotes);
        }

        boolean pending = isPendingRestart() || !downloadedNotes.isEmpty();
        if (!pending || !restartInMaint) {
            if (!downloadedNotes.isEmpty() && !result.maintenanceActive()) {
                plugin.getLogger().info(
                        "Heartbeat jar download(s) ready — auto-restart deferred until maintenance window"
                                + (result.maintenanceWindow() != null && !result.maintenanceWindow().isBlank()
                                        ? " (" + result.maintenanceWindow() + " HST)"
                                        : "")
                                + ".");
            }
            return;
        }

        if (!result.maintenanceActive()) {
            return;
        }

        List<String> notes = downloadedNotes.isEmpty() ? pendingNotes() : downloadedNotes;
        clearPendingRestart();
        HeartbeatUpdateRestart.scheduleAfterUpdates(plugin, notes);
    }

    private void patchRootMcConfig(Map<String, Object> defaults) {
        Path configPath = RootRecordFolders.configFile(plugin, RootRecordFolders.ROOTMC_CONFIG).toPath();
        if (!Files.isRegularFile(configPath)) {
            plugin.getLogger().info("RootMC config not found — skip remote config patch.");
            return;
        }
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configPath.toFile());
            boolean changed = false;
            for (Map.Entry<String, Object> entry : defaults.entrySet()) {
                if (!cfg.contains(entry.getKey())) {
                    cfg.set(entry.getKey(), entry.getValue());
                    changed = true;
                }
            }
            if (changed) {
                cfg.save(configPath.toFile());
                plugin.getLogger().info("Patched plugins/RootMC/rootmc.yml — run /rootstat reload.");
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "RootMC config patch failed: " + ex.getMessage(), ex);
        }
    }

    /** @return true if a new jar was written */
    private boolean tryDownload(PluginUpdate update) {
        if (update.url() == null || update.url().isBlank() || update.filename() == null) {
            return false;
        }
        if (update.version() != null && alreadyDownloaded(update)) {
            return false;
        }
        Path pluginsDir = RootRecordFolders.pluginsDir(plugin).toPath();
        Path target = pluginsDir.resolve(update.filename());
        Path temp = pluginsDir.resolve(update.filename() + ".download");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(update.url()))
                    .timeout(Duration.ofMinutes(2))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                plugin.getLogger().warning("Plugin update download failed (" + update.filename() + "): HTTP "
                        + response.statusCode());
                return false;
            }
            try (InputStream in = response.body()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            markDownloaded(update);
            plugin.getLogger().info("Downloaded " + update.filename()
                    + " — will auto-restart in maintenance window (or restart Paper to apply now).");
            return true;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Plugin update failed for " + update.filename() + ": "
                    + ex.getMessage(), ex);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // ignore
            }
            return false;
        }
    }

    private Path markerFile() {
        return RootRecordFolders.configFile(plugin, RootRecordFolders.DOWNLOADED_PLUGINS_STATE).toPath();
    }

    private boolean alreadyDownloaded(PluginUpdate update) {
        Path marker = markerFile();
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(marker.toFile());
        String key = update.plugin() + ".version";
        return update.version() != null && update.version().equals(cfg.getString(key));
    }

    private void markDownloaded(PluginUpdate update) {
        try {
            RootRecordFolders.ensureDir(plugin);
            Path marker = markerFile();
            YamlConfiguration cfg = Files.isRegularFile(marker)
                    ? YamlConfiguration.loadConfiguration(marker.toFile())
                    : new YamlConfiguration();
            cfg.set(update.plugin() + ".version", update.version());
            cfg.set(update.plugin() + ".filename", update.filename());
            cfg.set(update.plugin() + ".at", System.currentTimeMillis());
            cfg.save(marker.toFile());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not record plugin download marker: " + ex.getMessage());
        }
    }

    private boolean isPendingRestart() {
        Path marker = markerFile();
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        return YamlConfiguration.loadConfiguration(marker.toFile()).getBoolean("pending-restart", false);
    }

    @SuppressWarnings("unchecked")
    private List<String> pendingNotes() {
        Path marker = markerFile();
        if (!Files.isRegularFile(marker)) {
            return List.of("pending plugin update(s)");
        }
        List<?> raw = YamlConfiguration.loadConfiguration(marker.toFile()).getList("pending-notes");
        if (raw == null || raw.isEmpty()) {
            return List.of("pending plugin update(s)");
        }
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out.isEmpty() ? List.of("pending plugin update(s)") : out;
    }

    private void setPendingRestart(boolean pending, List<String> notes) {
        try {
            RootRecordFolders.ensureDir(plugin);
            Path marker = markerFile();
            YamlConfiguration cfg = Files.isRegularFile(marker)
                    ? YamlConfiguration.loadConfiguration(marker.toFile())
                    : new YamlConfiguration();
            cfg.set("pending-restart", pending);
            if (notes != null && !notes.isEmpty()) {
                cfg.set("pending-notes", notes);
            }
            cfg.save(marker.toFile());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not set pending-restart: " + ex.getMessage());
        }
    }

    private void clearPendingRestart() {
        setPendingRestart(false, null);
        try {
            Path marker = markerFile();
            if (!Files.isRegularFile(marker)) {
                return;
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(marker.toFile());
            cfg.set("pending-notes", null);
            cfg.save(marker.toFile());
        } catch (IOException ignored) {
            // ignore
        }
    }

    public record PluginUpdate(String plugin, String version, String filename, String url) {}

    public record HeartbeatResult(
            Map<String, Object> configDefaults,
            java.util.List<PluginUpdate> updates,
            boolean maintenanceActive,
            String maintenanceWindow) {}
}
