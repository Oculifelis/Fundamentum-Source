package com.fundamentum.weight;

import com.fundamentum.port.FId;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.living.LivingEvent;

/**
 * The parts of the weight system that differ between game versions. This version has no jump strength attribute, so
 * the overload jump penalty scales the jump's upward motion instead; the server sends each player their own scale.
 */
final class WeightPort {
    private WeightPort() { }

    private static final UUID MOD = UUID.fromString("5b0c6f0e-3c1a-4f7e-9a51-7d2f3e8a9c41");
    private static final FId JUMP_MSG = FId.of("fundamentum", "weight_jump");
    /** Server side: each overloaded player's jump scale. */
    private static final Map<UUID, Float> JUMP = new ConcurrentHashMap<>();
    /** Client side: this player's jump scale. */
    private static volatile float clientJump = 1;

    record Jump(float scale) {
        static final FStreamCodec<Jump> CODEC = FStreamCodec.of((buf, j) -> buf.writeFloat(j.scale), buf -> new Jump(buf.readFloat()));
    }

    /** Armour points and toughness the item gives when worn. */
    static double[] armor(ItemStack stack) {
        double[] r = new double[2];
        if (stack.getItem() instanceof ArmorItem a) {
            r[0] = a.getDefense();
            r[1] = a.getToughness();
        }
        return r;
    }

    /** Every stack stored inside this one (shulker box, bundle or backpack), one level deep. */
    static void contents(ItemStack stack, Consumer<ItemStack> out) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            ListTag items = null;
            CompoundTag be = tag.contains("BlockEntityTag", Tag.TAG_COMPOUND) ? tag.getCompound("BlockEntityTag") : null;
            if (be != null && be.contains("Items", Tag.TAG_LIST)) items = be.getList("Items", Tag.TAG_COMPOUND);
            else if (stack.getItem() instanceof net.minecraft.world.item.BundleItem && tag.contains("Items", Tag.TAG_LIST)) items = tag.getList("Items", Tag.TAG_COMPOUND);
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    ItemStack s = ItemStack.of(items.getCompound(i));
                    if (!s.isEmpty()) out.accept(s);
                }
                return;
            }
        }
        try {
            stack.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(h -> {
                for (int i = 0; i < h.getSlots(); i++) {
                    ItemStack s = h.getStackInSlot(i);
                    if (!s.isEmpty()) out.accept(s);
                }
            });
        } catch (RuntimeException ignored) {
            // a broken item handler counts as empty
        }
    }

    /** Sets the overload slowdown on walking speed and jump height, as fractions (0 or below). */
    static void penalty(ServerPlayer p, double speed, double jump) {
        AttributeInstance a = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a != null) {
            a.removeModifier(MOD);
            if (speed != 0) a.addTransientModifier(new AttributeModifier(MOD, "Fundamentum weight", speed, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
        float scale = (float) Math.max(0, 1 + jump);
        Float old = scale >= 1 ? JUMP.remove(p.getUUID()) : JUMP.put(p.getUUID(), scale);
        if ((old == null ? 1 : old) != scale) FNet.toPlayer(p, new Jump(scale));
    }

    /** A shulker box holding these stacks; the self-test uses it. */
    static ItemStack testBox(java.util.List<ItemStack> inside) {
        ItemStack box = new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        ListTag items = new ListTag();
        for (int i = 0; i < inside.size(); i++) {
            CompoundTag t = inside.get(i).save(new CompoundTag());
            t.putByte("Slot", (byte) i);
            items.add(t);
        }
        box.getOrCreateTagElement("BlockEntityTag").put("Items", items);
        return box;
    }

    /** The player's jump scale now: below 1 while overloaded. */
    static double jumpScale(ServerPlayer p) {
        return JUMP.getOrDefault(p.getUUID(), 1f);
    }

    static void init() {
        FNet.register(JUMP_MSG, Jump.class, Jump.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> clientJump = msg.scale());
        MinecraftForge.EVENT_BUS.addListener(WeightPort::jumped);
    }

    static void serverReset() {
        JUMP.clear();
    }

    static void clientReset() {
        clientJump = 1;
    }

    private static void jumped(LivingEvent.LivingJumpEvent e) {
        LivingEntity en = e.getEntity();
        if (!(en instanceof Player)) return;
        float scale = en instanceof ServerPlayer sp ? JUMP.getOrDefault(sp.getUUID(), 1f) : clientJump;
        if (scale >= 1) return;
        Vec3 v = en.getDeltaMovement();
        en.setDeltaMovement(v.x, v.y * scale, v.z);
    }
}
