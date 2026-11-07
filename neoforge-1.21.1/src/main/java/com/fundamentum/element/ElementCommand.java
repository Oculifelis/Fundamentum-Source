package com.fundamentum.element;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceKeyArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /fundamentum element ...}, for testing elemental data (operator only):
 * <ul>
 * <li>{@code auras <targets>}: each creature's auras, with stacks, strength and seconds left;</li>
 * <li>{@code clear <targets>}: removes their auras;</li>
 * <li>{@code hit <targets> <damage type> <amount> [attacker]}: a test hit of that type (their hurt cooldown is reset
 * first, so hits in a row all land), then the health it took and the auras left;</li>
 * <li>{@code affinity <targets> <damage type>}: what they resist of that type, and from where;</li>
 * <li>{@code reactions}: the loaded reactions in the order they are tried;</li>
 * <li>{@code help}: the manual, from lang lines {@code fundamentum.element.help.1..8}.</li>
 * </ul>
 */
public final class ElementCommand {
    private static final DynamicCommandExceptionType ERROR_UNKNOWN_TYPE = new DynamicCommandExceptionType(
        id -> Component.translatableEscape("fundamentum.element.unknown_type", id));

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ElementCommand::register);
    }

    private static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fundamentum")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("element")
                .then(Commands.literal("help").executes(ElementCommand::help))
                .then(Commands.literal("reactions").executes(ElementCommand::reactions))
                .then(Commands.literal("auras")
                    .then(Commands.argument("targets", EntityArgument.entities()).executes(ElementCommand::auras)))
                .then(Commands.literal("clear")
                    .then(Commands.argument("targets", EntityArgument.entities()).executes(ElementCommand::clear)))
                .then(Commands.literal("affinity")
                    .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("type", ResourceKeyArgument.key(Registries.DAMAGE_TYPE)).executes(ElementCommand::affinity))))
                .then(Commands.literal("hit")
                    .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("type", ResourceKeyArgument.key(Registries.DAMAGE_TYPE))
                            .then(Commands.argument("amount", FloatArgumentType.floatArg(0))
                                .executes(c -> hit(c, null))
                                .then(Commands.argument("attacker", EntityArgument.entity())
                                    .executes(c -> hit(c, EntityArgument.getEntity(c, "attacker"))))))))));
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        for (int i = 1; i <= 8; i++) {
            String key = "fundamentum.element.help." + i;
            context.getSource().sendSuccess(() -> Component.translatable(key).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int reactions(CommandContext<CommandSourceStack> context) {
        List<Map.Entry<ResourceLocation, ElementReactions.Reaction>> all = ElementReactions.all();
        context.getSource().sendSuccess(() -> Component.translatable("fundamentum.element.reactions", all.size()).withStyle(ChatFormatting.GOLD), false);
        for (Map.Entry<ResourceLocation, ElementReactions.Reaction> e : all) {
            ElementReactions.Reaction r = e.getValue();
            context.getSource().sendSuccess(() -> Component.literal("  " + r.priority() + " ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.translatableWithFallback("reaction." + e.getKey().getNamespace() + "." + e.getKey().getPath().replace('/', '.'),
                    e.getKey().toString()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(": " + r.trigger() + " on " + r.aura()).withStyle(ChatFormatting.GRAY)), false);
        }
        return all.size();
    }

    private static int auras(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int n = 0;
        for (LivingEntity living : living(EntityArgument.getEntities(context, "targets"))) {
            report(context.getSource(), living);
            n++;
        }
        return n;
    }

    private static void report(CommandSourceStack source, LivingEntity living) {
        List<ElementAuras.Aura> auras = ElementAuras.get(living);
        long now = living.level().getGameTime();
        source.sendSuccess(() -> Component.empty().append(living.getDisplayName()).append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
            .append(auras.isEmpty() ? Component.translatable("fundamentum.element.no_auras").withStyle(ChatFormatting.DARK_GRAY) : Component.empty()), false);
        for (ElementAuras.Aura aura : auras) {
            source.sendSuccess(() -> Component.literal("  ").append(aura.element().name().withStyle(ChatFormatting.AQUA))
                .append(Component.translatable("fundamentum.element.aura_line", aura.stacks(), String.format("%.2f", aura.strength(now)),
                    String.format("%.1f", aura.ticksLeft(now) / 20.0)).withStyle(ChatFormatting.GRAY)), false);
        }
    }

    private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<LivingEntity> targets = living(EntityArgument.getEntities(context, "targets"));
        targets.forEach(ElementAuras::clear);
        context.getSource().sendSuccess(() -> Component.translatable("fundamentum.element.cleared", targets.size()), true);
        return targets.size();
    }

    private static int affinity(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Holder<DamageType> type = type(context);
        int n = 0;
        for (LivingEntity living : living(EntityArgument.getEntities(context, "targets"))) {
            ElementAffinities.Result result = ElementAffinities.resolve(living, new DamageSource(type));
            context.getSource().sendSuccess(() -> Component.empty().append(living.getDisplayName())
                .append(Component.translatable("fundamentum.element.affinity_line", type.getRegisteredName(),
                    String.format("%.2f", result.resistance()), String.format("%.2f", result.multiplier()), result.immune())
                    .withStyle(ChatFormatting.GRAY)), false);
            n++;
        }
        return n;
    }

    private static int hit(CommandContext<CommandSourceStack> context, @Nullable Entity attacker) throws CommandSyntaxException {
        Holder<DamageType> type = type(context);
        float amount = FloatArgumentType.getFloat(context, "amount");
        ServerLevel level = context.getSource().getLevel();
        int n = 0;
        for (LivingEntity living : living(EntityArgument.getEntities(context, "targets"))) {
            living.invulnerableTime = 0;
            float before = living.getHealth();
            living.hurt(new DamageSource(type, attacker, attacker), amount);
            float taken = before - living.getHealth();
            context.getSource().sendSuccess(() -> Component.empty().append(living.getDisplayName())
                .append(Component.translatable("fundamentum.element.hit_line", type.getRegisteredName(), String.format("%.2f", amount),
                    String.format("%.2f", taken)).withStyle(ChatFormatting.GRAY)), false);
            report(context.getSource(), living);
            n++;
        }
        return n;
    }

    private static Holder<DamageType> type(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceKey<?> raw = context.getArgument("type", ResourceKey.class);
        ResourceKey<DamageType> key = raw.cast(Registries.DAMAGE_TYPE).orElseThrow(() -> ERROR_UNKNOWN_TYPE.create(raw.location()));
        return context.getSource().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
    }

    private static List<LivingEntity> living(Collection<? extends Entity> entities) {
        return entities.stream().filter(e -> e instanceof LivingEntity).map(e -> (LivingEntity) e).toList();
    }

    private ElementCommand() {}
}
