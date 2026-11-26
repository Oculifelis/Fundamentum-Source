package com.fundamentum.slots;

import com.fundamentum.port.FAttach;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves slot items from the old {@code fundamentum:slots} player data (the store Fundamentum used before Curios was
 * required) into the player's Curios slots, once, the first time each player logs in. An item whose Curios slot is
 * missing or already taken goes to the inventory, and is dropped at the player's feet when that is full. The old data
 * is then removed and the {@code fundamentum:slots_migrated} marker set, so it never runs twice for that player.
 */
public final class SlotMigration {
    private SlotMigration() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    /** The old data as it was saved: slot type to stacks, worn and cosmetic. */
    public record Loadout(Map<String, List<ItemStack>> worn, Map<String, List<ItemStack>> cosmetic) {
        public static final Loadout EMPTY = new Loadout(Map.of(), Map.of());
        private static final Codec<Map<String, List<ItemStack>>> MAP = Codec.unboundedMap(Codec.STRING, ItemStack.CODEC.listOf());
        public static final Codec<Loadout> CODEC = RecordCodecBuilder.create(i -> i.group(
            MAP.optionalFieldOf("worn", Map.of()).forGetter(Loadout::worn),
            MAP.optionalFieldOf("cosmetic", Map.of()).forGetter(Loadout::cosmetic)).apply(i, Loadout::new));

        public boolean isEmpty() {
            return this.worn.values().stream().allMatch(l -> l.stream().allMatch(ItemStack::isEmpty))
                && this.cosmetic.values().stream().allMatch(l -> l.stream().allMatch(ItemStack::isEmpty));
        }
    }

    /** The old data, read so it can be moved; nothing writes it any more. */
    public static final FAttach<Loadout> OLD = FAttach.builder(FId.of("fundamentum", "slots"), () -> Loadout.EMPTY)
        .codec(Loadout.CODEC)
        .copyOnDeath()
        .build();
    /** Set once a player's old data has been moved (or there was none). */
    public static final FAttach<Boolean> MIGRATED = FAttach.builder(FId.of("fundamentum", "slots_migrated"), () -> false)
        .codec(Codec.BOOL)
        .copyOnDeath()
        .build();

    static void init() {
        FLife.playerLoggedIn(SlotMigration::migrate);
    }

    /** What one migration did: items put in slots, given to the inventory, and dropped. */
    public record Result(int slotted, int given, int dropped) {
        public static final Result NONE = new Result(0, 0, 0);
    }

    /** Moves the player's old slot data into Curios unless that was already done; returns what moved. */
    public static Result migrate(ServerPlayer player) {
        if (MIGRATED.get(player)) return Result.NONE;
        Loadout old = OLD.has(player) ? OLD.get(player) : Loadout.EMPTY;
        int[] counts = new int[3];
        if (!old.isEmpty()) {
            old.worn().forEach((slot, stacks) -> move(player, slot, stacks, false, counts));
            old.cosmetic().forEach((slot, stacks) -> move(player, slot, stacks, true, counts));
            LOG.info("Moved {}'s old Fundamentum slot items into Curios: {} into slots, {} into the inventory, {} dropped",
                player.getName().getString(), counts[0], counts[1], counts[2]);
        }
        OLD.remove(player);
        MIGRATED.set(player, true);
        return new Result(counts[0], counts[1], counts[2]);
    }

    private static void move(ServerPlayer player, String slot, List<ItemStack> stacks, boolean cosmetic, int[] counts) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i).copy();
            if (stack.isEmpty()) continue;
            boolean free = cosmetic ? i < Slots.cosmeticSize(player, slot) && Slots.getCosmetic(player, slot, i).isEmpty()
                : i < Slots.size(player, slot) && Slots.get(player, slot, i).isEmpty();
            if (free && (cosmetic ? Slots.setCosmetic(player, slot, i, stack) : Slots.set(player, slot, i, stack))) {
                counts[0]++;
            } else if (player.getInventory().add(stack) && stack.isEmpty()) {
                counts[1]++;
            } else {
                player.drop(stack, false);
                counts[2]++;
            }
        }
    }
}
