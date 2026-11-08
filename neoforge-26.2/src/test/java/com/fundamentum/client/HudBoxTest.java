package com.fundamentum.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HudBoxTest {
    @Test
    void besideTheHotbarTwoLinesSitWhereTheCrossbowHudDid() {
        // 960x540 GUI (1920x1080 at scale 2): two lines are 18 high, top at hotbar top + 2
        int hx = HudBox.hotbarRight(960, false, false);
        assertEquals(480 + 97, hx);
        assertArrayEquals(new int[] {577, 540 - 22 + 2}, HudBox.place(HudBox.Anchor.HOTBAR_RIGHT, 960, 540, 100, 18, hx, 0, 0));
        // one line (8 high) is centred: hotbar top + 7
        assertArrayEquals(new int[] {577, 540 - 22 + 7}, HudBox.place(HudBox.Anchor.HOTBAR_RIGHT, 960, 540, 100, 8, hx, 0, 0));
    }

    @Test
    void movesAsideForTheOffhandSlotAndAttackIndicator() {
        assertEquals(480 + 91 + 29 + 4, HudBox.hotbarRight(960, true, true));
        assertEquals(480 + 97 + 22, HudBox.hotbarRight(960, false, true));
    }

    @Test
    void tallBoxesGrowUpwardAndStayOnScreen() {
        // five lines (48 high) can't centre on the hotbar: kept on screen
        int[] at = HudBox.place(HudBox.Anchor.HOTBAR_RIGHT, 960, 540, 100, 48, 577, 0, 0);
        assertEquals(540 - 48, at[1]);
        // a wide box at the right edge is pulled back on screen
        assertEquals(960 - 500, HudBox.place(HudBox.Anchor.HOTBAR_RIGHT, 960, 540, 500, 18, 577, 0, 0)[0]);
        // negative offsets past the edge clamp to 0
        assertArrayEquals(new int[] {0, 0}, HudBox.place(HudBox.Anchor.TOP_LEFT, 960, 540, 50, 10, 0, -20, -20));
    }

    @Test
    void screenAnchorsUseTheirFractions() {
        assertArrayEquals(new int[] {4, 4}, HudBox.place(HudBox.Anchor.TOP_LEFT, 960, 540, 50, 10, 0, 4, 4));
        assertArrayEquals(new int[] {910, 265}, HudBox.place(HudBox.Anchor.MIDDLE_RIGHT, 960, 540, 50, 10, 0, 0, 0));
        assertArrayEquals(new int[] {455, 530}, HudBox.place(HudBox.Anchor.BOTTOM_CENTER, 960, 540, 50, 10, 0, 0, 0));
    }

    @Test
    void coloursParseWithOpacity() {
        assertEquals(0xFFFF8800, HudBox.argb("#FF8800", 1.0));
        assertEquals(0x80FFFFFF, HudBox.argb("nonsense", 0.5));
        assertEquals(26 << 24 | 0x123456, HudBox.argb("123456", 0.0));
    }
}
