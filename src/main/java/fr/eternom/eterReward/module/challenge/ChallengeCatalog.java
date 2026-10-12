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

/** challenges.yml : les défis possibles du jour et de la semaine (copié au premier démarrage). */
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
        Map<Period, List<ChallengeDefinition>> pools = new EnumMap<>(Period.class);
        Map<Period, Integer> counts = new EnumMap<>(Period.class);
        for (Period period : Period.values()) {
            String name = period == Period.DAILY ? "daily" : "weekly";
            counts.put(period, Math.max(0, yaml.getInt(name + "-count", 3)));
            int xp = yaml.getInt("defaults." + name + ".xp", period == Period.DAILY ? 100 : 300);
            double money = yaml.getDouble("defaults." + name + ".money", period == Period.DAILY ? 30 : 150);
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
