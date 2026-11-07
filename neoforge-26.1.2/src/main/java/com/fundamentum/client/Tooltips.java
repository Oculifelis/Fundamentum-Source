package com.fundamentum.client;

import com.fundamentum.element.ElementAffinities;
import com.fundamentum.hook.Hooks;
import com.fundamentum.summon.Summons;
import com.fundamentum.whip.WhipDefinition;
import com.fundamentum.whip.WhipTuning;
import com.fundamentum.whip.Whips;
import com.fundamentum.whip.WhipsConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Item tooltips (1.22.1), for every mod's items:
 * <ul>
 * <li>Any item: its description, from the lang keys {@code <item key>.desc}, {@code .desc2} and {@code .desc3} when they
 * exist (gray, under the name). A mod gives a weapon, tool or trinket a tooltip with lang lines alone.</li>
 * <li>Whips: damage, reach, cooldown, the tip crack's crit, targets, strands, homing, a slam, and the summon tag.</li>
 * <li>Hooks: the hook slot and its key, range, shoot and pull speed, and how many can be out.</li>
 * <li>Items with the {@code fundamentum:element_affinity} component: what they resist, are weak to or immune to.</li>
 * <li>Lines other mods add through {@link #details(DetailSource)}.</li>
 * </ul>
 * Since 1.25.3 these lines fold behind one grey "Hold [Shift] for details" line until Shift is held ({@link TooltipRule}
 * says which items fold; {@code fundamentum-client.toml [tooltips] shiftForDetails = false} shows them always). Recipe
 * viewers ({@code TooltipFlag.shouldDisplayAllInformation()}) and the creative search tree (no player) always get the
 * full text, so search still finds it. Vanilla's enchantment, durability and attribute lines are never touched.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class Tooltips {
    /** Items whose Fundamentum lines always show, whatever {@link TooltipRule} says. */
    public static final TagKey<Item> ALWAYS = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("fundamentum", "tooltip_always"));
    /** Items whose Fundamentum lines always fold behind Shift. */
    public static final TagKey<Item> FOLD = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("fundamentum", "tooltip_fold"));

    /** Adds another mod's detail lines for a stack to Fundamentum's block, which folds behind Shift with the rest. */
    @FunctionalInterface
    public interface DetailSource {
        /** Appends the lines for this stack (none for stacks it does not know). Stat lines: see {@link Tooltips#stat}. */
        void add(ItemStack stack, List<Component> lines);
    }

    private static final List<DetailSource> SOURCES = new CopyOnWriteArrayList<>();

    /**
     * Registers detail lines for other mods' items (client side, e.g. from FMLClientSetupEvent): they go under the
     * description, fold behind "Hold [Shift] for details" like Fundamentum's own lines, and count toward
     * {@link TooltipRule}. A mod with its own {@code appendHoverText} can instead ask {@link #showDetails}.
     */
    public static void details(DetailSource source) {
        SOURCES.add(source);
    }

    /**
     * Whether a tooltip should show its details now: Shift held, recipe viewers and search (all information, or no
     * player), or {@code shiftForDetails} off. For mods that write their own tooltip lines and want the same rule.
     */
    public static boolean showDetails(TooltipFlag flag, @Nullable Player player) {
        return !shiftForDetails() || player == null || flag.shouldDisplayAllInformation() || flag.hasShiftDown() || shiftHeld();
    }

    /** The grey "Hold [Shift] for details" line, "Shift" in white. */
    public static Component holdShift() {
        return Component.translatable("tooltip.fundamentum.hold_shift",
            Component.translatable("tooltip.fundamentum.key_shift").withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY);
    }

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        List<Component> lines = event.getToolTip();
        if (lines.isEmpty()) {
            return;
        }
        List<Component> top = new ArrayList<>();
        String key = stack.getItem().getDescriptionId();
        for (String suffix : new String[] {".desc", ".desc2", ".desc3"}) {
            if (I18n.exists(key + suffix)) {
                top.add(Component.translatable(key + suffix).withStyle(ChatFormatting.GRAY));
            }
        }
        for (DetailSource source : SOURCES) {
            source.add(stack, top);
        }
        boolean stats = false;
        WhipDefinition whip = Whips.definition(stack);
        if (whip != null) {
            whip(whip, top);
            stats = true;
        }
        Hooks.Def hook = Hooks.def(stack);
        if (hook != null) {
            hook(hook, top);
            stats = true;
        }
        List<Component> bottom = new ArrayList<>();
        ElementAffinities.Affinity affinity = stack.get(ElementAffinities.ITEM.get());
        if (affinity != null) {
            affinity(affinity, bottom);
            stats |= !bottom.isEmpty();
        }
        if (top.isEmpty() && bottom.isEmpty()) {
            return;
        }
        List<String> text = new ArrayList<>();
        top.forEach(c -> text.add(c.getString()));
        bottom.forEach(c -> text.add(c.getString()));
        boolean folds = TooltipRule.folds(text, stats, gear(stack, whip != null || hook != null), stack.is(ALWAYS), stack.is(FOLD));
        if (folds && !showDetails(event.getFlags(), event.getEntity())) {
            lines.add(1, holdShift());
            return;
        }
        lines.addAll(1, top);
        lines.addAll(bottom);
    }

    private static boolean shiftForDetails() {
        return !WhipsConfig.SPEC.isLoaded() || WhipsConfig.SHIFT_FOR_DETAILS.get();
    }

    private static boolean shiftHeld() {
        return Minecraft.getInstance().hasShiftDown();
    }

    /** Weapons, tools, armour, anything equippable or worn in a Curios slot, whips, hooks, summons and ammo. */
    private static boolean gear(ItemStack stack, boolean whipOrHook) {
        if (whipOrHook || stack.isDamageableItem() || stack.has(DataComponents.EQUIPPABLE) || stack.has(DataComponents.WEAPON)
            || stack.has(DataComponents.TOOL) || stack.is(ItemTags.ARROWS) || Summons.def(stack) != null) {
            return true;
        }
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers != null && !modifiers.modifiers().isEmpty()) {
            return true;
        }
        return stack.typeHolder().tags().anyMatch(tag -> tag.location().getNamespace().equals("curios")
            || tag.location().getPath().contains("ammo"));
    }

    /** An item's element_affinity component (1.25.0): resistances in blue, weaknesses in red, immunities. */
    private static void affinity(ElementAffinities.Affinity affinity, List<Component> lines) {
        affinity.resist().forEach((element, value) -> lines.add(Component.translatable(
            value >= 0 ? "tooltip.fundamentum.element.resist" : "tooltip.fundamentum.element.weak", element.name(), num(Math.abs(value) * 100))
            .withStyle(value >= 0 ? ChatFormatting.BLUE : ChatFormatting.RED)));
        affinity.immune().forEach(element -> lines.add(Component.translatable("tooltip.fundamentum.element.immune", element.name())
            .withStyle(ChatFormatting.BLUE)));
    }

    private static void whip(WhipDefinition whip, List<Component> lines) {
        WhipTuning t = whip.tuning;
        List<Component> add = new ArrayList<>();
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
        lines.addAll(add);
    }

    private static void hook(Hooks.Def hook, List<Component> lines) {
        List<Component> add = new ArrayList<>();
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
        lines.addAll(add);
    }

    /** A stat line in vanilla's attribute style: indented, dark green. Public for {@link DetailSource}s. */
    public static Component stat(String key, Object... args) {
        return Component.literal(" ").append(Component.translatable(key, args)).withStyle(ChatFormatting.DARK_GREEN);
    }

    /** Up to two decimals, trailing zeros dropped. */
    private static String num(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private Tooltips() {}
}
