package fr.eternom.eterReward.module.pass;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * La boîte du pass (/pass box), 6 lignes : jusqu'à 28 colis (les plus anciens d'abord), un clic pour en prendre un,
 * le coffre en bas pour tout prendre. Ce qui ne tient pas dans l'inventaire reste dans la boîte.
 * <pre>
 *  ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *  ▣ c c c c c c c ▣     c = un colis (clic : le prendre)
 *  ▣ c c c c c c c ▣
 *  ▣ c c c c c c c ▣
 *  ▣ c c c c c c c ▣
 *  ▣ ▣ ▣ « ▣ ⇊ ▣ ▣ ▣     « = retour au pass · ⇊ = tout prendre
 * </pre>
 */
class ParcelMenu implements Menu {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    private static final int BACK = 48;
    private static final int TAKE_ALL = 50;

    private final PassService service;
    private final Player viewer;
    private final List<Parcel> parcels;
    private final Inventory inventory;

    ParcelMenu(PassService service, Player viewer, List<Parcel> parcels) {
        this.service = service;
        this.viewer = viewer;
        this.parcels = parcels.size() > SLOTS.length ? parcels.subList(0, SLOTS.length) : parcels;
        this.inventory = Bukkit.createInventory(this, 54, text("pass.box.title", "count", String.valueOf(parcels.size())));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            Sounds.page(player);
            service.open(player);
            return;
        }
        if (slot == TAKE_ALL) {
            if (parcels.isEmpty()) {
                Sounds.refuse(player);
            } else {
                Sounds.click(player);
                service.takeParcels(player, parcels);
            }
            return;
        }
        for (int i = 0; i < parcels.size(); i++) {
            if (SLOTS[i] == slot) {
                Sounds.click(player);
                service.takeParcels(player, List.of(parcels.get(i)));
                return;
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.BROWN_STAINED_GLASS_PANE);
        for (int i = 0; i < parcels.size(); i++) {
            inventory.setItem(SLOTS[i], parcelItem(parcels.get(i)));
        }
        if (parcels.isEmpty()) {
            inventory.setItem(22, Items.item(Material.MINECART, text("pass.box.empty"), List.of()));
        }
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("pass.box.back"), List.of()));
        inventory.setItem(TAKE_ALL, Items.item(parcels.isEmpty() ? Material.GRAY_DYE : Material.HOPPER, text("pass.box.take-all"),
                List.of(text("pass.box.take-all-lore", "count", String.valueOf(parcels.size()))), !parcels.isEmpty()));
    }

    private ItemStack parcelItem(Parcel parcel) {
        List<Component> lore = new ArrayList<>();
        if (parcel.special().startsWith("badge:")) {
            lore.add(text("pass.box.badge-lore"));
        } else {
            lore.add(service.messages().render(service.raw(viewer, "pass.menu.reward"),
                    Placeholder.component("content", service.rewards().describe(viewer, parcel.asReward()))));
        }
        if (parcel.season() != null && !parcel.season().isEmpty()) {
            lore.add(text("pass.box.season", "season", parcel.season()));
        }
        lore.add(Component.empty());
        lore.add(text("pass.box.take"));
        Material icon = parcel.special().startsWith("badge:") ? Material.NETHER_STAR
                : parcel.items().isEmpty() ? parcel.asReward().icon() : parcel.items().getFirst().material();
        return Items.item(icon, label(parcel), lore);
    }

    /** "tier:12:premium" -> « Palier 12 · premium », "badge" -> « Insigne du premium »... */
    private Component label(Parcel parcel) {
        String label = parcel.label() == null ? "" : parcel.label();
        String[] parts = label.split(":");
        if (parts.length == 3 && parts[0].equals("tier")) {
            int tier;
            try {
                tier = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return text("pass.box.label-other");
            }
            return text(parts[2].equals("premium") ? "pass.box.label-premium" : "pass.box.label-free",
                    "tier", service.tierLabel(viewer, tier));
        }
        if (label.equals("badge")) {
            return text("pass.box.label-badge");
        }
        return text("pass.box.label-other");
    }

    private Component text(String key, String... placeholders) {
        return service.messages().get(viewer, key, placeholders);
    }
}
