package fr.eternom.eterReward.module.pass;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterReward.module.ResourceRewards;
import fr.eternom.eterReward.module.pass.PassDefinition.ItemReward;
import fr.eternom.eterReward.module.pass.PassDefinition.PermissionReward;
import fr.eternom.eterReward.module.pass.PassDefinition.Reward;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Donner et décrire une récompense du pass. Donner se fait en deux temps : ce qui ne demande pas le joueur connecté
 * (argent, clés de ressource, permissions LuckPerms) en tâche de fond juste après l'enregistrement de la réclamation,
 * puis le reste (objets, commandes, insigne) sur le thread principal. Ce qui ne peut pas être donné ici (plugin absent,
 * inventaire plein) part en colis dans la boîte du pass : rien n'est jamais posé au sol ni perdu.
 */
class PassRewards {

    /** Une permission n'a pas pu être donnée alors que LuckPerms tourne : la réclamation est annulée. */
    static final class GiveException extends Exception {
        GiveException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final JavaPlugin plugin;
    private final Messages messages;

    PassRewards(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /**
     * Tâche de fond : la partie à distance (argent, clés, permissions). Les permissions d'abord : si LuckPerms refuse,
     * rien n'a encore été donné et la réclamation peut être annulée (GiveException). Renvoie ce qui n'a pas pu être
     * donné sur ce serveur (plugin absent, argent refusé), à mettre en colis ; vide si tout est donné.
     */
    Parcel giveRemote(UUID uuid, String name, Parcel parcel) throws GiveException {
        List<PermissionReward> permissionsLeft = parcel.permissions();
        if (!parcel.permissions().isEmpty() && Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            for (PermissionReward permission : parcel.permissions()) {
                try {
                    LuckPermsGrants.grantFor(uuid, permission.permission(), permission.duration());
                } catch (RuntimeException | LinkageError e) {
                    throw new GiveException("permission " + permission.permission() + " non donnée à " + name, e);
                }
            }
            permissionsLeft = List.of();
        }
        int keysLeft = parcel.keys();
        int minutesLeft = parcel.minutes();
        if ((keysLeft > 0 || minutesLeft > 0) && Bukkit.getPluginManager().isPluginEnabled("EterResource")
                && ResourceRewards.give(uuid, keysLeft, minutesLeft)) {
            keysLeft = 0;
            minutesLeft = 0;
        }
        double moneyLeft = parcel.money();
        if (moneyLeft > 0 && EconomyApi.get().map(economy -> economy.deposit(uuid, parcel.money(), "EterReward · pass")).orElse(false)) {
            moneyLeft = 0;
        }
        return new Parcel(0, parcel.season(), parcel.label(), moneyLeft, keysLeft, minutesLeft, List.of(), permissionsLeft, List.of(), "");
    }

    /**
     * Thread principal : objets (par piles complètes), commandes, insigne. Renvoie ce qui n'a pas tenu dans l'inventaire
     * (objets, insigne), à mettre en colis ; vide si tout est donné.
     */
    Parcel giveLocal(Player player, Parcel parcel, PassService service) {
        List<ItemReward> left = new ArrayList<>();
        for (ItemReward item : parcel.items()) {
            int remaining = item.amount();
            int max = Math.max(1, item.material().getMaxStackSize());
            while (remaining > 0) {
                int amount = Math.min(max, remaining);
                remaining -= amount;
                for (ItemStack rest : player.getInventory().addItem(ItemStack.of(item.material(), amount)).values()) {
                    left.add(new ItemReward(rest.getType(), rest.getAmount()));
                }
            }
        }
        for (String command : parcel.commands()) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Pass : commande en erreur pour " + player.getName() + " (" + command + ") : " + e.getMessage());
            }
        }
        String special = "";
        if (parcel.special().startsWith("badge:")) {
            ItemStack badge = service.premium().badge(player, parcel.special());
            if (badge != null && !player.getInventory().addItem(badge).isEmpty()) {
                special = parcel.special(); // l'insigne ne tient pas : il reste dans la boîte
            }
        }
        return new Parcel(0, parcel.season(), parcel.label(), 0, 0, 0, left, List.of(), List.of(), special);
    }

    /** « 100 Heloks, 2 clés de ressource, /craft (7 j) » ou le label. */
    Component describe(Player viewer, Reward reward) {
        if (reward.label() != null) {
            return messages.render(reward.label(), TagResolver.empty());
        }
        List<Component> parts = new ArrayList<>();
        if (reward.money() > 0) {
            parts.add(Component.text(Money.format(reward.money())));
        }
        if (reward.keys() > 0) {
            parts.add(messages.get(viewer, "pass.reward.keys", "count", String.valueOf(reward.keys())));
        }
        if (reward.resourceMinutes() > 0) {
            parts.add(messages.get(viewer, "pass.reward.minutes", "count", String.valueOf(reward.resourceMinutes())));
        }
        for (ItemReward item : reward.items()) {
            parts.add(Component.text(item.amount() + "× ").append(Component.translatable(item.material().translationKey())));
        }
        for (PermissionReward permission : reward.permissions()) {
            parts.add(messages.get(viewer, "pass.reward.permission",
                    "permission", permissionLabel(viewer, permission.permission()),
                    "duration", EterLib.get().formatDuration(viewer, permission.duration().toSeconds())));
        }
        if (!reward.commands().isEmpty() && parts.isEmpty()) {
            parts.add(messages.get(viewer, "pass.reward.surprise"));
        }
        return Component.join(JoinConfiguration.commas(true), parts);
    }

    /** Le détail ligne par ligne (clic droit sur un palier) : chaque objet, chaque permission et sa durée, les commandes. */
    List<Component> details(Player viewer, Reward reward) {
        List<Component> lines = new ArrayList<>();
        if (reward.label() != null) {
            lines.add(messages.render(reward.label(), TagResolver.empty()));
        }
        if (reward.money() > 0) {
            lines.add(messages.get(viewer, "pass.details.money", "amount", Money.format(reward.money())));
        }
        if (reward.keys() > 0) {
            lines.add(messages.get(viewer, "pass.details.keys", "count", String.valueOf(reward.keys())));
        }
        if (reward.resourceMinutes() > 0) {
            lines.add(messages.get(viewer, "pass.details.minutes", "count", String.valueOf(reward.resourceMinutes())));
        }
        for (ItemReward item : reward.items()) {
            lines.add(messages.get(viewer, "pass.details.item", "count", String.valueOf(item.amount()))
                    .append(Component.translatable(item.material().translationKey())));
        }
        for (PermissionReward permission : reward.permissions()) {
            lines.add(messages.get(viewer, "pass.details.permission", "permission", permissionLabel(viewer, permission.permission()),
                    "duration", EterLib.get().formatDuration(viewer, permission.duration().toSeconds())));
        }
        if (!reward.commands().isEmpty()) {
            lines.add(messages.get(viewer, "pass.details.commands", "count", String.valueOf(reward.commands().size())));
        }
        return lines;
    }

    /** Nom lisible d'une permission (lang : pass.permission.<permission>), sinon la permission elle-même. */
    private String permissionLabel(Player viewer, String permission) {
        String key = "pass.permission." + permission.replace('.', '_');
        return messages.raw(viewer, key) == null ? permission : messages.plain(viewer, key);
    }
}
