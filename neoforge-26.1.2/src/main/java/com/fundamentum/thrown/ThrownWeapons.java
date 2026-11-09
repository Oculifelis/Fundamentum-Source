package com.fundamentum.thrown;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.damage.DamageClasses;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;

/**
 * Thrown weapons, the weapons of the thrower damage class: javelins, knives, shuriken, kunai, molotovs, and the
 * returning ones (boomerangs, chakrams, throwing axes). One type for all of them; what differs is data
 * ({@link ThrownStats}), and a returning weapon is one whose stats have a {@code returns} block.
 * <ul>
 * <li><b>Make one:</b> give the item {@link ThrownWeaponItem} (it throws the generic {@link #THROWN_WEAPON} entity), or
 * call {@link #throwFrom} from your own item's {@code use} with your own {@link ThrownWeapon} subclass for custom
 * effects. Tag it {@code #fundamentum:thrown}; a returning one {@code #fundamentum:boomerangs} (which is in
 * {@code #fundamentum:thrown}).</li>
 * <li><b>Stats:</b> code defaults with {@link #register}, laid over by datapack JSON in
 * {@code data/<ns>/fundamentum/thrown/<name>.json} keyed by {@code "item"}, then changed by the thrower's attributes:
 * {@link #SPEED} and {@link #PIERCE} for every throw, {@link #RANGE}, {@link #RETURN_SPEED} and {@link #BOUNCES} for a
 * returning one. Curios accessories, armour, effects and the enchantments all work through the usual attribute
 * modifiers.</li>
 * <li><b>Class:</b> {@code #fundamentum:thrown} is in {@code #fundamentum:class/thrower}, so the hits are thrower hits and
 * {@code fundamentum:thrower_damage} multiplies them; they also get the item's damage enchantments (Sharpness, Smite...).</li>
 * <li><b>Enchantments:</b> Hurling (throw speed) on {@code #fundamentum:enchantable/thrown}; Farflight (range) and
 * Ricochet (bounces) on {@code #fundamentum:enchantable/boomerang}.</li>
 * </ul>
 */
public final class ThrownWeapons {
    /** Item tag of every thrown weapon (returning or not). */
    public static final TagKey<Item> THROWN = itemTag("thrown");
    /** Item tag of the returning thrown weapons; it is inside {@link #THROWN}. */
    public static final TagKey<Item> BOOMERANGS = itemTag("boomerangs");
    /** Items the thrown-weapon enchantments go on. */
    public static final TagKey<Item> ENCHANTABLE_THROWN = itemTag("enchantable/thrown");
    /** Items the return-only enchantments go on. */
    public static final TagKey<Item> ENCHANTABLE_BOOMERANG = itemTag("enchantable/boomerang");

    /** The damage class of thrown hits ({@code fundamentum:thrower}, attribute {@code fundamentum:thrower_damage}). */
    public static final DamageClasses.DamageClass CLASS = DamageClasses.THROWER;

    /** Multiplier on throw speed (base 1). */
    public static final Holder<Attribute> SPEED = StatAttributes.add("thrown_speed", 1, 0.05, 20);
    /** Extra creatures pierced (base 0; no effect on unlimited pierce). */
    public static final Holder<Attribute> PIERCE = StatAttributes.add("thrown_pierce", 0, 0, 64);
    /** Multiplier on a returning throw's range (base 1). */
    public static final Holder<Attribute> RANGE = StatAttributes.add("boomerang_range", 1, 0.05, 20);
    /** Multiplier on a returning throw's return speed (base 1). */
    public static final Holder<Attribute> RETURN_SPEED = StatAttributes.add("boomerang_return_speed", 1, 0.05, 20);
    /** Extra ricochets of a returning throw (base 0). */
    public static final Holder<Attribute> BOUNCES = StatAttributes.add("boomerang_bounces", 0, 0, 64);

    public static final ResourceKey<Enchantment> HURLING = enchantment("hurling");
    public static final ResourceKey<Enchantment> FARFLIGHT = enchantment("farflight");
    public static final ResourceKey<Enchantment> RICOCHET = enchantment("ricochet");

    /** Per-item stats in data, keyed by item id (see {@link ThrownStats#withJson} for the fields). */
    public static final DataTable<JsonObject> DATA = DataTables
        .registerJson(Identifier.fromNamespaceAndPath("fundamentum", "thrown"), "fundamentum/thrown")
        .keyField("item")
        .synced();

    private static final Map<Identifier, ThrownStats> REGISTERED = new ConcurrentHashMap<>();
    private record Resolved(JsonObject data, ThrownStats code, ThrownStats stats) {}
    private static final Map<Identifier, Resolved> RESOLVED = new ConcurrentHashMap<>();

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, "fundamentum");
    /** The generic thrown weapon ({@link ThrownWeaponItem} throws it): drawn as its item. */
    public static final DeferredHolder<EntityType<?>, EntityType<ThrownWeapon>> THROWN_WEAPON = ENTITIES.register("thrown_weapon",
        id -> EntityType.Builder.<ThrownWeapon>of(ThrownWeapon::new, MobCategory.MISC).sized(0.5F, 0.5F)
            .clientTrackingRange(6).updateInterval(1).noSummon().build(ResourceKey.create(Registries.ENTITY_TYPE, id)));

    /** The example boomerang and knife; set only in -Pthrowntest runs (see ThrownExamples there), null otherwise. */
    public static @Nullable DeferredItem<Item> EXAMPLE_BOOMERANG, EXAMPLE_KNIFE;

    /** No inventory slot recorded (a dispenser's throw, or an old save). */
    public static final int NO_SLOT = -1;

    /** Makes the item with this id a thrown weapon with these code defaults (data still lays over them). */
    public static void register(Identifier item, ThrownStats stats) {
        REGISTERED.put(item, stats);
        RESOLVED.remove(item);
    }

    /** Whether the stack is a thrown weapon: tagged, or with stats in code or data. */
    public static boolean is(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return stack.is(THROWN) || REGISTERED.containsKey(id) || DATA.get(id) != null;
    }

    /**
     * The item's own stats: code defaults with its data laid over. An item with neither starts from
     * {@link ThrownStats#BOOMERANG} when it is in {@code #fundamentum:boomerangs}, else {@link ThrownStats#DEFAULT}.
     */
    public static ThrownStats base(Identifier item) {
        ThrownStats code = REGISTERED.get(item);
        if (code == null) {
            Item it = BuiltInRegistries.ITEM.getValue(item);
            code = it != null && it.builtInRegistryHolder().is(BOOMERANGS) ? ThrownStats.BOOMERANG : ThrownStats.DEFAULT;
        }
        JsonObject data = DATA.get(item);
        if (data == null) {
            return code;
        }
        Resolved r = RESOLVED.get(item);
        if (r == null || r.data != data || r.code != code) {
            r = new Resolved(data, code, code.withJson(data));
            RESOLVED.put(item, r);
        }
        return r.stats;
    }

    /** The stats of a throw of {@code stack} by {@code thrower}: the item's, changed by the thrower's thrown attributes. */
    public static ThrownStats stats(ItemStack stack, @Nullable LivingEntity thrower) {
        ThrownStats base = base(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (thrower == null) {
            return base;
        }
        return base.modified(value(thrower, SPEED) * value(thrower, StatAttributes.PROJECTILE_SPEED), (int) Math.round(value(thrower, PIERCE)),
            value(thrower, RANGE), value(thrower, RETURN_SPEED), (int) Math.round(value(thrower, BOUNCES)));
    }

    /** The item's data entry (the generic renderer reads {@code "spin"}: "flat", "tumble" or "none", and {@code "spin_speed"}). */
    public static @Nullable JsonObject data(Identifier item) {
        return DATA.get(item);
    }

    static double value(LivingEntity entity, Holder<Attribute> attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? attribute.value().getDefaultValue() : instance.getValue();
    }

    /**
     * Throws the weapon in {@code hand}: works out its stats (read now, while it is still held, so hand-slot enchantments
     * and modifiers count), makes the entity with {@code make}, launches it and pays for the throw. A consumable weapon
     * takes one off the stack; any other costs one durability and leaves the hand (it comes back, or lies where it
     * landed). Creative keeps everything. Call it from your item's {@code use}; on the client it only answers SUCCESS.
     */
    public static InteractionResult throwFrom(Level level, Player player, InteractionHand hand,
                                              BiFunction<Level, ItemStack, ? extends ThrownWeapon> make) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel server) {
            ThrownStats stats = stats(stack, player);
            if (!stats.consumable()) {
                stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
                if (stack.isEmpty()) {
                    return InteractionResult.SUCCESS;
                }
            }
            ItemStack thrown = stack.copyWithCount(1);
            ThrownWeapon entity = make.apply(level, thrown);
            entity.launch(player, thrown, stats, slotOf(player, hand));
            server.addFreshEntity(entity);
            entity.onThrown(server);
            if (!player.hasInfiniteMaterials()) {
                if (stats.consumable()) {
                    stack.shrink(1);
                } else {
                    player.setItemInHand(hand, ItemStack.EMPTY);
                }
            } else {
                entity.pickup = false;
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** The default throw sound: a trident's whoosh, pitched up. */
    public static void throwSound(ServerLevel level, LivingEntity thrower) {
        level.playSound(null, thrower.blockPosition(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.0F, 1.3F);
    }

    /** The inventory slot of the stack in {@code hand}: the selected hotbar slot, or the offhand's. */
    public static int slotOf(Player player, InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? Inventory.SLOT_OFFHAND : player.getInventory().getSelectedSlot();
    }

    /** Puts a caught weapon back: its own slot if empty, else the first free hotbar slot, else the inventory; false if full. */
    public static boolean giveBack(Player player, ItemStack stack, int slot) {
        Inventory inventory = player.getInventory();
        if ((slot >= 0 && slot < Inventory.INVENTORY_SIZE || slot == Inventory.SLOT_OFFHAND) && inventory.getItem(slot).isEmpty()) {
            inventory.setItem(slot, stack);
            inventory.setChanged();
            return true;
        }
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, stack);
                inventory.setChanged();
                return true;
            }
        }
        return inventory.add(stack);
    }

    private static TagKey<Item> itemTag(String path) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("fundamentum", path));
    }

    private static ResourceKey<Enchantment> enchantment(String name) {
        return ResourceKey.create(Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath("fundamentum", name));
    }

    public static void init(IEventBus modBus) {
        ENTITIES.register(modBus);
        // The example boomerang and knife: ThrownExamples is in src/throwntest, built only with -Pthrowntest.
        // It never ships: Fundamentum is a library and registers no items of its own.
        try {
            Class.forName("com.fundamentum.thrown.ThrownExamples").getMethod("init", IEventBus.class).invoke(null, modBus);
        } catch (ClassNotFoundException absent) {
            // a normal build
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ThrownSelfTest.init();
    }

    private ThrownWeapons() {}
}
