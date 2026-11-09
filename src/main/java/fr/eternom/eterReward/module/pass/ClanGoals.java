package fr.eternom.eterReward.module.pass;

import fr.eternom.eterClan.api.ClanApi;
import fr.eternom.eterReward.module.pass.PassService.Season;
import fr.eternom.eterReward.module.pass.PassSettings.ClanGoal;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le pass et les clans (EterClan en softdepend : ses classes ne sont touchées que s'il tourne ici) :
 * - objectifs de clan : quand le palier MOYEN des membres atteint un objectif (pass.clan-goals), chaque membre gagne
 *   son XP, une fois par mois et par objectif (la base départage les serveurs) ;
 * - le clan d'un joueur, pour le premium offert à tout le clan.
 * Bloquant (base) : hors du thread principal.
 */
final class ClanGoals {

    /** Ce dont le pass a besoin d'un clan. */
    record Clan(long id, String name, UUID owner, List<UUID> members) {
    }

    private ClanGoals() {
    }

    static Clan clanOf(UUID player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("EterClan")) {
            return null;
        }
        return ClanApi.get().flatMap(api -> api.clanOf(player))
                .map(info -> new Clan(info.id(), info.name(), info.owner(), info.members()))
                .orElse(null);
    }

    /** Après un palier passé par player : les objectifs de son clan sont-ils atteints ? */
    static void check(PassService service, UUID player, Season season) {
        List<ClanGoal> goals = service.settings().clanGoals();
        if (goals.isEmpty()) {
            return;
        }
        Clan clan = clanOf(player);
        if (clan == null || clan.members().size() < service.settings().clanMinMembers()) {
            return;
        }
        Map<UUID, Integer> xp = service.store().xpOf(clan.members(), season.key());
        double average = xp.values().stream().mapToInt(service::tier).average().orElse(0);
        for (int step = 0; step < goals.size(); step++) {
            ClanGoal goal = goals.get(step);
            if (average < goal.averageTier() || !service.store().reachClanGoal(clan.id(), season.key(), step)) {
                continue;
            }
            for (UUID member : clan.members()) {
                service.addXp(member, season, goal.xp(), PassService.Source.CLAN);
            }
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                for (UUID member : clan.members()) {
                    service.bus().notify(member, "pass.clan.goal", true, "tier", String.valueOf(goal.averageTier()),
                            "xp", String.valueOf(goal.xp()));
                }
            });
        }
    }
}
