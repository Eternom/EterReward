package fr.eternom.eterReward.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Ce qu'EterReward offre aux autres plugins : la série de récompenses du jour d'un joueur (/daily) et le pass de saison.
 * Personne d'autre ne lit les tables eterreward_* : on demande ici.
 * <pre>
 *     // compileOnly("com.github.Eternom:EterReward:&lt;tag&gt;") ; plugin.yml : softdepend: [EterReward]
 * </pre>
 */
public interface RewardApi {

    /** L'API d'EterReward si le plugin tourne sur ce serveur. */
    static Optional<RewardApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(RewardApi.class));
    }

    /** Jours de la série en cours déjà réclamés (0 à 7 ; 0 si la série est perdue). Bloquant (base). */
    int streak(UUID player);

    /** Déjà réclamé aujourd'hui. Bloquant (base). */
    boolean claimedToday(UUID player);

    /** Le menu /daily. Thread principal. */
    void openMenu(Player player);

    /** Donne de l'XP du pass de saison en cours (ex : une action propre à un autre plugin). Bloquant (base). */
    void addPassXp(UUID player, int amount);

    /** Le menu /pass. Thread principal. */
    void openPass(Player player);
}
