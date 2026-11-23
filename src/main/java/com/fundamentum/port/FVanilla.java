package com.fundamentum.port;

import com.mojang.authlib.GameProfile;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.stats.Stats;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * One-line stand-ins for vanilla calls that were renamed or retyped between 1.20.1, 1.21.1 and 26.x. Where vanilla's
 * own constants changed type (effects, attributes and sounds became holders, enchantments became data), there is one
 * overload per type, so the same source text ({@code FVanilla.addEffect(e, MobEffects.REGENERATION, 100, 0)}) compiles
 * on every version.
 */
public final class FVanilla {
    private FVanilla() { }

    // ---- damage

    /** Hurts the entity on the server; false on the client or when the hit was refused. */
    public static boolean hurt(Entity target, DamageSource source, float amount) {
        return target.level() instanceof ServerLevel && target.hurt(source, amount);
    }

    public static DamageSource damage(Level level, ResourceKey<DamageType> type, Entity attacker) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), attacker);
    }

    public static DamageSource damage(Level level, ResourceKey<DamageType> type, Entity direct, Entity attacker) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), direct, attacker);
    }

    public static DamageSource damage(Level level, FId type, Entity attacker) {
        return damage(level, ResourceKey.create(Registries.DAMAGE_TYPE, type.vanilla()), attacker);
    }

    // ---- attributes

    public static AttributeInstance attribute(LivingEntity entity, Attribute attribute) {
        return entity.getAttribute(attribute);
    }

    public static AttributeInstance attribute(LivingEntity entity, Holder<Attribute> attribute) {
        return entity.getAttribute(attribute.value());
    }

    public static double attributeValue(LivingEntity entity, Attribute attribute) {
        return entity.getAttributeValue(attribute);
    }

    public static double attributeValue(LivingEntity entity, Holder<Attribute> attribute) {
        return entity.getAttributeValue(attribute.value());
    }

    /** The attribute instance by id, or null when the entity has no such attribute. */
    public static AttributeInstance attribute(LivingEntity entity, FId id) {
        Attribute a = BuiltInRegistries.ATTRIBUTE.containsKey(id.vanilla()) ? BuiltInRegistries.ATTRIBUTE.get(id.vanilla()) : null;
        return a == null ? null : entity.getAttribute(a);
    }

    public static double attributeValue(LivingEntity entity, FId id) {
        AttributeInstance a = attribute(entity, id);
        return a == null ? 0 : a.getValue();
    }

    // ---- enchantments

    public static int enchantLevel(Level level, ItemStack stack, Enchantment enchantment) {
        return EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack);
    }

    public static int enchantLevel(Level level, ItemStack stack, FId enchantment) {
        Enchantment e = BuiltInRegistries.ENCHANTMENT.get(enchantment.vanilla());
        return e == null ? 0 : enchantLevel(level, stack, e);
    }

    public static void enchant(Level level, ItemStack stack, Enchantment enchantment, int lvl) {
        stack.enchant(enchantment, lvl);
    }

    public static void enchant(Level level, ItemStack stack, FId enchantment, int lvl) {
        Enchantment e = BuiltInRegistries.ENCHANTMENT.get(enchantment.vanilla());
        if (e == null) throw new IllegalArgumentException("No enchantment " + enchantment);
        enchant(level, stack, e, lvl);
    }

    // ---- custom data (the free-form tag on a stack)

    /** Stack tag keys that are their own data components from 1.20.5 on, so they are not custom data. */
    private static final Set<String> VANILLA_KEYS = Set.of("Damage", "Enchantments", "StoredEnchantments", "display",
        "RepairCost", "Unbreakable", "HideFlags", "CustomModelData", "AttributeModifiers", "CanDestroy", "CanPlaceOn",
        "BlockEntityTag", "BlockStateTag", "EntityTag", "Trim", "Potion", "CustomPotionEffects", "CustomPotionColor",
        "SkullOwner", "Fireworks", "Explosion", "pages", "title", "author", "generation", "resolved", "Items",
        "ChargedProjectiles", "Charged", "LodestonePos", "LodestoneDimension", "LodestoneTracked", "Decorations",
        "map", "map_scale_direction", "map_to_lock", "BucketVariantTag", "Recipes", "Effects", "instrument",
        "DebugProperty", "Bees", "Base", "Patterns");

    /** A copy of the stack's custom data tag (empty when it has none): its own tags, not vanilla's or stack data. */
    public static CompoundTag customData(ItemStack stack) {
        CompoundTag out = new CompoundTag();
        CompoundTag tag = stack.getTag();
        if (tag == null) return out;
        Set<String> skip = FStackData.keys();
        for (String k : tag.getAllKeys()) {
            if (!VANILLA_KEYS.contains(k) && !skip.contains(k)) out.put(k, tag.get(k).copy());
        }
        return out;
    }

    public static void setCustomData(ItemStack stack, CompoundTag tag) {
        CompoundTag old = customData(stack);
        for (String k : old.getAllKeys()) stack.removeTagKey(k);
        if (!tag.isEmpty()) {
            CompoundTag t = stack.getOrCreateTag();
            for (String k : tag.getAllKeys()) t.put(k, tag.get(k).copy());
        }
    }

    public static void updateCustomData(ItemStack stack, Consumer<CompoundTag> change) {
        CompoundTag tag = customData(stack);
        change.accept(tag);
        setCustomData(stack, tag);
    }

    // ---- effects

    public static boolean addEffect(LivingEntity entity, MobEffect effect, int duration, int amplifier) {
        return entity.addEffect(new MobEffectInstance(effect, duration, amplifier));
    }

    public static boolean addEffect(LivingEntity entity, Holder<MobEffect> effect, int duration, int amplifier) {
        return addEffect(entity, effect.value(), duration, amplifier);
    }

    public static boolean addEffect(LivingEntity entity, FReg.Ref<? extends MobEffect> effect, int duration, int amplifier) {
        return addEffect(entity, (MobEffect) effect.get(), duration, amplifier);
    }

    public static boolean hasEffect(LivingEntity entity, MobEffect effect) {
        return entity.hasEffect(effect);
    }

    public static boolean hasEffect(LivingEntity entity, Holder<MobEffect> effect) {
        return entity.hasEffect(effect.value());
    }

    public static boolean hasEffect(LivingEntity entity, FReg.Ref<? extends MobEffect> effect) {
        return entity.hasEffect(effect.get());
    }

    // ---- sounds and particles

    public static void playSound(Level level, double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch) {
        level.playSound(null, x, y, z, sound, source, volume, pitch);
    }

    public static void playSound(Level level, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) {
        level.playSound(null, x, y, z, sound.value(), source, volume, pitch);
    }

    public static void playSound(Level level, double x, double y, double z, FReg.Ref<SoundEvent> sound, SoundSource source, float volume, float pitch) {
        level.playSound(null, x, y, z, sound.get(), source, volume, pitch);
    }

    /** Sends particles to nearby players; returns how many players got them. */
    public static int particles(ServerLevel level, ParticleOptions particle, double x, double y, double z, int count, double dx, double dy, double dz, double speed) {
        return level.sendParticles(particle, x, y, z, count, dx, dy, dz, speed);
    }

    // ---- registry lookups

    /** The item with this id, or null. */
    public static Item item(FId id) {
        return BuiltInRegistries.ITEM.containsKey(id.vanilla()) ? BuiltInRegistries.ITEM.get(id.vanilla()) : null;
    }

    public static Block block(FId id) {
        return BuiltInRegistries.BLOCK.containsKey(id.vanilla()) ? BuiltInRegistries.BLOCK.get(id.vanilla()) : null;
    }

    public static EntityType<?> entityType(FId id) {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(id.vanilla()) ? BuiltInRegistries.ENTITY_TYPE.get(id.vanilla()) : null;
    }

    public static SoundEvent sound(FId id) {
        return BuiltInRegistries.SOUND_EVENT.containsKey(id.vanilla()) ? BuiltInRegistries.SOUND_EVENT.get(id.vanilla()) : null;
    }

    public static FId idOf(Item item) {
        return FId.from(BuiltInRegistries.ITEM.getKey(item));
    }

    public static FId idOf(Block block) {
        return FId.from(BuiltInRegistries.BLOCK.getKey(block));
    }

    public static FId idOf(EntityType<?> type) {
        return FId.from(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    // ---- entities, block entities, stacks

    /** A new entity of the type in the level (not yet added), or null. */
    public static <E extends Entity> E create(EntityType<E> type, ServerLevel level) {
        return type.create(level);
    }

    /** A new entity of the type with this id (not yet added), or null. Ask for the class the type really makes. */
    @SuppressWarnings("unchecked")
    public static <E extends Entity> E create(FId type, ServerLevel level) {
        EntityType<?> t = entityType(type);
        return t == null ? null : (E) create(t, level);
    }

    /** Ends the short immunity an entity gets after being hit, so the next hit lands. */
    public static void clearHurtCooldown(Entity entity) {
        entity.invulnerableTime = 0;
    }

    public static ServerPlayer fakePlayer(ServerLevel level, String name) {
        return FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
    }

    /**
     * A plain server player with no connection, outside the player list, for tests. Unlike a fake player it earns
     * advancements (the loader keeps those from fake players). Never add it to the world.
     */
    public static ServerPlayer testPlayer(ServerLevel level, String name) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name);
        return new ServerPlayer(level.getServer(), level, profile);
    }

    /** The entity as it is saved in the world, with its id and attached data. */
    public static CompoundTag saveEntity(Entity entity) {
        CompoundTag tag = new CompoundTag();
        entity.save(tag);
        return tag;
    }

    /** A new entity read from a saved tag (not yet added), or null. */
    public static Entity loadEntity(CompoundTag tag, ServerLevel level) {
        return EntityType.create(tag, level).orElse(null);
    }

    public static CompoundTag saveBlockEntity(BlockEntity be, Level level) {
        return be.saveWithFullMetadata();
    }

    public static BlockEntity loadBlockEntity(BlockPos pos, BlockState state, CompoundTag tag, Level level) {
        return BlockEntity.loadStatic(pos, state, tag);
    }

    public static Tag saveStack(ItemStack stack, Level level) {
        return stack.save(new CompoundTag());
    }

    public static ItemStack loadStack(Tag tag, Level level) {
        return tag instanceof CompoundTag c ? ItemStack.of(c) : ItemStack.EMPTY;
    }

    /** Saves every level, its chunks and saved data, and waits for the writes. */
    public static void saveWorld(MinecraftServer server) {
        server.saveEverything(true, true, true);
    }

    /** True for an operator (permission level 2, the level of /gamemode) or the owner of a singleplayer world. */
    public static boolean isOp(ServerPlayer player) {
        return player.hasPermissions(2) || player.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    /** The game's config folder ({@code config/}). */
    public static java.nio.file.Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** Ticks this player has spent in the world, from the play-time statistic. */
    public static int playTime(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
    }

    // ---- statistics and scoreboard

    /**
     * A vanilla statistic of the player: {@code type} is the stat type ({@code minecraft:custom},
     * {@code minecraft:mined}, ...) and {@code value} the thing counted ({@code minecraft:jump}, {@code minecraft:stone}).
     * 0 when either is unknown.
     */
    public static int statValue(ServerPlayer player, FId type, FId value) {
        if (!BuiltInRegistries.STAT_TYPE.containsKey(type.vanilla())) return 0;
        return statValue(player, BuiltInRegistries.STAT_TYPE.get(type.vanilla()), value);
    }

    private static <T> int statValue(ServerPlayer player, net.minecraft.stats.StatType<T> type, FId value) {
        if (!type.getRegistry().containsKey(value.vanilla())) return 0;
        return player.getStats().getValue(type.get(type.getRegistry().get(value.vanilla())));
    }

    /** The holder's score in the objective (holder is a player name or any score holder name), or null when unset. */
    public static Integer score(MinecraftServer server, String holder, String objective) {
        net.minecraft.world.scores.Scoreboard board = server.getScoreboard();
        net.minecraft.world.scores.Objective o = board.getObjective(objective);
        if (o == null) return null;
        if (!board.hasPlayerScore(holder, o)) return null;
        return board.getOrCreatePlayerScore(holder, o).getScore();
    }

    /** Sets the holder's score; false when the objective does not exist. */
    public static boolean setScore(MinecraftServer server, String holder, String objective, int value) {
        net.minecraft.world.scores.Scoreboard board = server.getScoreboard();
        net.minecraft.world.scores.Objective o = board.getObjective(objective);
        if (o == null) return false;
        board.getOrCreatePlayerScore(holder, o).setScore(value);
        return true;
    }

    /** Whether a piece of the structure {@code id} covers {@code pos} (false for an unknown id). */
    public static boolean structurePieceAt(ServerLevel level, BlockPos pos, FId id) {
        ResourceKey<net.minecraft.world.level.levelgen.structure.Structure> key = ResourceKey.create(Registries.STRUCTURE, id.vanilla());
        return level.structureManager().getStructureWithPieceAt(pos, key).isValid();
    }
}
