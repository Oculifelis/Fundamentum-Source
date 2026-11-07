package com.fundamentum.cosmetic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

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
    /** Saved under the player's persisted data, which Forge carries over death. */
    private static final String KEY = "fundamentum:cosmetic_armor";
    private static final String VERSION = "1";
    static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("fundamentum", "cosmetic_armor"), () -> VERSION, VERSION::equals, VERSION::equals);

    /** One player's cosmetic armour, in {@link #SLOTS} order. Never mutated: changes set a new one. */
    public record Outfit(List<ItemStack> stacks) {
        public static final Outfit EMPTY = new Outfit(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));

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

        ListTag save() {
            ListTag list = new ListTag();
            for (ItemStack stack : stacks) {
                list.add(stack.save(new CompoundTag()));
            }
            return list;
        }

        static Outfit load(ListTag list) {
            List<ItemStack> stacks = new ArrayList<>(SLOTS.length);
            for (int i = 0; i < list.size(); i++) {
                stacks.add(ItemStack.of(list.getCompound(i)));
            }
            return of(stacks);
        }
    }

    /** Decoded outfits by player, on both sides (the server's are loaded from the player's data on first use). */
    private static final Map<Player, Outfit> OUTFITS = Collections.synchronizedMap(new WeakHashMap<>());

    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, "fundamentum");
    public static final RegistryObject<MenuType<CosmeticArmorMenu>> MENU = MENUS.register("cosmetic_armor",
        () -> IForgeMenuType.create((id, inventory, buf) -> new CosmeticArmorMenu(id, inventory)));

    public static Outfit outfit(Player player) {
        Outfit outfit = OUTFITS.get(player);
        if (outfit == null) {
            CompoundTag data = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
            outfit = player.level().isClientSide() || !data.contains(KEY, Tag.TAG_LIST) ? Outfit.EMPTY : Outfit.load(data.getList(KEY, Tag.TAG_COMPOUND));
            OUTFITS.put(player, outfit);
        }
        return outfit;
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
        OUTFITS.put(player, outfit);
        CompoundTag root = player.getPersistentData();
        CompoundTag data = root.getCompound(Player.PERSISTED_NBT_TAG);
        if (outfit.isEmpty()) {
            data.remove(KEY);
        } else {
            data.put(KEY, outfit.save());
        }
        root.put(Player.PERSISTED_NBT_TAG, data);
        if (player instanceof ServerPlayer serverPlayer) {
            CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> serverPlayer), new SyncPayload(player.getId(), outfit));
        }
    }

    /** Client side: the outfit a sync packet brought. */
    static void remember(Player player, Outfit outfit) {
        OUTFITS.put(player, outfit);
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
        MENUS.register(modBus);
        CHANNEL.messageBuilder(OpenPayload.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder((p, buf) -> {}).decoder(buf -> OpenPayload.INSTANCE).consumerMainThread(OpenPayload::handle).add();
        CHANNEL.messageBuilder(SyncPayload.class, 1, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(SyncPayload::encode).decoder(SyncPayload::decode).consumerMainThread(SyncPayload::handle).add();
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.StartTracking e) -> {
            if (e.getTarget() instanceof Player target && e.getEntity() instanceof ServerPlayer tracker) {
                send(tracker, target);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> sendSelf(e.getEntity()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> sendSelf(e.getEntity()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> sendSelf(e.getEntity()));
    }

    private static void sendSelf(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            send(serverPlayer, serverPlayer);
        }
    }

    private static void send(ServerPlayer to, Player about) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> to), new SyncPayload(about.getId(), outfit(about)));
    }

    /** Client to server: open the cosmetic armour screen. */
    public record OpenPayload() {
        public static final OpenPayload INSTANCE = new OpenPayload();

        void handle(Supplier<NetworkEvent.Context> context) {
            ServerPlayer player = context.get().getSender();
            if (player != null && player.isAlive() && !player.isSpectator()) {
                // Leaving the inventory: hand back the crafting grid and the carried stack, as closing it would.
                if (player.containerMenu == player.inventoryMenu) {
                    player.inventoryMenu.removed(player);
                }
                player.openMenu(new SimpleMenuProvider((id, inventory, p) -> CosmeticArmorMenu.forPlayer(id, inventory),
                    Component.translatable("fundamentum.cosmetic_armor")));
            }
        }
    }

    /** Server to client: a player's outfit. */
    record SyncPayload(int entityId, Outfit outfit) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(entityId);
            for (ItemStack stack : outfit.stacks()) {
                buf.writeItem(stack);
            }
        }

        static SyncPayload decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            List<ItemStack> stacks = new ArrayList<>(SLOTS.length);
            for (int i = 0; i < SLOTS.length; i++) {
                stacks.add(buf.readItem());
            }
            return new SyncPayload(id, new Outfit(List.copyOf(stacks)));
        }

        void handle(Supplier<NetworkEvent.Context> context) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CosmeticArmorClient.sync(entityId, outfit));
        }
    }

    private CosmeticArmor() {}
}
