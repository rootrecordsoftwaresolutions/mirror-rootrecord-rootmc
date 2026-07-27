package com.rootrecord.minecraft.rootstat;

import com.rootrecord.minecraft.rootstat.cloud.CloudApiClient;
import com.rootrecord.minecraft.rootstat.config.RootStatConfig;
import com.rootrecord.minecraft.rootstat.mysql.McMMOStatsReader;
import com.rootrecord.minecraft.rootstat.mysql.MySqlPlayerStore;
import com.rootrecord.minecraft.rootstat.mysql.PlayerPlaytimeStore;
import com.rootrecord.minecraft.rootstat.mysql.ReportingStore;
import com.rootrecord.minecraft.rootstat.mysql.RootShopsStore;
import com.rootrecord.minecraft.rootstat.economy.EconomyCollector;
import com.rootrecord.minecraft.rootstat.governance.GovernancePowerCacheService;
import com.rootrecord.minecraft.rootstat.sync.SyncTask;
import org.bukkit.plugin.Plugin;

/** Host for RootStat services — implemented by RootMC (merged) or legacy RootStatPlugin. */
public interface RootStatBridge {

    Plugin getPlugin();

    RootStatConfig config();

    CloudApiClient cloud();

    MySqlPlayerStore players();

    McMMOStatsReader mcmmo();

    PlayerPlaytimeStore playtime();

    SyncTask syncTask();

    EconomyCollector economy();

    RootShopsStore rootShops();

    ReportingStore reporting();

    String msg(String key);

    void reloadRootStatConfig();

    default String colorize(String input) {
        return input == null ? "" : input.replace('&', '\u00A7');
    }

    default GovernancePowerCacheService governancePower() {
        return null;
    }
}
