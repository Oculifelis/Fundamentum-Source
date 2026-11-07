/*
 * The aim assist rule is adapted from Better Whips (https://github.com/my2167592261-cell/Better-Whips), whose code is
 * under the MIT License:
 *
 * Copyright (c) 2026 my2167592261-cell
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.fundamentum.whip;

import com.fundamentum.summon.Summons;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.entity.PartEntity;

/**
 * Who a swinging cord may hit. Better Whips' aim assist: a target near the crosshair ({@link #CROSSHAIR_DEGREES}) is hit
 * like any melee target; anywhere else the cord only hits hostiles, so a wild swing does not flay the villagers. Each
 * whip may swap that {@link Rule}. On top, a spared target is never hit by any whip: the player themself, their mount
 * and passengers, their tamed pets, their Fundamentum minions, players in creative or spectator, and whatever a mod adds
 * with {@link #spare} (its companions and summons).
 *
 * <p>Multipart creatures (the ender dragon) are hit through their parts, and end crystals are hit too.
 */
public final class WhipTargets {
    /** The aim assist cone's half angle, in degrees. */
    public static final double CROSSHAIR_DEGREES = 15.0;

    /** May a whip hit this target? {@code nearCrosshair} is the aim assist test. Server side. */
    public interface Rule {
        boolean mayHit(ServerPlayer owner, Entity target, boolean nearCrosshair);
    }

    /** Near the crosshair anything; elsewhere hostiles only. */
    public static final Rule DEFAULT = (owner, target, near) -> near || isHostile(target);
    /** Hostiles only, even on the crosshair. */
    public static final Rule HOSTILES = (owner, target, near) -> isHostile(target);
    /** Anything the cord touches (spared targets still are). */
    public static final Rule ANYTHING = (owner, target, near) -> true;

    private static final List<BiPredicate<ServerPlayer, Entity>> SPARED = new CopyOnWriteArrayList<>();

    /** Never let a whip hit what the test accepts (given the swinging player and the whole target). */
    public static void spare(BiPredicate<ServerPlayer, Entity> test) {
        SPARED.add(test);
    }

    /** True when no whip of {@code owner}'s may hit {@code target} (the whole target, not a part). */
    public static boolean spared(ServerPlayer owner, Entity target) {
        if (target == owner || target.isPassengerOfSameVehicle(owner) || owner.hasPassenger(target) || target.hasPassenger(owner)) {
            return true;
        }
        if (target instanceof Player p && (p.isCreative() || p.isSpectator() || !owner.canHarmPlayer(p))) {
            return true;
        }
        if (target instanceof OwnableEntity pet && pet.getOwner() == owner) {
            return true;
        }
        if (Summons.allies(owner, target)) {
            return true;
        }
        for (BiPredicate<ServerPlayer, Entity> test : SPARED) {
            if (test.test(owner, target)) {
                return true;
            }
        }
        return false;
    }

    /** Hostile for the aim assist: a monster, an enemy, or an end crystal (of a whole target, not a part). */
    public static boolean isHostile(Entity target) {
        Entity e = whole(target);
        return e instanceof Enemy || e.getType().getCategory() == MobCategory.MONSTER || e instanceof EndCrystal;
    }

    /** The whole creature a part belongs to, or the entity itself. */
    public static Entity whole(Entity entity) {
        return entity instanceof PartEntity<?> part && part.getParent() != null ? part.getParent() : entity;
    }

    /** Can a cord touch this at all: alive, not the owner, and something a melee attack could hit. */
    static boolean touchable(ServerPlayer owner, Entity e) {
        if (e == owner || e.isRemoved() || e.isSpectator() || !e.isAlive()) {
            return false;
        }
        return e instanceof LivingEntity || e instanceof PartEntity<?> || e.isPickable() && e.isAttackable() && !e.skipAttackInteraction(owner);
    }

    private WhipTargets() {}
}
