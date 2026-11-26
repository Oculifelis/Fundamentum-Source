package com.fundamentum.weight;

import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** The parts of the weight system that differ between game versions. */
final class WeightPort {
    private WeightPort() { }

    private static final Identifier MOD = Identifier.fromNamespaceAndPath("fundamentum", "weight");

    /** Armour points and toughness the item gives when worn, from its attribute modifiers. */
    static double[] armor(ItemStack stack) {
        double[] r = new double[2];
        ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (e.modifier().operation() != AttributeModifier.Operation.ADD_VALUE) continue;
            if (e.attribute().is(Attributes.ARMOR)) r[0] += e.modifier().amount();
            else if (e.attribute().is(Attributes.ARMOR_TOUGHNESS)) r[1] += e.modifier().amount();
        }
        return r;
    }

    /** Every stack stored inside this one (shulker box, bundle or backpack), one level deep. */
    static void contents(ItemStack stack, Consumer<ItemStack> out) {
        ItemContainerContents c = stack.get(DataComponents.CONTAINER);
        if (c != null) {
            c.nonEmptyItemCopyStream().forEach(out);
            return;
        }
        BundleContents b = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (b != null) {
            b.itemCopyStream().forEach(out);
            return;
        }
        try {
            ResourceHandler<ItemResource> h = Capabilities.Item.ITEM.getCapability(stack, ItemAccess.forStack(stack));
            if (h == null) return;
            for (int i = 0; i < h.size(); i++) {
                ItemResource r = h.getResource(i);
                int n = h.getAmountAsInt(i);
                if (r != null && !r.isEmpty() && n > 0) out.accept(r.toStack(n));
            }
        } catch (RuntimeException ignored) {
            // a broken item handler counts as empty
        }
    }

    /** Sets the overload slowdown on walking speed and jump height, as fractions (0 or below). */
    static void penalty(ServerPlayer p, double speed, double jump) {
        set(p.getAttribute(Attributes.MOVEMENT_SPEED), speed);
        set(p.getAttribute(Attributes.JUMP_STRENGTH), jump);
    }

    private static void set(AttributeInstance a, double v) {
        if (a == null) return;
        a.removeModifier(MOD);
        if (v != 0) a.addTransientModifier(new AttributeModifier(MOD, v, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** A shulker box holding these stacks; the self-test uses it. */
    static ItemStack testBox(java.util.List<ItemStack> inside) {
        ItemStack box = new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(inside));
        return box;
    }

    /** Jump strength now against its base: below 1 while overloaded. */
    static double jumpScale(ServerPlayer p) {
        AttributeInstance a = p.getAttribute(Attributes.JUMP_STRENGTH);
        return a == null || a.getBaseValue() == 0 ? 1 : a.getValue() / a.getBaseValue();
    }

    /** No jump event needed: jump height is an attribute on this version. */
    static void init() { }
}
