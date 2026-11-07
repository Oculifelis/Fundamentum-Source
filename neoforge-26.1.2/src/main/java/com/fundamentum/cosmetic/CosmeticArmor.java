package com.fundamentum.cosmetic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Cosmetic armour: four vanity slots (head, chest, legs, feet) per player, set in their own small screen opened from a
 * button on the inventory. A filled cosmetic slot is drawn instead of the real armour in that slot; the real armour
 * still does all the protecting. Kept on death, saved with the player and synced to everyone who can see them.
 * Cosmetic curios use Curios' own cosmetic slots (see com.fundamentum.curios.Slots). Mods that draw their own armour
 * pieces call {@link #shown}.
 */
public final class CosmeticArmor {
    /** The slots, in screen order. */
    public static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** One player's cosmetic armour, in {@link #SLOTS} order. Never mutated: changes set a new one. */
    public record Outfit(List<ItemStack> stacks) {
        public static final Outfit EMPTY = new Outfit(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
        static final MapCodec<Outfit> CODEC = ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").xmap(Outfit::of, Outfit::stacks);
        static final StreamCodec<RegistryFriendlyByteBuf, Outfit> STREAM_CODEC = ItemStack.OPTIONAL_LIST_STREAM_CODEC.map(Outfit::of, Outfit::stacks);

        static Outfit of(List<ItemStack> stacks) {
            List<ItemStack> copy = new ArrayList<>(SLOTS.length);
            for (int i = 0; i < SLOTS.length; i++) {
                copy.add(i < stacks.size() ? stacks.get(i).copy() : ItemStack.EMPTY);
            }
            return new Outfit(List.copyOf(copy));
        }

        static Outfit of(Container container) {
            List<ItemStack> stacks = new ArrayList<>(SLOTS.length);
            for (int i = 0; i < SLOTS.length; i++) {
                stacks.add(container.getItem(i));
            }
            return of(stacks);
        }

        public ItemStack get(EquipmentSlot slot) {
            int i = index(slot);
            return i < 0 ? ItemStack.EMPTY : stacks.get(i);
        }

        public boolean isEmpty() {
            return stacks.stream().allMatch(ItemStack::isEmpty);
        }
    }

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "fundamentum");
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, "fundamentum");

    public static final Supplier<AttachmentType<Outfit>> OUTFIT = ATTACHMENTS.register("cosmetic_armor", () -> AttachmentType.builder(() -> Outfit.EMPTY)
        .serialize(Outfit.CODEC, outfit -> !outfit.isEmpty())
        .copyOnDeath()
        .sync(Outfit.STREAM_CODEC)
        .build());

    public static final Supplier<MenuType<CosmeticArmorMenu>> MENU = MENUS.register("cosmetic_armor",
        () -> IMenuTypeExtension.create((id, inventory, buf) -> new CosmeticArmorMenu(id, inventory)));

    public static Outfit outfit(Player player) {
        return player.getData(OUTFIT);
    }

    /** The cosmetic piece in a slot, or empty. */
    public static ItemStack get(Player player, EquipmentSlot slot) {
        return outfit(player).get(slot);
    }

    /** What a player shows in an armour slot: the cosmetic piece if there is one, else the real armour. */
    public static ItemStack shown(Player player, EquipmentSlot slot) {
        ItemStack cosmetic = get(player, slot);
        return cosmetic.isEmpty() ? player.getItemBySlot(slot) : cosmetic;
    }

    /** Replaces a player's cosmetic armour (server side; it syncs). */
    public static void set(Player player, Outfit outfit) {
        player.setData(OUTFIT, outfit);
    }

    static int index(EquipmentSlot slot) {
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    public static void init(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        MENUS.register(modBus);
        modBus.addListener(CosmeticArmor::payloads);
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(OpenPayload.TYPE, OpenPayload.STREAM_CODEC, OpenPayload::handle);
    }

    /** Client to server: open the cosmetic armour screen. */
    public record OpenPayload() implements CustomPacketPayload {
        public static final OpenPayload INSTANCE = new OpenPayload();
        public static final CustomPacketPayload.Type<OpenPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("fundamentum", "open_cosmetic_armor"));
        public static final StreamCodec<ByteBuf, OpenPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(OpenPayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player && player.isAlive() && !player.isSpectator()) {
                // Leaving the inventory: hand back the crafting grid and the carried stack, as closing it would.
                if (player.containerMenu == player.inventoryMenu) {
                    player.inventoryMenu.removed(player);
                }
                player.openMenu(new SimpleMenuProvider((id, inventory, p) -> CosmeticArmorMenu.forPlayer(id, inventory),
                    Component.translatable("fundamentum.cosmetic_armor")));
            }
        }
    }

    private CosmeticArmor() {}
}
