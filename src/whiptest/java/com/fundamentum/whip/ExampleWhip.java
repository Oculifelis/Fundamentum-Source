package com.fundamentum.whip;

import net.minecraft.resources.Identifier;

/**
 * {@code fundamentum:example_whip}, registered in development runs only: a plain leather whip on the default tuning,
 * with a small summoner mark, to show and test the API. Its cord and item textures are Fundamentum's own placeholders.
 */
final class ExampleWhip {
    static WhipDefinition definition() {
        return WhipDefinition.builder(Identifier.fromNamespaceAndPath("fundamentum", "example_whip"))
            .color(0xFF8A5A33).tipColor(0xFFD9B98C)
            .summonTag(new WhipDefinition.SummonTag(240, 3, 1.0, 0.05))
            .build();
    }

    private ExampleWhip() {}
}
