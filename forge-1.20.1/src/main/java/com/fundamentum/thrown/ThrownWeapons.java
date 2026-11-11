package com.fundamentum.thrown;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.damage.DamageClasses;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ItemAttributeModifierEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

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
 * Ricochet (bounces) on {@code #fundamentum:enchantable/boomerang}. On 1.20.1 they are Forge enchantment classes
 * ({@link ThrownEnchantment}) and their modifiers come from {@link ItemAttributeModifierEvent} while held.</li>
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
    public static final RegistryObject<Attribute> SPEED = StatAttributes.add("thrown_speed", 1, 0.05, 20);
    /** Extra creatures pierced (base 0; no effect on unlimited pierce). */
    public static final RegistryObject<Attribute> PIERCE = StatAttributes.add("thrown_pierce", 0, 0, 64);
    /** Multiplier on a returning throw's range (base 1). */
    public static final RegistryObject<Attribute> RANGE = StatAttributes.add("boomerang_range", 1, 0.05, 20);
    /** Multiplier on a returning throw's return speed (base 1). */
    public static final RegistryObject<Attribute> RETURN_SPEED = StatAttributes.add("boomerang_return_speed", 1, 0.05, 20);
    /** Extra ricochets of a returning throw (base 0). */
    public static final RegistryObject<Attribute> BOUNCES = StatAttributes.add("boomerang_bounces", 0, 0, 64);

    /** The enchantment categories of the two tags (Forge's extensible enum; they read the item tags). */
    public static final EnchantmentCategory CATEGORY_THROWN = EnchantmentCategory.create("FUNDAMENTUM_THROWN",
        item -> item.builtInRegistryHolder().is(ENCHANTABLE_THROWN));
    public static final EnchantmentCategory CATEGORY_BOOMERANG = EnchantmentCategory.create("FUNDAMENTUM_BOOMERANG",
        item -> item.builtInRegistryHolder().is(ENCHANTABLE_BOOMERANG));

    private static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, "fundamentum");
    /** Throw speed (and a returning throw's return speed) +15% a level, up to III. */
    public static final RegistryObject<Enchantment> HURLING = ENCHANTMENTS.register("hurling",
        () -> new ThrownEnchantment(Enchantment.Rarity.UNCOMMON, CATEGORY_THROWN, 3, 8, 10, 58, 10));
    /** A returning throw's range +25% a level, up to III. */
    public static final RegistryObject<Enchantment> FARFLIGHT = ENCHANTMENTS.register("farflight",
        () -> new ThrownEnchantment(Enchantment.Rarity.UNCOMMON, CATEGORY_BOOMERANG, 3, 10, 9, 60, 9));
    /** One more ricochet a level, up to II. */
    public static final RegistryObject<Enchantment> RICOCHET = ENCHANTMENTS.register("ricochet",
        () -> new ThrownEnchantment(Enchantment.Rarity.RARE, CATEGORY_BOOMERANG, 2, 20, 20, 70, 20));

    private static final UUID[] HURLING_IDS = {UUID.fromString("5e0d2b7a-3c41-4f8e-9a16-1b2c3d4e5f01"), UUID.fromString("5e0d2b7a-3c41-4f8e-9a16-1b2c3d4e5f02")};
    private static final UUID[] HURLING_RETURN_IDS = {UUID.fromString("5e0d2b7a-3c41-4f8e-9a16-1b2c3d4e5f03"), UUID.fromString("5e0d2b7a-3c41-4f8e-9a16-1b2c3d4e5f04")};
    private static final UUID[] FARFLIGHT_IDS = {UUID.fromString("5e0d2b7a-3c41-4f8e-9a16-1b2c3d4e5f05"), UUID.fromString("5e0d2b7a-3c41-4f8e-9a16-1b2c3d4e5f06")};
    private static final UUID[] RICOCHET_IDS = {UUID.fromString("5e0d2b7a-3c41-4f8e-9a16-1b2c3d4e5f07"), UUID.fromString("5e0d2b7a-3c41-4f8e-9a16-1b2c3d4e5f08")};

    /** Per-item stats in data, keyed by item id (see {@link ThrownStats#withJson} for the fields). */
    public static final DataTable<JsonObject> DATA = DataTables
        .registerJson(new ResourceLocation("fundamentum", "thrown"), "fundamentum/thrown")
        .keyField("item")
        .synced();

    private static final Map<ResourceLocation, ThrownStats> REGISTERED = new ConcurrentHashMap<>();
    private record Resolved(JsonObject data, ThrownStats code, ThrownStats stats) {}
    private static final Map<ResourceLocation, Resolved> RESOLVED = new ConcurrentHashMap<>();

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, "fundamentum");
    /** The generic thrown weapon ({@link ThrownWeaponItem} throws it): drawn as its item. */
    public static final RegistryObject<EntityType<ThrownWeapon>> THROWN_WEAPON = ENTITIES.register("thrown_weapon",
        () -> EntityType.Builder.<ThrownWeapon>of(ThrownWeapon::new, MobCategory.MISC).sized(0.5F, 0.5F)
            .clientTrackingRange(6).updateInterval(1).noSummon().build("thrown_weapon"));

    /** The example boomerang and knife; set only in -Pthrowntest runs (see ThrownExamples there), null otherwise. */
    public static @Nullable RegistryObject<Item> EXAMPLE_BOOMERANG, EXAMPLE_KNIFE;

    /** No inventory slot recorded (a dispenser's throw, or an old save). */
    public static final int NO_SLOT = -1;

    /** Makes the item with this id a thrown weapon with these code defaults (data still lays over them). */
    public static void register(ResourceLocation item, ThrownStats stats) {
        REGISTERED.put(item, stats);
        RESOLVED.remove(item);
    }

    /** Whether the stack is a thrown weapon: tagged, or with stats in code or data. */
    public static boolean is(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return stack.is(THROWN) || REGISTERED.containsKey(id) || DATA.get(id) != null;
    }

    /**
     * The item's own stats: code defaults with its data laid over. An item with neither starts from
     * {@link ThrownStats#BOOMERANG} when it is in {@code #fundamentum:boomerangs}, else {@link ThrownStats#DEFAULT}.
     */
    public static ThrownStats base(ResourceLocation item) {
        ThrownStats code = REGISTERED.get(item);
        if (code == null) {
            Item it = BuiltInRegistries.ITEM.get(item);
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
        return base.modified(value(thrower, SPEED.get()) * value(thrower, StatAttributes.PROJECTILE_SPEED.get()),
            (int) Math.round(value(thrower, PIERCE.get())), value(thrower, RANGE.get()), value(thrower, RETURN_SPEED.get()),
            (int) Math.round(value(thrower, BOUNCES.get())));
    }

    /** The item's data entry (the generic renderer reads {@code "spin"}: "flat", "tumble" or "none", and {@code "spin_speed"}). */
    public static @Nullable JsonObject data(ResourceLocation item) {
        return DATA.get(item);
    }

    static double value(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? attribute.getDefaultValue() : instance.getValue();
    }

    /**
     * Throws the weapon in {@code hand}: works out its stats (read now, while it is still held, so hand-slot enchantments
     * and modifiers count), makes the entity with {@code make}, launches it and pays for the throw. A consumable weapon
     * takes one off the stack; any other costs one durability and leaves the hand (it comes back, or lies where it
     * landed). Creative keeps everything. Call it from your item's {@code use}; on the client it only answers success.
     */
    public static InteractionResultHolder<ItemStack> throwFrom(Level level, Player player, InteractionHand hand,
                                                               BiFunction<Level, ItemStack, ? extends ThrownWeapon> make) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel server) {
            ThrownStats stats = stats(stack, player);
            if (!stats.consumable()) {
                stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
                if (stack.isEmpty()) {
                    return InteractionResultHolder.sidedSuccess(stack, false);
                }
            }
            ItemStack thrown = stack.copyWithCount(1);
            ThrownWeapon entity = make.apply(level, thrown);
            entity.launch(player, thrown, stats, slotOf(player, hand));
            server.addFreshEntity(entity);
            entity.onThrown(server);
            if (!player.getAbilities().instabuild) {
                if (stats.consumable()) {
                    stack.shrink(1);
                } else {
                    player.setItemInHand(hand, ItemStack.EMPTY);
                }
            } else {
                entity.pickup = false;
            }
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    /** The default throw sound: a trident's whoosh, pitched up. */
    public static void throwSound(ServerLevel level, LivingEntity thrower) {
        level.playSound(null, thrower.blockPosition(), SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 1.3F);
    }

    /** The inventory slot of the stack in {@code hand}: the selected hotbar slot, or the offhand's. */
    public static int slotOf(Player player, InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? Inventory.SLOT_OFFHAND : player.getInventory().selected;
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

    /** The enchantments' modifiers on a held thrown weapon (1.21 lists them in the enchantment files, slot "hand"). */
    private static void enchantmentModifiers(ItemAttributeModifierEvent event) {
        int hand = event.getSlotType() == EquipmentSlot.MAINHAND ? 0 : event.getSlotType() == EquipmentSlot.OFFHAND ? 1 : -1;
        ItemStack stack = event.getItemStack();
        if (hand < 0 || stack.isEmpty() || !stack.isEnchanted()) {
            return;
        }
        int hurling = EnchantmentHelper.getItemEnchantmentLevel(HURLING.get(), stack);
        if (hurling > 0) {
            event.addModifier(SPEED.get(), new AttributeModifier(HURLING_IDS[hand], "fundamentum:enchantment.hurling",
                0.15 * hurling, AttributeModifier.Operation.MULTIPLY_BASE));
            event.addModifier(RETURN_SPEED.get(), new AttributeModifier(HURLING_RETURN_IDS[hand], "fundamentum:enchantment.hurling_return",
                0.15 * hurling, AttributeModifier.Operation.MULTIPLY_BASE));
        }
        int farflight = EnchantmentHelper.getItemEnchantmentLevel(FARFLIGHT.get(), stack);
        if (farflight > 0) {
            event.addModifier(RANGE.get(), new AttributeModifier(FARFLIGHT_IDS[hand], "fundamentum:enchantment.farflight",
                0.25 * farflight, AttributeModifier.Operation.MULTIPLY_BASE));
        }
        int ricochet = EnchantmentHelper.getItemEnchantmentLevel(RICOCHET.get(), stack);
        if (ricochet > 0) {
            event.addModifier(BOUNCES.get(), new AttributeModifier(RICOCHET_IDS[hand], "fundamentum:enchantment.ricochet",
                ricochet, AttributeModifier.Operation.ADDITION));
        }
    }

    private static TagKey<Item> itemTag(String path) {
        return TagKey.create(Registries.ITEM, new ResourceLocation("fundamentum", path));
    }

    public static void init(IEventBus modBus) {
        ENTITIES.register(modBus);
        ENCHANTMENTS.register(modBus);
        MinecraftForge.EVENT_BUS.addListener(ThrownWeapons::enchantmentModifiers);
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
