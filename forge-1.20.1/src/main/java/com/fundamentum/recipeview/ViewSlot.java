package com.fundamentum.recipeview;

/**
 * One slot of a category's layout. {@code x} and {@code y} are the slot's top-left corner inside the category's area,
 * the same in every viewer. A catalyst slot shows the block or tool the recipe needs in place (viewers show it as an
 * input that is not used up).
 */
public record ViewSlot(int index, Role role, Kind kind, int x, int y) {
    public enum Role { INPUT, OUTPUT, CATALYST }
    public enum Kind { ITEM, FLUID }
}
