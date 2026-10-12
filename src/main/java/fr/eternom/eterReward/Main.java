package fr.eternom.eterReward;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterReward.api.RewardApi;
import fr.eternom.eterReward.listeners.Commands;
import fr.eternom.eterReward.listeners.Events;
import fr.eternom.eterReward.module.RewardApiService;
import fr.eternom.eterReward.module.daily.DailyRewards;
import fr.eternom.eterReward.module.daily.DailyService;
import fr.eternom.eterReward.module.daily.DailyStore;
import fr.eternom.eterReward.module.pass.PassCatalog;
import fr.eternom.eterReward.module.pass.PassService;
import fr.eternom.eterReward.module.pass.PassStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/** Récompenses du réseau : la récompense quotidienne (/daily) et le pass de saison (/pass) ; plus tard celles des votes. */
public final class Main extends JavaPlugin {

    /** Version minimale d'EterLib : textes communs et outils partagés (Frame, Money, NetworkBus) depuis 1.6.0. */
    private static final String REQUIRED_ETERLIB = "1.10.0";

    /** Préfixe des tables d'EterReward dans la base commune : eterreward_daily, eterreward_pass_... */
    private static final String TABLE_PREFIX = "eterreward_";

    /** Paliers de chaque pass. */
    private static final int PASS_TIERS = 30;

    private Messages messages;
    private DailyService daily;
    private PassService pass;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // En premier : vérifie la version d'EterLib (un EterLib < 1.3.0 n'a pas requireVersion, d'où le catch)
        try {
            if (!EterLib.requireVersion(this, REQUIRED_ETERLIB)) {
                return;
            }
        } catch (LinkageError tooOld) {
            getLogger().severe("EterLib " + REQUIRED_ETERLIB + " ou plus récent est nécessaire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        EterLib lib = EterLib.get();
        messages = lib.messages(this, "en_us", "fr_fr");
        Database database = lib.database(TABLE_PREFIX);
        ZoneId zone = zone();

        pass = new PassService(this, new PassStore(database), PassCatalog.load(this, PASS_TIERS), passSettings(), messages, zone,
                lib.backButton(getConfig().getString("menus.pass.back-command", "")));
        daily = new DailyService(this, new DailyStore(database),
                DailyRewards.load(getConfig().getConfigurationSection("daily.days"), getLogger()), messages, zone,
                lib.backButton(getConfig().getString("menus.daily.back-command", "")), pass);

        // API pour les autres plugins (RewardApi.get())
        getServer().getServicesManager().register(RewardApi.class, new RewardApiService(daily, pass), this, ServicePriority.Normal);

        new Commands(this);
        new Events(this);
    }

    private PassService.Settings passSettings() {
        ConfigurationSection section = getConfig().getConfigurationSection("pass");
        Map<String, Integer> questXp = new HashMap<>();
        for (String tier : new String[]{"easy", "normal", "hard", "bonus"}) {
            questXp.put(tier, Math.max(0, getConfig().getInt("pass.quest-xp." + tier)));
        }
        return new PassService.Settings(
                Math.max(1, section == null ? 1200 : section.getInt("xp-per-tier", 1200)),
                Math.max(0, section == null ? 5 : section.getInt("playtime-xp-per-minute", 5)),
                Math.max(0, section == null ? 500 : section.getInt("playtime-daily-cap", 500)),
                Math.max(0, section == null ? 300 : section.getInt("daily-xp", 300)),
                Map.copyOf(questXp));
    }

    /** Fuseau du changement de jour et de mois (daily.time-zone), Europe/Paris si invalide. */
    private ZoneId zone() {
        String zone = getConfig().getString("daily.time-zone", "Europe/Paris");
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            getLogger().warning("daily.time-zone invalide (" + zone + "), Europe/Paris utilisé");
            return ZoneId.of("Europe/Paris");
        }
    }

    public Messages getMessages() {
        return messages;
    }

    public DailyService getDaily() {
        return daily;
    }

    public PassService getPass() {
        return pass;
    }
}
