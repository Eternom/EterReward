package fr.eternom.eterReward.module.pass;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Les pass de plugins/EterReward/passes/ : un fichier par pass, son nom de fichier est son identifiant. */
public final class PassCatalog {

    /** Les 8 pass fournis, copiés au premier démarrage (dossier passes/ absent). */
    private static final String[] DEFAULTS = {"profondeurs", "moisson", "foret", "maree", "chasse", "foire", "voyage", "lanternes"};

    private PassCatalog() {
    }

    public static Map<String, PassDefinition> load(JavaPlugin plugin, int tiers) {
        File folder = new File(plugin.getDataFolder(), "passes");
        if (!folder.exists()) {
            for (String name : DEFAULTS) {
                plugin.saveResource("passes/" + name + ".yml", false);
            }
        }
        Map<String, PassDefinition> passes = new LinkedHashMap<>();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                PassDefinition pass = PassDefinition.load(file, tiers, plugin.getLogger());
                if (pass != null) {
                    passes.put(pass.id(), pass);
                }
            }
        }
        if (passes.isEmpty()) {
            plugin.getLogger().warning("Aucun pass valable dans passes/ : /pass est désactivé");
        }
        return passes;
    }
}
