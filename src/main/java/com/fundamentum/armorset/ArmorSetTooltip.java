package com.fundamentum.armorset;

import com.fundamentum.attribute.AttributeBonus;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

/** Client only: lists the set an item belongs to, how many pieces are worn and what each tier gives. */
public final class ArmorSetTooltip {
    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(ArmorSetTooltip::onTooltip);
    }

    private static void onTooltip(ItemTooltipEvent event) {
        List<ArmorSetDefinition> sets = ArmorSets.setsOf(event.getItemStack());
        if (sets.isEmpty()) {
            return;
        }
        Player player = event.getEntity();
        List<Component> lines = event.getToolTip();
        for (ArmorSetDefinition set : sets) {
            int worn = player == null ? 0 : ArmorSets.pieces(player, set);
            lines.add(Component.empty().append(set.name().copy().withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" (" + worn + "/" + set.maxPieces() + ")").withStyle(ChatFormatting.GRAY)));
            for (ArmorSetDefinition.Tier tier : set.tiers()) {
                boolean active = worn >= tier.pieces();
                ChatFormatting colour = active ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY;
                MutableComponent head = Component.literal("  " + tier.pieces() + ": ").withStyle(colour);
                boolean first = true;
                for (AttributeBonus b : tier.attributes()) {
                    head.append(Component.literal(first ? "" : ", ").withStyle(colour)).append(b.describe(1).copy().withStyle(colour));
                    first = false;
                }
                for (ArmorSetDefinition.EffectGrant e : tier.effects()) {
                    head.append(Component.literal((first ? "" : ", ") + prettify(e.effect().getPath())
                        + (e.amplifier() > 0 ? " " + (e.amplifier() + 1) : "")).withStyle(colour));
                    first = false;
                }
                for (SetReward r : tier.rewards()) {
                    Component line = r.describe();
                    if (line != null) {
                        head.append(Component.literal(first ? "" : ", ").withStyle(colour)).append(line.copy().withStyle(colour));
                        first = false;
                    }
                }
                for (String flag : tier.flags()) {
                    head.append(Component.literal(first ? "" : ", ").withStyle(colour))
                        .append(Component.translatableWithFallback("armor_set.flag." + flag, prettify(flag)).withStyle(colour));
                    first = false;
                }
                lines.add(head);
            }
        }
    }

    private static String prettify(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
        return last.isEmpty() ? last : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    private ArmorSetTooltip() {}
}
