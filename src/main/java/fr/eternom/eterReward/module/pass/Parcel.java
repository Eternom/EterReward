package fr.eternom.eterReward.module.pass;

import fr.eternom.eterReward.module.pass.PassDefinition.ItemReward;
import fr.eternom.eterReward.module.pass.PassDefinition.PermissionReward;
import fr.eternom.eterReward.module.pass.PassDefinition.Reward;
import org.bukkit.Material;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Un colis de la boîte du pass (/pass colis) : ce qui n'a pas pu être donné tout de suite (inventaire plein, joueur
 * parti, plugin absent de ce serveur) ou ce qui attend le joueur (insigne du premium). Pris d'un clic, sur tout le réseau.
 *
 * @param id      0 tant qu'il n'est pas en base
 * @param label   texte affiché (MiniMessage), ex : "Palier 12 · premium"
 * @param special "badge:<saison>:<pass>" : l'insigne du premium de ce mois ; vide sinon
 */
public record Parcel(long id, String season, String label, double money, int keys, int minutes, List<ItemReward> items,
                     List<PermissionReward> permissions, List<String> commands, String special) {

    public Parcel {
        items = List.copyOf(items);
        permissions = List.copyOf(permissions);
        commands = List.copyOf(commands);
        special = special == null ? "" : special;
    }

    static Parcel of(String season, String label, Reward reward) {
        return new Parcel(0, season, label, reward.money(), reward.keys(), reward.resourceMinutes(), reward.items(),
                reward.permissions(), reward.commands(), "");
    }

    static Parcel items(String season, String label, List<ItemReward> items) {
        return new Parcel(0, season, label, 0, 0, 0, items, List.of(), List.of(), "");
    }

    /** La partie « à distance » seulement (argent, clés, permissions). */
    Parcel remotePart() {
        return new Parcel(id, season, label, money, keys, minutes, List.of(), permissions, List.of(), "");
    }

    /** La partie « sur place » seulement (objets, commandes, insigne). */
    Parcel localPart() {
        return new Parcel(id, season, label, 0, 0, 0, items, List.of(), commands, special);
    }

    boolean isEmpty() {
        return money <= 0 && keys <= 0 && minutes <= 0 && items.isEmpty() && permissions.isEmpty() && commands.isEmpty()
                && special.isEmpty();
    }

    /** Comme une récompense, pour la décrire avec PassRewards.describe. */
    Reward asReward() {
        return new Reward(money, keys, minutes, items, permissions, commands, null);
    }

    // ---------- En base (texte) ----------

    String itemsText() {
        return String.join(";", items.stream().map(item -> item.material().name() + " " + item.amount()).toList());
    }

    String permissionsText() {
        return String.join(";", permissions.stream().map(p -> p.permission() + " " + p.duration().toSeconds()).toList());
    }

    String commandsText() {
        return String.join("\n", commands);
    }

    static List<ItemReward> parseItems(String text) {
        List<ItemReward> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }
        for (String entry : text.split(";")) {
            String[] parts = entry.trim().split("\\s+");
            Material material = Material.matchMaterial(parts[0].toUpperCase(Locale.ROOT));
            if (material != null && parts.length > 1) {
                try {
                    items.add(new ItemReward(material, Integer.parseInt(parts[1])));
                } catch (NumberFormatException ignored) {
                    // entrée abîmée : ignorée
                }
            }
        }
        return items;
    }

    static List<PermissionReward> parsePermissions(String text) {
        List<PermissionReward> permissions = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return permissions;
        }
        for (String entry : text.split(";")) {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length > 1) {
                try {
                    permissions.add(new PermissionReward(parts[0], Duration.ofSeconds(Long.parseLong(parts[1]))));
                } catch (NumberFormatException ignored) {
                    // entrée abîmée : ignorée
                }
            }
        }
        return permissions;
    }

    static List<String> parseCommands(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(text.split("\n"));
    }
}
