package fr.eternom.eterReward.module.pass;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterReward.module.pass.PassService.Season;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

/**
 * Les annonces du pass, sur tout le réseau (et sur Discord si pass.discord-webhook) :
 * - le nouveau pass, au premier besoin du mois (un seul serveur l'annonce : la base départage), avec le bilan du mois
 *   d'avant pour le staff (joueurs, palier moyen, pass finis, premium) ;
 * - le début d'un évènement (XP multipliée, sans plafond).
 */
final class PassAnnouncer {

    private static final String ADMIN = "eterreward.admin";

    private final PassService service;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    PassAnnouncer(PassService service) {
        this.service = service;
    }

    void listen(NetworkBus bus) {
        bus.on("announce", data -> Tasks.async(service.plugin(), () -> {
            Season season = service.season();
            Bukkit.getScheduler().runTask(service.plugin(), () -> broadcastSeason(season));
        }, "Annonce du pass non affichée"));
        bus.on("stats", this::broadcastStats);
        bus.on("event", data -> broadcastEventLocal(data.get("minutes").getAsLong(), data.get("multiplier").getAsDouble(),
                data.get("uncapped").getAsBoolean()));
    }

    /** Le nouveau pass vient d'être marqué annoncé ici (tâche de fond) : annonce partout, bilan du mois d'avant. */
    void announce(Season season) {
        if (!service.settings().announce()) {
            return;
        }
        String previous = YearMonth.parse(season.key()).minusMonths(1).format(PassService.SEASON_FORMAT);
        JsonObject stats = stats(previous);
        Bukkit.getScheduler().runTask(service.plugin(), () -> {
            broadcastSeason(season);
            service.bus().publish("announce", new JsonObject());
            if (stats != null) {
                broadcastStats(stats);
                service.bus().publish("stats", stats);
            }
            Messages messages = service.messages();
            var console = Bukkit.getConsoleSender();
            discord(messages.plain(console, "pass.announce.discord", "pass", plain(season.pass().name()),
                    "description", String.join("\n", season.pass().description().stream().map(this::plain).toList())));
            if (stats != null) {
                discord(messages.plain(console, "pass.stats.discord", values(stats)));
            }
        });
    }

    /** Bilan d'une saison ; null si personne n'y a joué. Bloquant. */
    private JsonObject stats(String season) {
        List<Integer> xp = service.store().allXp(season);
        if (xp.isEmpty()) {
            return null;
        }
        XpCurve curve = service.curve();
        double average = xp.stream().mapToInt(curve::tier).average().orElse(0);
        long finished = xp.stream().filter(value -> curve.tier(value) >= curve.tiers()).count();
        int premium = service.store().premiumCount(season);
        JsonObject stats = new JsonObject();
        stats.addProperty("season", season);
        stats.addProperty("players", xp.size());
        stats.addProperty("average", String.format(Locale.FRANCE, "%.1f", average));
        stats.addProperty("finished", finished);
        stats.addProperty("finished_percent", Math.round(finished * 100.0 / xp.size()));
        stats.addProperty("premium", premium);
        stats.addProperty("premium_percent", Math.round(premium * 100.0 / xp.size()));
        return stats;
    }

    private static String[] values(JsonObject stats) {
        return new String[]{"season", stats.get("season").getAsString(), "players", stats.get("players").getAsString(),
                "average", stats.get("average").getAsString(), "finished", stats.get("finished").getAsString(),
                "finished_percent", stats.get("finished_percent").getAsString(), "premium", stats.get("premium").getAsString(),
                "premium_percent", stats.get("premium_percent").getAsString()};
    }

    /** Thread principal : le nouveau pass à tous les joueurs de ce serveur. */
    private void broadcastSeason(Season season) {
        Messages messages = service.messages();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(PassService.PERMISSION)) {
                continue;
            }
            player.sendMessage(Component.empty());
            player.sendMessage(messages.get(player, "pass.announce.title").append(Component.space())
                    .append(messages.render(season.pass().name(), TagResolver.empty())));
            season.pass().description().forEach(line -> player.sendMessage(messages.render(line, TagResolver.empty())));
            service.passButton(player, "pass.announce.open");
            player.sendMessage(Component.empty());
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1f);
        }
    }

    /** Thread principal : le bilan au staff de ce serveur. */
    private void broadcastStats(JsonObject stats) {
        String[] values = values(stats);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(ADMIN)) {
                service.messages().send(player, "pass.stats.staff", values);
            }
        }
        service.messages().send(Bukkit.getConsoleSender(), "pass.stats.staff", values);
    }

    /** Thread principal : un évènement commence (ici et ailleurs). */
    void broadcastEvent(Duration duration, double multiplier, boolean uncapped) {
        JsonObject data = new JsonObject();
        data.addProperty("minutes", duration.toMinutes());
        data.addProperty("multiplier", multiplier);
        data.addProperty("uncapped", uncapped);
        service.bus().publish("event", data);
        broadcastEventLocal(duration.toMinutes(), multiplier, uncapped);
        discord(service.messages().plain(Bukkit.getConsoleSender(), uncapped ? "pass.event.start-uncapped" : "pass.event.start",
                "multiplier", multiplier(multiplier), "time", EterLib.get().formatDuration(Bukkit.getConsoleSender(), duration.toSeconds())));
    }

    private void broadcastEventLocal(long minutes, double multiplier, boolean uncapped) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            service.messages().send(player, uncapped ? "pass.event.start-uncapped" : "pass.event.start",
                    "multiplier", multiplier(multiplier), "time", EterLib.get().formatDuration(player, minutes * 60));
        }
    }

    static String multiplier(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.FRANCE, "%.1f", value);
    }

    private String plain(String miniMessage) {
        return PlainTextComponentSerializer.plainText().serialize(service.messages().render(miniMessage, TagResolver.empty()));
    }

    /** Sur le salon Discord (webhook), en tâche de fond ; sans mention possible. */
    private void discord(String text) {
        String webhook = service.settings().discordWebhook();
        if (webhook.isEmpty() || text == null || text.isBlank()) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("content", text.length() > 1900 ? text.substring(0, 1900) : text);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(error -> {
                service.plugin().getLogger().warning("Annonce du pass non envoyée sur Discord (webhook injoignable)");
                return null;
            });
        } catch (IllegalArgumentException e) {
            service.plugin().getLogger().warning("pass.discord-webhook invalide");
        }
    }
}
