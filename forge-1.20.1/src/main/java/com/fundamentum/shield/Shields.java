package com.fundamentum.shield;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.damage.Elements;
import com.fundamentum.thrown.ThrownStats;
import com.fundamentum.thrown.ThrownWeapon;
import com.fundamentum.thrown.ThrownWeapons;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shields as a data-driven weapon type, 1.20.1 backport of the 26.x one. The same JSON ({@link ShieldStats}); where 26.x
 * writes vanilla's {@code minecraft:blocks_attacks} component, this version decides the block itself (a mixin on
 * {@code LivingEntity.isDamageSourceBlocked} and {@code isBlocking}, then {@link ShieldBlockEvent} for the amount),
 * so the delay, the reductions per type and angle, the bypass tag, the item damage and the disable cooldown work alike.
 * <ul>
 * <li><b>Make one:</b> register a {@link ShieldTypeItem}; it is drawn as vanilla's shield with
 * {@code <ns>:textures/entity/shield/<name>.png} and {@code <name>_nopattern.png} (named after the item), and its item
 * model needs the {@code minecraft:blocking} override like vanilla's {@code item/shield.json}. Any other item becomes one
 * by a data entry, but keeps its own blocking rules for whether it can raise.</li>
 * <li><b>Stats:</b> {@link #register} in code, laid over by {@code data/<ns>/fundamentum/shields/<name>.json} keyed by
 * {@code "item"}.</li>
 * <li><b>Throw:</b> {@code thrown} makes sneak-use throw the shield as a returning thrown weapon ({@link ThrownWeapons}).</li>
 * <li><b>Not on this version:</b> {@code block_sound} and
 * {@code disabled_sound} (vanilla's sounds play), {@code can_sprint} (vanilla stops sprinting while an item is used).</li>
 * <li><b>Better Combat:</b> nothing here is on left click, never swings with it, never uses its attack cooldown.</li>
 * </ul>
 */
public final class Shields {
    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum/Shields");

    /** Item tag of every Fundamentum shield. */
    public static final TagKey<Item> SHIELDS = TagKey.create(Registries.ITEM, new ResourceLocation("fundamentum", "shields"));

    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, "fundamentum");
    /** The banner-on-a-Fundamentum-shield crafting recipe ({@link ShieldTypeDecorationRecipe}). */
    public static final RegistryObject<RecipeSerializer<ShieldTypeDecorationRecipe>> DECORATION =
        SERIALIZERS.register("shield_decoration", () -> new SimpleCraftingRecipeSerializer<>(ShieldTypeDecorationRecipe::new));

    /** Per-item stats in data, keyed by item id (see {@link ShieldStats#withJson} for the fields). */
    public static final DataTable<JsonObject> DATA = DataTables
        .registerJson(new ResourceLocation("fundamentum", "shields"), "fundamentum/shields")
        .keyField("item")
        .synced();

    private static final Map<ResourceLocation, ShieldStats> REGISTERED = new ConcurrentHashMap<>();
    private record Resolved(JsonObject data, ShieldStats code, ShieldStats stats) {}
    private static final Map<ResourceLocation, Resolved> RESOLVED = new ConcurrentHashMap<>();

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
    private static final Map<Player, ShieldStats> RESCALE = new HashMap<>();

    /** Makes the item with this id a shield with these code defaults (data still lays over them). */
    public static void register(ResourceLocation item, ShieldStats stats) {
        REGISTERED.put(item, stats);
        RESOLVED.remove(item);
    }

    /** Whether the stack is a Fundamentum shield: tagged, a {@link ShieldTypeItem}, or with stats in code or data. */
    public static boolean is(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return stack.getItem() instanceof ShieldTypeItem || stack.is(SHIELDS) || REGISTERED.containsKey(id) || DATA.get(id) != null;
    }

    /** The item's stats: code defaults ({@link ShieldStats#DEFAULT} for our items, {@link ShieldStats#VANILLA} else) with data laid over. */
    public static ShieldStats base(ResourceLocation item) {
        ShieldStats code = REGISTERED.get(item);
        if (code == null) {
            Item it = BuiltInRegistries.ITEM.get(item);
            code = it instanceof ShieldTypeItem || it.builtInRegistryHolder().is(SHIELDS) ? ShieldStats.DEFAULT : ShieldStats.VANILLA;
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

    // ---- blocking (what 26.x's blocks_attacks does) ----------------------------------------------------------------

    /** The shield {@code user} is blocking with right now (raised past its delay), or null. */
    public static @Nullable ItemStack blockingStack(LivingEntity user) {
        if (!user.isUsingItem()) {
            return null;
        }
        ItemStack stack = user.getUseItem();
        ShieldStats stats = stats(stack);
        return stats != null && user.getTicksUsingItem() >= stats.blockDelayTicks() ? stack : null;
    }

    /** For the mixin on {@code isBlocking}: our answer while a Fundamentum shield is in use, else null (vanilla's). */
    public static @Nullable Boolean isBlocking(LivingEntity user) {
        if (!user.isUsingItem() || stats(user.getUseItem()) == null) {
            return null;
        }
        return blockingStack(user) != null;
    }

    /** For the mixin on {@code isDamageSourceBlocked}: whether our shield blocks any of this hit, or null (vanilla's). */
    public static @Nullable Boolean blocks(LivingEntity user, DamageSource source) {
        if (!user.isUsingItem()) {
            return null;
        }
        ShieldStats stats = stats(user.getUseItem());
        if (stats == null) {
            return null;
        }
        if (blockingStack(user) == null || source.getDirectEntity() instanceof AbstractArrow arrow && arrow.getPierceLevel() > 0
            || stats.bypassed(matcher(source))) {
            return false;
        }
        return stats.blockedDamage(1000, angle(user, source), matcher(source)) > 0;
    }

    private static void onBlock(ShieldBlockEvent event) {
        LivingEntity user = event.getEntity();
        if (event.isCanceled() || !(user.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack shield = blockingStack(user);
        ShieldStats stats = shield == null ? null : stats(shield);
        if (stats == null) {
            return;
        }
        DamageSource source = event.getDamageSource();
        float dealt = event.getOriginalBlockedDamage();
        boolean parried = parryTiming(user, stats);
        double blocked = parried ? dealt : stats.blockedDamage(dealt, angle(user, source), matcher(source));
        if (blocked <= 0) {
            event.setCanceled(true);
            return;
        }
        event.setBlockedDamage((float) blocked);
        event.setShieldTakesDamage(false); // the item damage is ours, below
        int wear = parried ? 0 : stats.itemDamage().apply(blocked);
        if (wear > 0 && user instanceof Player player && !player.getAbilities().instabuild) {
            InteractionHand hand = user.getUsedItemHand();
            shield.hurtAndBreak(wear, user, e -> e.broadcastBreakEvent(hand));
            if (shield.isEmpty()) {
                user.stopUsingItem();
            }
        }
        Entity direct = source.getDirectEntity();
        if (!parried) {
            if (user instanceof Player player && direct instanceof LivingEntity attacker && attacker.canDisableShield()
                && stats.disableTicks(5) != 100) {
                RESCALE.put(player, stats);
            }
            return;
        }
        ShieldStats.Parry parry = stats.parry();
        state(user).armed = false; // one parry per raise
        level.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ANVIL_LAND, user.getSoundSource(), 0.5F,
            1.6F + level.getRandom().nextFloat() * 0.2F);
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
        State state = state(user);
        long now = level.getGameTime();
        state.armed = stats.parry() == null || stats.parry().armed(state.previousRaise, now);
        state.previousRaise = now;
    }

    private static void afterTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
            RESCALE.remove(player);
            ItemStack shield = player.getUseItem().isEmpty() ? player.getOffhandItem() : player.getUseItem();
            if (!shield.isEmpty()) {
                player.getCooldowns().removeCooldown(shield.getItem());
            }
        }
        UNDISABLE.clear();
        RESCALE.forEach((player, stats) -> {
            // vanilla's disable is 100 ticks; this shield's disable_cooldown_scale changes it
            for (ItemStack held : List.of(player.getMainHandItem(), player.getOffhandItem())) {
                if (stats(held) == stats && player.getCooldowns().isOnCooldown(held.getItem())) {
                    player.getCooldowns().removeCooldown(held.getItem());
                    int ticks = stats.disableTicks(5);
                    if (ticks > 0) {
                        player.getCooldowns().addCooldown(held.getItem(), ticks);
                    }
                }
            }
        });
        RESCALE.clear();
    }

    /** Knocks {@code target} back from {@code from} and slows and weakens it for {@code ticks}. */
    public static void stagger(LivingEntity target, LivingEntity from, double knockback, int ticks) {
        if (knockback > 0) {
            target.knockback(knockback, from.getX() - target.getX(), from.getZ() - target.getZ());
            target.hurtMarked = true;
        }
        if (ticks > 0) {
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 2), from);
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0), from);
        }
        if (target instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        if (target instanceof Player player && !ShieldCompat.betterCombat()) {
            player.resetAttackStrengthTicker();
        }
    }

    // ---- element resistance ---------------------------------------------------------------------------------------

    private static void onIncoming(LivingHurtEvent event) {
        LivingEntity user = event.getEntity();
        ItemStack shield = blockingStack(user);
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

    /** The angle (radians) between where {@code user} looks and where the hit came from, as 26.x measures it. */
    public static double angle(LivingEntity user, DamageSource source) {
        Vec3 from = source.getSourcePosition();
        if (from == null) {
            return Math.PI;
        }
        Vec3 view = Vec3.directionFromRotation(0.0F, user.getYHeadRot());
        Vec3 to = from.subtract(user.position());
        to = new Vec3(to.x, 0.0, to.z).normalize();
        return Math.acos(Math.max(-1, Math.min(1, to.dot(view))));
    }

    /** Matches a damage source's type against an id or {@code #tag} from data. */
    public static ShieldStats.TypeMatcher matcher(DamageSource source) {
        return s -> s.startsWith("#")
            ? source.is(TagKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(s.substring(1))))
            : source.typeHolder().is(new ResourceLocation(s));
    }

    // ---- bash ---------------------------------------------------------------------------------------------------

    /** Bashes with the shield in {@code hand} (see the 26.x version). Server side; never touches the attack cooldown. */
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
                target.hurt(source, (float) bash.damage());
            }
            stagger(target, user, bash.knockback(), bash.staggerTicks());
            hits++;
        }
        level.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0F,
            0.6F + level.getRandom().nextFloat() * 0.1F);
        if (hits > 0 && bash.durability() > 0) {
            stack.hurtAndBreak(bash.durability(), user, e -> e.broadcastBreakEvent(hand));
        }
        if (!ShieldCompat.betterCombat()) {
            user.swing(hand, true);
        }
        return true;
    }

    private static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        Player player = event.player;
        if (event.phase != TickEvent.Phase.END || !(player.level() instanceof ServerLevel)) {
            return;
        }
        State state = state(player);
        boolean sneaking = player.isShiftKeyDown();
        if (sneaking && !state.wasSneaking && blockingStack(player) != null) {
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
        if (stats == null || player.getCooldowns().isOnCooldown(stack.getItem())) {
            return;
        }
        if (stats.throwable()) {
            event.setCanceled(true);
            event.setCancellationResult(throwShield(player.level(), player, event.getHand(), stats));
            return;
        }
        if (stats.bash() != null && ShieldStats.Bash.USE.equals(stats.bash().trigger())) {
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
        return base.modified(thrower.getAttributeValue(ThrownWeapons.SPEED.get()) * thrower.getAttributeValue(StatAttributes.PROJECTILE_SPEED.get()),
            (int) Math.round(thrower.getAttributeValue(ThrownWeapons.PIERCE.get())), thrower.getAttributeValue(ThrownWeapons.RANGE.get()),
            thrower.getAttributeValue(ThrownWeapons.RETURN_SPEED.get()), (int) Math.round(thrower.getAttributeValue(ThrownWeapons.BOUNCES.get())));
    }

    /** Throws the shield in {@code hand} as a returning thrown weapon (one durability; it comes back to its slot). */
    public static InteractionResult throwShield(Level level, Player player, InteractionHand hand, ShieldStats stats) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel server) {
            ThrownStats thrown = thrownStats(stats, player);
            stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
            if (stack.isEmpty()) {
                return InteractionResult.SUCCESS;
            }
            ItemStack copy = stack.copyWithCount(1);
            ThrownWeapon entity = new ThrownWeapon(ThrownWeapons.THROWN_WEAPON.get(), level);
            entity.launch(player, copy, thrown, ThrownWeapons.slotOf(player, hand));
            server.addFreshEntity(entity);
            entity.onThrown(server);
            if (!player.getAbilities().instabuild) {
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

    public static void init(IEventBus modBus) {
        MinecraftForge.EVENT_BUS.addListener(Shields::onUseStart);
        MinecraftForge.EVENT_BUS.addListener(Shields::onBlock);
        ShieldBlockHook.init();
        MinecraftForge.EVENT_BUS.addListener(Shields::onIncoming);
        MinecraftForge.EVENT_BUS.addListener(Shields::afterTick);
        MinecraftForge.EVENT_BUS.addListener(Shields::onPlayerTick);
        MinecraftForge.EVENT_BUS.addListener(Shields::onRightClick);
        ShieldTextures.init();
        SERIALIZERS.register(modBus);
        // The example shields and the shield test: ShieldExamples is in src/shieldtest, built only with -Pshieldtest, never
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
