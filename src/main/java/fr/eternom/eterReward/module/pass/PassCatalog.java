package fr.eternom.eterReward.module.pass;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Les pass de plugins/EterReward/passes/ : un fichier par pass, son nom de fichier est son identifiant. */
public final class PassCatalog {

    /**
     * Les pass fournis : chacun est copié s'il manque dans passes/ (au premier démarrage, et quand une version en ajoute).
     * Pour retirer un pass de la rotation, garder son fichier avec « enabled: false » : supprimé, il reviendrait.
     */
    private static final String[] DEFAULTS = {"profondeurs", "moisson", "foret", "maree", "chasse", "foire", "voyage", "lanternes",
            "epreuves", "ravitaillement"};

    private PassCatalog() {
    }

    public static Map<String, PassDefinition> load(JavaPlugin plugin, int tiers) {
        File folder = new File(plugin.getDataFolder(), "passes");
        for (String name : DEFAULTS) {
            if (!new File(folder, name + ".yml").exists()) {
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
