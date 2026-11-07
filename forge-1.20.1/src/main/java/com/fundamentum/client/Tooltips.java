package com.fundamentum.client;

import com.fundamentum.element.ElementAffinities;
import com.fundamentum.hook.Hooks;
import com.fundamentum.whip.WhipDefinition;
import com.fundamentum.whip.WhipTuning;
import com.fundamentum.whip.Whips;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

/**
 * Item tooltips (1.22.1), for every mod's items:
 * <ul>
 * <li>Any item: its description, from the lang keys {@code <item key>.desc}, {@code .desc2} and {@code .desc3} when they
 * exist (gray, under the name). A mod gives a weapon, tool or trinket a tooltip with lang lines alone.</li>
 * <li>Whips: damage, reach, cooldown, the tip crack's crit, targets, strands, homing, a slam, and the summon tag.</li>
 * <li>Hooks: the hook slot and its key, range, shoot and pull speed, and how many can be out.</li>
 * <li>Items with the {@code fundamentum:element_affinity} component: what they resist, are weak to or immune to.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class Tooltips {
    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        List<Component> lines = event.getToolTip();
        if (lines.isEmpty()) {
            return;
        }
        int at = 1;
        String key = stack.getItem().getDescriptionId();
        for (String suffix : new String[] {".desc", ".desc2", ".desc3"}) {
            if (I18n.exists(key + suffix)) {
                lines.add(at++, Component.translatable(key + suffix).withStyle(ChatFormatting.GRAY));
            }
        }
        WhipDefinition whip = Whips.definition(stack);
        if (whip != null) {
            whip(whip, lines, at);
        }
        Hooks.Def hook = Hooks.def(stack);
        if (hook != null) {
            hook(hook, lines, at);
        }
        ElementAffinities.Affinity affinity = ElementAffinities.item(stack);
        if (affinity != null) {
            affinity(affinity, lines);
        }
    }

    /** An item's element_affinity NBT tag (1.25.0; a data component on 1.21): resistances in blue, weaknesses in red, immunities. */
    private static void affinity(ElementAffinities.Affinity affinity, List<Component> lines) {
        affinity.resist().forEach((element, value) -> lines.add(Component.translatable(
            value >= 0 ? "tooltip.fundamentum.element.resist" : "tooltip.fundamentum.element.weak", element.name(), num(Math.abs(value) * 100))
            .withStyle(value >= 0 ? ChatFormatting.BLUE : ChatFormatting.RED)));
        affinity.immune().forEach(element -> lines.add(Component.translatable("tooltip.fundamentum.element.immune", element.name())
            .withStyle(ChatFormatting.BLUE)));
    }

    private static void whip(WhipDefinition whip, List<Component> lines, int at) {
        WhipTuning t = whip.tuning;
        List<Component> add = new java.util.ArrayList<>();
        add.add(Component.empty());
        add.add(Component.translatable("tooltip.fundamentum.whip.swung").withStyle(ChatFormatting.GRAY));
        add.add(stat("tooltip.fundamentum.whip.damage", num(t.damage())));
        add.add(stat("tooltip.fundamentum.whip.reach", num(t.length())));
        add.add(stat("tooltip.fundamentum.whip.cooldown", num(t.cooldownTicks() / 20.0)));
        add.add(stat("tooltip.fundamentum.whip.crack", num(t.tipCritMultiplier())));
        if (t.maxTargets() > 1) {
            add.add(stat("tooltip.fundamentum.whip.targets", Integer.toString(t.maxTargets())));
        }
        if (t.strands() > 1) {
            add.add(stat(whip.strandTargets ? "tooltip.fundamentum.whip.strands_seek" : "tooltip.fundamentum.whip.strands",
                Integer.toString(t.strands())));
        }
        if (whip.homingDegrees > 0) {
            add.add(stat("tooltip.fundamentum.whip.homing", num(whip.homingDegrees)));
        }
        if (whip.slam != null) {
            add.add(stat("tooltip.fundamentum.whip.slam"));
        }
        WhipDefinition.SummonTag tag = whip.summonTag;
        if (tag != null) {
            add.add(Component.empty());
            add.add(Component.translatable("tooltip.fundamentum.whip.tag", num(tag.ticks() / 20.0)).withStyle(ChatFormatting.GRAY));
            if (tag.flat() != 0) {
                add.add(stat("tooltip.fundamentum.whip.tag_flat", num(tag.flat())));
            }
            if (tag.multiplier() != 1) {
                add.add(stat("tooltip.fundamentum.whip.tag_multiplier", num(tag.multiplier())));
            }
            if (tag.critChance() > 0) {
                add.add(stat("tooltip.fundamentum.whip.tag_crit", num(tag.critChance() * 100)));
            }
        }
        lines.addAll(at, add);
    }

    private static void hook(Hooks.Def hook, List<Component> lines, int at) {
        List<Component> add = new java.util.ArrayList<>();
        add.add(Component.empty());
        add.add(Component.translatable("tooltip.fundamentum.hook.slot", Component.keybind("key.fundamentum.hook"))
            .withStyle(ChatFormatting.GRAY));
        add.add(stat("tooltip.fundamentum.hook.range", num(hook.range())));
        add.add(stat("tooltip.fundamentum.hook.shoot", num(hook.shootSpeed() * 20)));
        add.add(stat("tooltip.fundamentum.hook.pull", num(hook.pullSpeed() * 20)));
        if (hook.count() > 1) {
            add.add(stat("tooltip.fundamentum.hook.count", Integer.toString(hook.count())));
        }
        if (hook.still()) {
            add.add(stat("tooltip.fundamentum.hook.still"));
        }
        if (hook.airSeconds() > 0) {
            add.add(stat("tooltip.fundamentum.hook.air", num(hook.airSeconds())));
        }
        lines.addAll(at, add);
    }

    /** A stat line in vanilla's attribute style: indented, dark green. */
    private static Component stat(String key, Object... args) {
        return Component.literal(" ").append(Component.translatable(key, args)).withStyle(ChatFormatting.DARK_GREEN);
    }

    /** Up to two decimals, trailing zeros dropped. */
    private static String num(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private Tooltips() {}
}
