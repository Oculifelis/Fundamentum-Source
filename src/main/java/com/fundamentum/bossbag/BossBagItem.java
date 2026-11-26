package com.fundamentum.bossbag;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A boss bag ({@link BossBags}): named for its boss, with a "Taken from" line; used, it opens into its boss's loot.
 * Mods extend it for a bag of their own look and extras ({@link #always}, {@link #colours}, {@link #moreLines}).
 */
public class BossBagItem extends Item {
    /** Dust motes in the puff of an opened bag, split among its colours. */
    public static final int PUFF = 16;

    public BossBagItem(Properties props) {
        super(props);
    }

    /** The bag entry of this bag's boss, or null. */
    public static @Nullable BagDef def(ItemStack stack) {
        EntityType<?> t = BossBags.boss(stack);
        return t == null ? null : BossBags.def(t);
    }

    /**
     * The entry's {@code name} key (given the boss's name); else {@code <this item's key>.<boss path>} when the boss is of
     * this item's mod and that key exists; else "Bag of the boss".
     */
    @Override
    public Component getName(ItemStack stack) {
        EntityType<?> t = BossBags.boss(stack);
        if (t == null) return super.getName(stack);
        BagDef def = BossBags.def(t);
        if (def != null && def.name() != null) return Component.translatable(def.name(), t.getDescription());
        ResourceLocation boss = BuiltInRegistries.ENTITY_TYPE.getKey(t);
        ResourceLocation self = BuiltInRegistries.ITEM.getKey(this);
        String own = getDescriptionId() + "." + boss.getPath();
        if (boss.getNamespace().equals(self.getNamespace()) && Language.getInstance().has(own)) return Component.translatable(own);
        return Component.translatable("item.fundamentum.boss_bag.of", t.getDescription());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (BossBags.boss(stack) == null) return InteractionResultHolder.pass(stack);
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) BossBags.use(sl, sp, stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** What every bag of its boss holds besides its roll: the entry's {@code always}. */
    public List<ItemStack> always(ItemStack bag) {
        BagDef def = def(bag);
        List<ItemStack> out = new ArrayList<>();
        if (def == null) return out;
        for (BagDef.Always a : def.always()) {
            Item item = BuiltInRegistries.ITEM.get(a.item());
            if (item != null && a.count() > 0) out.add(new ItemStack(item, a.count()));
        }
        return out;
    }

    /** The puff colours of an opened bag: the entry's {@code puff}. */
    public int[] colours(ItemStack bag) {
        BagDef def = def(bag);
        return def != null ? def.puff() : BagDef.DEFAULT_PUFF;
    }

    /** The sound of an opened bag: the entry's {@code sound}, else the bundle's. */
    public SoundEvent sound(ItemStack bag) {
        BagDef def = def(bag);
        return def != null && def.sound() != null ? SoundEvent.createVariableRangeEvent(def.sound()) : SoundEvents.BUNDLE_DROP_CONTENTS;
    }

    /** The sound at {@code player}, heard by those near, and a short puff of dust where the bag opened. */
    public void opened(ServerLevel level, ServerPlayer player, ItemStack bag) {
        level.playSound(null, player.getX(), player.getY(), player.getZ(), sound(bag), SoundSource.PLAYERS, 1.0F,
            0.95F + level.getRandom().nextFloat() * 0.1F);
        Vec3 at = player.getEyePosition().add(player.getLookAngle().scale(0.6)).subtract(0, 0.45, 0);
        int[] cs = colours(bag);
        for (int c : cs) {
            level.sendParticles(new DustParticleOptions(Vec3.fromRGB24(c & 0xFFFFFF).toVector3f(), 1.0F), at.x, at.y, at.z, Math.max(1, PUFF / Math.max(1, cs.length)), 0.22, 0.18, 0.22, 0.03);
        }
    }

    /** Lines a mod's bag shows above "Right-click to open" (a tier, say). */
    protected void moreLines(ItemStack stack, EntityType<?> boss, List<Component> tooltip) { }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        EntityType<?> t = BossBags.boss(stack);
        if (t == null) return;
        moreLines(stack, t, tooltip);
        tooltip.add(Component.translatable(getDescriptionId() + ".use").withColor(0xD8B45A));
        tooltip.add(Component.translatable(getDescriptionId() + ".taken_from", t.getDescription()).withStyle(ChatFormatting.GRAY));
    }
}
