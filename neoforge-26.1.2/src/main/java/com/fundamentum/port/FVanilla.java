package com.fundamentum.port;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.stats.Stats;

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
        return target.level() instanceof ServerLevel level && target.hurtServer(level, source, amount);
    }

    public static DamageSource damage(Level level, ResourceKey<DamageType> type, Entity attacker) {
        return level.damageSources().source(type, attacker);
    }

    public static DamageSource damage(Level level, ResourceKey<DamageType> type, Entity direct, Entity attacker) {
        return level.damageSources().source(type, direct, attacker);
    }

    public static DamageSource damage(Level level, FId type, Entity attacker) {
        return damage(level, ResourceKey.create(Registries.DAMAGE_TYPE, type.vanilla()), attacker);
    }

    // ---- attributes

    public static AttributeInstance attribute(LivingEntity entity, Holder<Attribute> attribute) {
        return entity.getAttribute(attribute);
    }

    public static double attributeValue(LivingEntity entity, Holder<Attribute> attribute) {
        return entity.getAttributeValue(attribute);
    }

    /** The attribute instance by id, or null when the entity has no such attribute. */
    public static AttributeInstance attribute(LivingEntity entity, FId id) {
        return BuiltInRegistries.ATTRIBUTE.get(id.vanilla()).map(entity::getAttribute).orElse(null);
    }

    public static double attributeValue(LivingEntity entity, FId id) {
        AttributeInstance a = attribute(entity, id);
        return a == null ? 0 : a.getValue();
    }

    // ---- enchantments

    public static int enchantLevel(Level level, ItemStack stack, ResourceKey<Enchantment> enchantment) {
        return EnchantmentHelper.getItemEnchantmentLevel(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchantment), stack);
    }

    public static int enchantLevel(Level level, ItemStack stack, FId enchantment) {
        return enchantLevel(level, stack, ResourceKey.create(Registries.ENCHANTMENT, enchantment.vanilla()));
    }

    public static void enchant(Level level, ItemStack stack, ResourceKey<Enchantment> enchantment, int lvl) {
        stack.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchantment), lvl);
    }

    public static void enchant(Level level, ItemStack stack, FId enchantment, int lvl) {
        enchant(level, stack, ResourceKey.create(Registries.ENCHANTMENT, enchantment.vanilla()), lvl);
    }

    // ---- custom data (the free-form tag on a stack)

    /** A copy of the stack's custom data tag (empty when it has none). */
    public static CompoundTag customData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static void setCustomData(ItemStack stack, CompoundTag tag) {
        if (tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static void updateCustomData(ItemStack stack, Consumer<CompoundTag> change) {
        CompoundTag tag = customData(stack);
        change.accept(tag);
        setCustomData(stack, tag);
    }

    // ---- effects

    public static boolean addEffect(LivingEntity entity, Holder<MobEffect> effect, int duration, int amplifier) {
        return entity.addEffect(new MobEffectInstance(effect, duration, amplifier));
    }

    public static boolean addEffect(LivingEntity entity, FReg.Ref<? extends MobEffect> effect, int duration, int amplifier) {
        return addEffect(entity, BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect.get()), duration, amplifier);
    }

    public static boolean hasEffect(LivingEntity entity, Holder<MobEffect> effect) {
        return entity.hasEffect(effect);
    }

    public static boolean hasEffect(LivingEntity entity, FReg.Ref<? extends MobEffect> effect) {
        return entity.hasEffect(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect.get()));
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
        return BuiltInRegistries.ITEM.containsKey(id.vanilla()) ? BuiltInRegistries.ITEM.getValue(id.vanilla()) : null;
    }

    public static Block block(FId id) {
        return BuiltInRegistries.BLOCK.containsKey(id.vanilla()) ? BuiltInRegistries.BLOCK.getValue(id.vanilla()) : null;
    }

    public static EntityType<?> entityType(FId id) {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(id.vanilla()) ? BuiltInRegistries.ENTITY_TYPE.getValue(id.vanilla()) : null;
    }

    public static SoundEvent sound(FId id) {
        return BuiltInRegistries.SOUND_EVENT.containsKey(id.vanilla()) ? BuiltInRegistries.SOUND_EVENT.getValue(id.vanilla()) : null;
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
        return type.create(level, EntitySpawnReason.COMMAND);
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
        return new ServerPlayer(level.getServer(), level, profile, net.minecraft.server.level.ClientInformation.createDefault());
    }

    /** The entity as it is saved in the world, with its id and attached data. */
    public static CompoundTag saveEntity(Entity entity) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.save(out);
        return out.buildResult();
    }

    /** A new entity read from a saved tag (not yet added), or null. */
    public static Entity loadEntity(CompoundTag tag, ServerLevel level) {
        return EntityType.create(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), level, EntitySpawnReason.LOAD).orElse(null);
    }

    public static CompoundTag saveBlockEntity(BlockEntity be, Level level) {
        return be.saveWithFullMetadata(level.registryAccess());
    }

    public static BlockEntity loadBlockEntity(BlockPos pos, BlockState state, CompoundTag tag, Level level) {
        return BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
    }

    public static Tag saveStack(ItemStack stack, Level level) {
        return ItemStack.OPTIONAL_CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
    }

    public static ItemStack loadStack(Tag tag, Level level) {
        return ItemStack.OPTIONAL_CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag).result().orElse(ItemStack.EMPTY);
    }

    /** Saves every level, its chunks and saved data, and waits for the writes. */
    public static void saveWorld(MinecraftServer server) {
        server.saveEverything(true, true, true);
    }

    /** True for an operator (permission level 2, the level of /gamemode) or the owner of a singleplayer world. */
    public static boolean isOp(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
            || player.level().getServer().isSingleplayerOwner(player.nameAndId());
    }

    /** The game's config folder ({@code config/}). */
    public static java.nio.file.Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** Ticks this player has spent in the world, from the play-time statistic. */
    public static int playTime(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
    }
}
