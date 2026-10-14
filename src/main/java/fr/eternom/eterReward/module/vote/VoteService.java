package fr.eternom.eterReward.module.vote;

import com.google.gson.JsonObject;
import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import fr.eternom.eterReward.module.ResourceRewards;
import fr.eternom.eterReward.module.pass.PassService;
import fr.eternom.eterReward.module.ranking.RankingService;
import fr.eternom.eterReward.module.vote.VoteRewards.Loot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Votes sur les sites de vote. Le plugin Vote du site lance « eterreward vote <joueur> » dans la console (par AzLink)
 * à chaque vote validé. Le joueur peut être hors ligne ou sur un autre serveur : tout passe par la base (argent par
 * EterEconomy, XP du pass, clés par EterResource), rien n'attend sa connexion.
 *
 * Un vote : la série avance (premier vote du jour), le vote compte au classement du mois, un tirage est donné, plus le
 * bonus de série s'il y en a un pour ce nombre de jours. Le joueur est prévenu là où il est (dans sa langue), et un
 * merci est annoncé sur tout le réseau.
 */
public class VoteService {

    private static final String RECEIVED = "received";
    private static final String THANKS = "thanks";

    /** Ce que le joueur a reçu, envoyé tel quel au serveur où il se trouve pour lui écrire dans sa langue. */
    private record Gift(double money, int passXp, int keys, String label) {

        static Gift of(Loot loot) {
            return loot == null ? null : new Gift(loot.money(), loot.passXp(), loot.keys(), loot.label());
        }
    }

    private final JavaPlugin plugin;
    private final VoteStore store;
    private final VoteRewards rewards;
    private final PassService pass;
    private final RankingService ranking;
    private final Messages messages;
    private final ZoneId zone;
    private final boolean announce;
    private final NetworkBus bus;

    public VoteService(JavaPlugin plugin, VoteStore store, VoteRewards rewards, PassService pass, RankingService ranking,
                       Messages messages, ZoneId zone, boolean announce) {
        this.plugin = plugin;
        this.store = store;
        this.rewards = rewards;
        this.pass = pass;
        this.ranking = ranking;
        this.messages = messages;
        this.zone = zone;
        this.announce = announce;
        this.bus = EterLib.get().network(plugin, "eterreward-votes", messages);
        bus.on(RECEIVED, data -> {
            Player player = Bukkit.getPlayer(UUID.fromString(data.get("player").getAsString()));
            if (player != null) {
                tell(player, data.get("streak").getAsInt(), read(data, "vote"), read(data, "bonus"));
            }
        });
        bus.on(THANKS, data -> thank(data.get("player").getAsString()));
    }

    /** eterreward vote <joueur> (thread principal) : tout en tâche de fond, puis réponse au lanceur. */
    public void vote(CommandSender sender, String name) {
        Tasks.async(plugin, () -> {
            NetworkPlayer player = EterLib.get().getPlayers().find(name).orElse(null);
            if (player == null) {
                Bukkit.getScheduler().runTask(plugin, () -> messages.send(sender, "vote.unknown", "player", name));
                return;
            }
            VoteStore.Day day = store.record(player.uuid(), LocalDate.now(zone).toEpochDay());
            ranking.voted(player.uuid());
            Loot vote = rewards.pick();
            Loot bonus = day.first() ? rewards.streakBonus(day.streak()) : null;
            give(player, vote);
            give(player, bonus);
            Bukkit.getScheduler().runTask(plugin, () -> {
                messages.send(sender, "vote.done", "player", player.name(), "streak", String.valueOf(day.streak()));
                received(player.uuid(), day.streak(), Gift.of(vote), Gift.of(bonus));
                if (announce) {
                    thank(player.name());
                    JsonObject data = new JsonObject();
                    data.addProperty("player", player.name());
                    bus.publish(THANKS, data);
                }
            });
        }, "Vote non enregistré pour " + name);
    }

    /** Bloquant. Une récompense absente (null) ne donne rien. */
    private void give(NetworkPlayer player, Loot loot) {
        if (loot == null) {
            return;
        }
        if (loot.money() > 0) {
            EconomyApi.get().ifPresentOrElse(economy -> economy.deposit(player.uuid(), loot.money(), "EterReward · vote"),
                    () -> plugin.getLogger().warning("Vote : argent non versé à " + player.name() + " (EterEconomy absent)"));
        }
        if (loot.passXp() > 0) {
            pass.addXp(player.uuid(), loot.passXp());
        }
        if (loot.keys() > 0 && (!Bukkit.getPluginManager().isPluginEnabled("EterResource")
                || !ResourceRewards.give(player.uuid(), loot.keys(), 0))) {
            plugin.getLogger().warning("Vote : clés non données à " + player.name() + " (EterResource absent sur ce serveur)");
        }
        for (String command : loot.commands()) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.name())));
        }
    }

    /** Thread principal : prévient le joueur ici, ou sur le serveur où il se trouve. */
    private void received(UUID uuid, int streak, Gift vote, Gift bonus) {
        Player local = Bukkit.getPlayer(uuid);
        if (local != null) {
            tell(local, streak, vote, bonus);
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("player", uuid.toString());
        data.addProperty("streak", streak);
        write(data, "vote", vote);
        write(data, "bonus", bonus);
        bus.publish(RECEIVED, data);
    }

    private void tell(Player player, int streak, Gift vote, Gift bonus) {
        player.sendMessage(messages.prefix().append(messages.get(player, "vote.received",
                Placeholder.component("content", describe(player, vote)), "streak", String.valueOf(streak))));
        if (bonus != null) {
            player.sendMessage(messages.prefix().append(messages.get(player, "vote.streak-bonus",
                    Placeholder.component("content", describe(player, bonus)), "streak", String.valueOf(streak))));
        }
        player.playSound(player, bonus != null ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
    }

    private void thank(String name) {
        Bukkit.getOnlinePlayers().forEach(player -> messages.send(player, "vote.thanks", "player", name));
    }

    /** « 100 Heloks, 150 XP de pass, 1 clé » dans la langue du joueur, ou le label du tirage. */
    private Component describe(Player viewer, Gift gift) {
        if (gift == null) {
            return messages.get(viewer, "vote.nothing");
        }
        if (gift.label() != null) {
            return messages.render(gift.label(), TagResolver.empty());
        }
        List<Component> parts = new ArrayList<>();
        if (gift.money() > 0) {
            parts.add(Component.text(Money.format(gift.money())));
        }
        if (gift.passXp() > 0) {
            parts.add(messages.get(viewer, "vote.part.pass-xp", "amount", String.valueOf(gift.passXp())));
        }
        if (gift.keys() > 0) {
            parts.add(messages.get(viewer, "vote.part.keys", "amount", String.valueOf(gift.keys())));
        }
        return parts.isEmpty() ? messages.get(viewer, "vote.nothing") : Component.join(JoinConfiguration.commas(true), parts);
    }

    private static void write(JsonObject data, String key, Gift gift) {
        if (gift == null) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("money", gift.money());
        json.addProperty("pass-xp", gift.passXp());
        json.addProperty("keys", gift.keys());
        if (gift.label() != null) {
            json.addProperty("label", gift.label());
        }
        data.add(key, json);
    }

    private static Gift read(JsonObject data, String key) {
        if (!data.has(key)) {
            return null;
        }
        JsonObject json = data.getAsJsonObject(key);
        return new Gift(json.get("money").getAsDouble(), json.get("pass-xp").getAsInt(), json.get("keys").getAsInt(),
                json.has("label") ? json.get("label").getAsString() : null);
    }
}
