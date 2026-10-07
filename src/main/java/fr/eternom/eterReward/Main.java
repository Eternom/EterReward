package fr.eternom.eterReward;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterReward.listeners.Commands;
import fr.eternom.eterReward.listeners.Events;
import fr.eternom.eterReward.module.daily.DailyRewards;
import fr.eternom.eterReward.module.daily.DailyService;
import fr.eternom.eterReward.module.daily.DailyStore;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DateTimeException;
import java.time.ZoneId;

/** Récompenses du réseau : pour l'instant la récompense quotidienne (/daily), plus tard celles des votes. */
public final class Main extends JavaPlugin {

    /** Version minimale d'EterLib : préfixe commun, bouton Retour/Fermer et durées lisibles arrivent en 1.5.0. */
    private static final String REQUIRED_ETERLIB = "1.5.0";

    /** Préfixe des tables d'EterReward dans la base commune : eterreward_daily. */
    private static final String TABLE_PREFIX = "eterreward_";

    private Messages messages;
    private DailyService daily;

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

        daily = new DailyService(this, new DailyStore(lib.database(TABLE_PREFIX)),
                DailyRewards.load(getConfig().getConfigurationSection("daily.days"), getLogger()), messages, zone(),
                lib.backButton(getConfig().getString("menus.daily.back-command", "")));

        new Commands(this);
        new Events(this);
    }

    /** Fuseau du changement de jour (daily.time-zone), Europe/Paris si invalide. */
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
}
