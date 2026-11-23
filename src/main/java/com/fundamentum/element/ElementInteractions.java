package com.fundamentum.element;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Elemental interactions (1.25.0), built on {@link com.fundamentum.damage.Elements}: an element is a damage type or a
 * damage type tag, nothing more. Hits leave auras ({@link ElementAuras}), hits on an aura of another element react
 * ({@link ElementReactions}), creatures and gear resist ({@link ElementAffinities}), mods hook it all through
 * {@link ElementEvents}, and {@code /fundamentum element} inspects it ({@link ElementCommand}). All of it is data; the
 * vanilla set (fire, ice, water, electric, arcane magic, shadow wither, explosions) ships as the built-in datapack
 * "Fundamentum: vanilla elements" ({@code mod/fundamentum:packs/elements_default}), on by default and switched off like any
 * datapack ({@code /datapack disable "mod/fundamentum:packs/elements_default"}, or in the world's datapack screen). The
 * schema is in {@code docs/ELEMENTS.md}.
 */
public final class ElementInteractions {
    public static void init(IEventBus modBus) {
        ElementAuras.init(modBus);
        ElementAffinities.init(modBus);
        ElementReactions.init();
        ElementCommand.init();
        ElementSelfTest.init();
        modBus.addListener(ElementInteractions::packs);
    }

    private static void packs(AddPackFindersEvent event) {
        event.addPackFinders(Identifier.fromNamespaceAndPath("fundamentum", "packs/elements_default"), PackType.SERVER_DATA,
            Component.translatable("pack.fundamentum.elements_default"), PackSource.BUILT_IN, false, Pack.Position.TOP);
    }

    private ElementInteractions() {}
}
