package fr.eternom.eterReward.module.challenge;

import fr.eternom.eterReward.module.challenge.ChallengeService.Period;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** challenges.yml : les défis possibles du jour, de la semaine, et les missions du pass (copié au premier démarrage). */
public final class ChallengeCatalog {

    public record Catalog(Map<Period, List<ChallengeDefinition>> pools, Map<Period, Integer> counts) {
    }

    private ChallengeCatalog() {
    }

    public static Catalog load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "challenges.yml");
        if (!file.exists()) {
            plugin.saveResource("challenges.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // Une section absente du fichier (ex : missions, ajoutées en 1.12.0) est lue dans celui du plugin
        var bundled = plugin.getResource("challenges.yml");
        if (bundled != null) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(bundled, java.nio.charset.StandardCharsets.UTF_8)));
        }
        Map<Period, List<ChallengeDefinition>> pools = new EnumMap<>(Period.class);
        Map<Period, Integer> counts = new EnumMap<>(Period.class);
        for (Period period : Period.values()) {
            String name = switch (period) {
                case DAILY -> "daily";
                case WEEKLY -> "weekly";
                case MISSION -> "missions";
                case CLAN -> "clan";
            };
            counts.put(period, Math.max(0, yaml.getInt(name + "-count", period == Period.CLAN ? 1 : 3)));
            int xp = yaml.getInt("defaults." + name + ".xp", switch (period) {
                case DAILY -> 100;
                case WEEKLY -> 300;
                case MISSION -> 500;
                case CLAN -> 400;
            });
            double money = yaml.getDouble("defaults." + name + ".money", switch (period) {
                case DAILY -> 30;
                case WEEKLY -> 150;
                case MISSION -> 200;
                case CLAN -> 1000;
            });
            List<ChallengeDefinition> pool = new ArrayList<>();
            ConfigurationSection section = yaml.getConfigurationSection(name);
            if (section != null) {
                for (String id : section.getKeys(false)) {
                    ConfigurationSection entry = section.getConfigurationSection(id);
                    ChallengeDefinition challenge = entry == null ? null
                            : ChallengeDefinition.load(id, entry, xp, money, plugin.getLogger());
                    if (challenge != null) {
                        pool.add(challenge);
                    }
                }
            }
            pools.put(period, List.copyOf(pool));
        }
        return new Catalog(pools, counts);
    }
}
