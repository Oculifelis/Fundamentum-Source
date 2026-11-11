package com.fundamentum.shield;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.damage.Elements;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.thrown.ThrownStats;
import com.fundamentum.thrown.ThrownWeapon;
import com.fundamentum.thrown.ThrownWeapons;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import org.jspecify.annotations.Nullable;

/**
 * Shields as a data-driven weapon type. One JSON format ({@link ShieldStats}) on every version; on 26.x it becomes vanilla's
 * {@code minecraft:blocks_attacks} and {@code minecraft:use_effects} components on the stack, so vanilla does the blocking
 * and every other mod sees an ordinary shield. On top: the parry, the bash, element resistance, the slowdown while
 * raised and an optional throw (it flies as a boomerang, {@link ThrownWeapons}).
 * <ul>
 * <li><b>Make one:</b> register a {@link ShieldTypeItem} (its {@link ShieldTypeItem#properties} give the vanilla shield's
 * components: banners, off-hand, break sound), tag it {@code #fundamentum:shields}, and draw it with the
 * {@code fundamentum:shield} special model (a {@code texture} under {@code textures/entity/shield/}, plus its
 * {@code _nopattern} twin). Any other item, vanilla's shield included, becomes one by having a data entry.</li>
 * <li><b>Stats:</b> code defaults with {@link #register}, laid over by datapack JSON in
 * {@code data/<ns>/fundamentum/shields/<name>.json} keyed by {@code "item"} (fields in {@link ShieldStats#withJson}).
 * Held stacks get the matching components stamped on whenever they are equipped and once a second while held, so a data
 * reload reaches them.</li>
 * <li><b>Banners, cooldown, off-hand:</b> untouched vanilla. Give the item a {@code crafting_special_shielddecoration}
 * recipe for banners.</li>
 * <li><b>Better Combat:</b> nothing here is on left click, never swings with it, never uses its attack cooldown.</li>
 * </ul>
 */
public final class Shields {
    /** Item tag of every Fundamentum shield. */
    public static final TagKey<Item> SHIELDS = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("fundamentum", "shields"));

    /** Per-item stats in data, keyed by item id (see {@link ShieldStats#withJson} for the fields). */
    public static final DataTable<JsonObject> DATA = DataTables
        .registerJson(Identifier.fromNamespaceAndPath("fundamentum", "shields"), "fundamentum/shields")
        .keyField("item")
        .synced();

    private static final Map<Identifier, ShieldStats> REGISTERED = new ConcurrentHashMap<>();
    private record Resolved(JsonObject data, ShieldStats code, ShieldStats stats) {}
    private static final Map<Identifier, Resolved> RESOLVED = new ConcurrentHashMap<>();
    /** The components made from each stats object; cleared when tags reload (the holder sets change). */
    private static final Map<ShieldStats, BlocksAttacks> COMPONENTS = new IdentityHashMap<>();

    /** Per-entity state of the parry and the bash (server side). */
    private static final class State {
        long previousRaise = Long.MIN_VALUE;
        boolean armed = true;
        long lastBash = Long.MIN_VALUE;
        boolean wasSneaking;
    }
    private static final Map<LivingEntity, State> STATES = new WeakHashMap<>();
    private record Reflection(Projectile projectile, LivingEntity parrier, @Nullable Entity shooter, double speed) {}
    private static final List<Reflection> REFLECTIONS = new ArrayList<>();
    private static final List<Player> UNDISABLE = new ArrayList<>();

    /** Makes the item with this id a shield with these code defaults (data still lays over them). */
    public static void register(Identifier item, ShieldStats stats) {
        REGISTERED.put(item, stats);
        RESOLVED.remove(item);
    }

    /** Whether the stack is a Fundamentum shield: tagged, a {@link ShieldTypeItem}, or with stats in code or data. */
    public static boolean is(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return stack.getItem() instanceof ShieldTypeItem || stack.is(SHIELDS) || REGISTERED.containsKey(id) || DATA.get(id) != null;
    }

    /**
     * The item's stats: code defaults with its data laid over. Without code defaults a {@link ShieldTypeItem} or tagged item
     * starts from {@link ShieldStats#DEFAULT}, any other (vanilla's shield) from {@link ShieldStats#VANILLA}.
     */
    public static ShieldStats base(Identifier item) {
        ShieldStats code = REGISTERED.get(item);
        if (code == null) {
            Item it = BuiltInRegistries.ITEM.getValue(item);
            code = it instanceof ShieldTypeItem || it != null && it.builtInRegistryHolder().is(SHIELDS) ? ShieldStats.DEFAULT : ShieldStats.VANILLA;
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

    /** The stack's stats, or null when it is no Fundamentum shield. */
    public static @Nullable ShieldStats stats(ItemStack stack) {
        return is(stack) ? base(BuiltInRegistries.ITEM.getKey(stack.getItem())) : null;
    }

    // ---- vanilla components -------------------------------------------------------------------------------------

    /** Vanilla's blocking component for these stats (the damage type ids and tags looked up in {@code types}). */
    public static BlocksAttacks blocksAttacks(ShieldStats stats, HolderGetter<DamageType> types) {
        synchronized (COMPONENTS) {
            BlocksAttacks done = COMPONENTS.get(stats);
            if (done != null) {
                return done;
            }
        }
        List<BlocksAttacks.DamageReduction> reductions = new ArrayList<>();
        for (ShieldStats.Reduction r : stats.damageReductions()) {
            reductions.add(new BlocksAttacks.DamageReduction((float) r.horizontalBlockingAngle(),
                r.type() == null ? Optional.empty() : Optional.of(holderSet(r.type(), types)), (float) r.base(), (float) r.factor()));
        }
        ShieldStats.ItemDamage d = stats.itemDamage();
        BlocksAttacks made = new BlocksAttacks((float) stats.blockDelaySeconds(), (float) stats.disableCooldownScale(), reductions,
            new BlocksAttacks.ItemDamageFunction((float) d.threshold(), (float) d.base(), (float) d.factor()),
            stats.bypassedBy() == null ? Optional.empty() : Optional.of(holderSet(List.of(stats.bypassedBy()), types)),
            sound(stats.blockSound()), sound(stats.disabledSound()));
        synchronized (COMPONENTS) {
            COMPONENTS.put(stats, made);
        }
        return made;
    }

    /** Vanilla's use component for these stats: the slowdown and sprinting while raised. */
    public static UseEffects useEffects(ShieldStats stats) {
        return new UseEffects(stats.canSprint(), UseEffects.DEFAULT.interactVibrations(), (float) stats.speedWhileBlocking());
    }

    private static HolderSet<DamageType> holderSet(List<String> idsAndTags, HolderGetter<DamageType> types) {
        if (idsAndTags.size() == 1 && idsAndTags.getFirst().startsWith("#")) {
            return types.getOrThrow(TagKey.create(Registries.DAMAGE_TYPE, Identifier.parse(idsAndTags.getFirst().substring(1))));
        }
        List<Holder<DamageType>> holders = new ArrayList<>();
        for (String s : idsAndTags) {
            if (s.startsWith("#")) {
                types.get(TagKey.create(Registries.DAMAGE_TYPE, Identifier.parse(s.substring(1)))).ifPresent(set -> set.forEach(holders::add));
            } else {
                types.get(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(s))).ifPresent(holders::add);
            }
        }
        return HolderSet.direct(holders);
    }

    private static Optional<Holder<SoundEvent>> sound(@Nullable String id) {
        if (id == null) {
            return Optional.empty();
        }
        Identifier key = Identifier.parse(id);
        return Optional.of(BuiltInRegistries.SOUND_EVENT.get(key).<Holder<SoundEvent>>map(h -> h)
            .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(key))));
    }

    /**
     * Puts the shield's components on the stack when they differ (a new item, new data, a reload). Called whenever a
     * shield is equipped and once a second for held ones; call it yourself for a stack you hand to a creature.
     */
    public static void stamp(ItemStack stack, Level level) {
        ShieldStats stats = stats(stack);
        if (stats == null) {
            return;
        }
        BlocksAttacks blocks = blocksAttacks(stats, level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE));
        if (!Objects.equals(stack.get(DataComponents.BLOCKS_ATTACKS), blocks)) {
            stack.set(DataComponents.BLOCKS_ATTACKS, blocks);
        }
        UseEffects use = useEffects(stats);
        if (!use.equals(stack.getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT))) {
            stack.set(DataComponents.USE_EFFECTS, use);
        }
    }

    // ---- parry --------------------------------------------------------------------------------------------------

    /** Whether a hit on {@code user} now, while blocking with {@code stats}, falls in the parry window. */
    public static boolean parryTiming(LivingEntity user, ShieldStats stats) {
        if (stats.parry() == null || !user.isUsingItem()) {
            return false;
        }
        State state = state(user);
        return state.armed && stats.parry().inWindow(user.getTicksUsingItem() - stats.blockDelayTicks());
    }

    private static void onUseStart(LivingEntityUseItemEvent.Start event) {
        LivingEntity user = event.getEntity();
        if (!(user.level() instanceof ServerLevel level)) {
            return;
        }
        ShieldStats stats = stats(event.getItem());
        if (stats == null) {
            return;
        }
        stamp(event.getItem(), level);
        State state = state(user);
        long now = level.getGameTime();
        state.armed = stats.parry() == null || stats.parry().armed(state.previousRaise, now);
        state.previousRaise = now;
    }

    private static void onBlock(LivingShieldBlockEvent event) {
        LivingEntity user = event.getEntity();
        if (!event.getBlocked() || !(user.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack shield = user.getItemBlockingWith();
        ShieldStats stats = shield == null ? null : stats(shield);
        if (stats == null || !parryTiming(user, stats)) {
            return;
        }
        ShieldStats.Parry parry = stats.parry();
        DamageSource source = event.getDamageSource();
        event.setBlockedDamage(event.getOriginalBlockedDamage() > 0 ? event.getDamageContainer().getNewDamage() : 0);
        if (event.getBlockedDamage() <= 0) {
            return;
        }
        event.setShieldDamage(0);
        state(user).armed = false; // one parry per raise
        level.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ANVIL_LAND, user.getSoundSource(), 0.5F,
            1.6F + level.getRandom().nextFloat() * 0.2F);
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile projectile) {
            if (parry.reflect()) {
                REFLECTIONS.add(new Reflection(projectile, user, source.getEntity(), projectile.getDeltaMovement().length() * parry.reflectSpeed()));
            }
        } else if (direct instanceof LivingEntity attacker) {
            stagger(attacker, user, parry.knockback(), parry.staggerTicks());
            if (user instanceof Player player) {
                UNDISABLE.add(player); // a parried axe does not disable the shield
            }
        }
    }

    private static void afterTick(ServerTickEvent.Post event) {
        for (Reflection r : REFLECTIONS) {
            Projectile p = r.projectile;
            if (p.isRemoved()) {
                continue;
            }
            Vec3 to = r.shooter != null && r.shooter.isAlive() && r.shooter != r.parrier
                ? r.shooter.getEyePosition().subtract(p.position())
                : r.parrier.getLookAngle();
            p.setDeltaMovement(to.normalize().scale(Math.max(0.5, r.speed)));
            p.setOwner(r.parrier);
            p.hurtMarked = true;
        }
        REFLECTIONS.clear();
        for (Player player : UNDISABLE) {
            ItemStack shield = player.getUseItem().isEmpty() ? player.getOffhandItem() : player.getUseItem();
            if (!shield.isEmpty()) {
                player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(shield));
            }
        }
        UNDISABLE.clear();
    }

    /** Knocks {@code target} back from {@code from} and slows and weakens it for {@code ticks}. */
    public static void stagger(LivingEntity target, LivingEntity from, double knockback, int ticks) {
        if (knockback > 0) {
            target.knockback(knockback, from.getX() - target.getX(), from.getZ() - target.getZ());
            target.hurtMarked = true;
        }
        if (ticks > 0) {
            target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 2), from);
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0), from);
        }
        if (target instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        if (target instanceof Player player && !ShieldCompat.betterCombat()) {
            player.resetAttackStrengthTicker(); // a staggered player starts their swing over (Better Combat keeps its own)
        }
    }

    // ---- element resistance ---------------------------------------------------------------------------------------

    private static void onIncoming(LivingIncomingDamageEvent event) {
        LivingEntity user = event.getEntity();
        ItemStack shield = user.getItemBlockingWith();
        ShieldStats stats = shield == null ? null : stats(shield);
        if (stats == null || stats.elementResistance().isEmpty()) {
            return;
        }
        DamageSource source = event.getSource();
        if (stats.bypassed(matcher(source)) || angle(user, source) > Math.toRadians(stats.maxAngle())) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Elements.Element e : Elements.of(source)) {
            ids.add(e.id().toString());
        }
        double m = stats.elementMultiplier(ids);
        if (m != 1) {
            event.setAmount((float) Math.max(0, event.getAmount() * m));
        }
    }

    /** The angle (radians) between where {@code user} looks and where the hit came from, as vanilla measures it. */
    public static double angle(LivingEntity user, DamageSource source) {
        Vec3 from = source.getSourcePosition();
        if (from == null) {
            return Math.PI;
        }
        Vec3 view = user.calculateViewVector(0.0F, user.getYHeadRot());
        Vec3 to = from.subtract(user.position());
        to = new Vec3(to.x, 0.0, to.z).normalize();
        return Math.acos(Math.max(-1, Math.min(1, to.dot(view))));
    }

    /** Matches a damage source's type against an id or {@code #tag} from data. */
    public static ShieldStats.TypeMatcher matcher(DamageSource source) {
        return s -> s.startsWith("#")
            ? source.is(TagKey.create(Registries.DAMAGE_TYPE, Identifier.parse(s.substring(1))))
            : source.typeHolder().is(Identifier.parse(s));
    }

    // ---- bash ---------------------------------------------------------------------------------------------------

    /**
     * Bashes with the shield in {@code hand}: every creature in the cone in front is hit, knocked back and staggered. False
     * when it is no shield, has no bash, or the bash is cooling down. Server side; never touches the attack cooldown.
     */
    public static boolean bash(LivingEntity user, InteractionHand hand) {
        ItemStack stack = user.getItemInHand(hand);
        ShieldStats stats = stats(stack);
        if (stats == null || stats.bash() == null || !(user.level() instanceof ServerLevel level)) {
            return false;
        }
        ShieldStats.Bash bash = stats.bash();
        State state = state(user);
        long now = level.getGameTime();
        if (!bash.ready(state.lastBash, now)) {
            return false;
        }
        state.lastBash = now;
        Vec3 eye = user.getEyePosition();
        Vec3 look = user.getLookAngle();
        DamageSource source = user instanceof Player player ? level.damageSources().playerAttack(player) : level.damageSources().mobAttack(user);
        int hits = 0;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, user.getBoundingBox().inflate(bash.reach()),
            e -> e != user && e.isAlive() && !e.isSpectator() && !user.isAlliedTo(e) && e.getVehicle() != user)) {
            AABB box = target.getBoundingBox();
            double nx = Math.max(box.minX, Math.min(eye.x, box.maxX));
            double ny = Math.max(box.minY, Math.min(eye.y, box.maxY));
            double nz = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
            Vec3 aim = box.getCenter().subtract(eye);
            if (!bash.inCone(nx - eye.x, ny - eye.y, nz - eye.z, look.x, look.y, look.z) && !bash.inCone(aim.x, aim.y, aim.z, look.x, look.y, look.z)) {
                continue;
            }
            if (bash.damage() > 0) {
                target.hurtServer(level, source, (float) bash.damage());
            }
            stagger(target, user, bash.knockback(), bash.staggerTicks());
            hits++;
        }
        level.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, 1.0F,
            0.6F + level.getRandom().nextFloat() * 0.1F);
        if (hits > 0 && bash.durability() > 0) {
            stack.hurtAndBreak(bash.durability(), user, hand.asEquipmentSlot());
        }
        if (!ShieldCompat.betterCombat()) {
            user.swing(hand, true); // with Better Combat the shield hand is left alone, so its animations never double up
        }
        return true;
    }

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (player.tickCount % 20 == 0) {
            stamp(player.getMainHandItem(), level);
            stamp(player.getOffhandItem(), level);
        }
        State state = state(player);
        boolean sneaking = player.isShiftKeyDown();
        if (sneaking && !state.wasSneaking && player.isBlocking()) {
            ShieldStats stats = stats(player.getUseItem());
            if (stats != null && stats.bash() != null && ShieldStats.Bash.SNEAK.equals(stats.bash().trigger())) {
                bash(player, player.getUsedItemHand());
            }
        }
        state.wasSneaking = sneaking;
    }

    private static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        ShieldStats stats = stats(stack);
        if (stats == null || player.getCooldowns().isOnCooldown(stack)) {
            return;
        }
        if (stats.throwable()) {
            event.setCanceled(true);
            event.setCancellationResult(throwShield(player.level(), player, event.getHand(), stats));
        } else if (stats.bash() != null && ShieldStats.Bash.USE.equals(stats.bash().trigger())) {
            event.setCanceled(true);
            if (!player.level().isClientSide()) {
                bash(player, event.getHand());
            }
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // ---- throw ----------------------------------------------------------------------------------------------------

    /** The throw of a throwable shield: its {@code thrown} fields over a boomerang's, changed by the thrower's stats. */
    public static ThrownStats thrownStats(ShieldStats stats, @Nullable LivingEntity thrower) {
        ThrownStats base = ThrownStats.BOOMERANG.withJson(stats.thrown() == null ? new JsonObject() : stats.thrown());
        if (thrower == null) {
            return base;
        }
        return base.modified(thrower.getAttributeValue(ThrownWeapons.SPEED) * thrower.getAttributeValue(StatAttributes.PROJECTILE_SPEED),
            (int) Math.round(thrower.getAttributeValue(ThrownWeapons.PIERCE)), thrower.getAttributeValue(ThrownWeapons.RANGE),
            thrower.getAttributeValue(ThrownWeapons.RETURN_SPEED), (int) Math.round(thrower.getAttributeValue(ThrownWeapons.BOUNCES)));
    }

    /** Throws the shield in {@code hand} as a returning thrown weapon (one durability; it comes back to its slot). */
    public static InteractionResult throwShield(Level level, Player player, InteractionHand hand, ShieldStats stats) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel server) {
            ThrownStats thrown = thrownStats(stats, player);
            stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
            if (stack.isEmpty()) {
                return InteractionResult.SUCCESS;
            }
            ItemStack copy = stack.copyWithCount(1);
            ThrownWeapon entity = new ThrownWeapon(ThrownWeapons.THROWN_WEAPON.get(), level);
            entity.launch(player, copy, thrown, ThrownWeapons.slotOf(player, hand));
            server.addFreshEntity(entity);
            entity.onThrown(server);
            if (!player.hasInfiniteMaterials()) {
                player.setItemInHand(hand, ItemStack.EMPTY);
            } else {
                entity.pickup = false;
            }
        }
        return InteractionResult.SUCCESS;
    }

    // ---- wiring ---------------------------------------------------------------------------------------------------

    private static State state(LivingEntity entity) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(entity, e -> new State());
        }
    }

    private static void onEquip(LivingEquipmentChangeEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level) {
            stamp(event.getTo(), level);
        }
    }

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(Shields::onUseStart);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, Shields::onBlock);
        ShieldBlockHook.init();
        NeoForge.EVENT_BUS.addListener(Shields::onIncoming);
        NeoForge.EVENT_BUS.addListener(Shields::afterTick);
        NeoForge.EVENT_BUS.addListener(Shields::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(Shields::onRightClick);
        NeoForge.EVENT_BUS.addListener(Shields::onEquip);
        ShieldTextures.init();
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> {
            synchronized (COMPONENTS) {
                COMPONENTS.clear();
            }
        });
        // the example shields, their in-game test and shots are in src/shieldtest, compiled only with -Pshieldtest and never
        // shipped: Fundamentum is a library and registers no items of its own
        try {
            Class.forName("com.fundamentum.shield.ShieldExamples").getMethod("init", IEventBus.class).invoke(null, modBus);
        } catch (ClassNotFoundException absent) {
            // a normal build
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Shields() {}
}
