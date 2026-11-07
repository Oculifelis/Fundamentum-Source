package com.fundamentum.whip;

/**
 * The numbers of one whip: the cord, the swing and the damage. Pure data (no game types), so tests and data files
 * share it; {@link WhipDefinition} adds the hooks and the look. Every field has a default (a plain leather whip,
 * chosen 2026-10-30 and written down in README.md); set only the ones that differ:
 * <pre>WhipTuning.builder().length(6).damage(9).tipCritMultiplier(2).build()</pre>
 *
 * @param length          cord length in blocks, handle to tip
 * @param segments        links the cord is simulated with (more is smoother and dearer; 8 to 48)
 * @param stiffness       0 to 1: how hard the cord resists bending (0 a rope, 1 nearly a rod)
 * @param guide           0 to 1: how closely the cord follows the swing's lagged shape while the stroke drives it.
 *                        Lower is looser and harder to aim; 0 leaves it to plain physics
 * @param lag             seconds for the swing to travel from handle to tip; the lag is what makes the tip crack
 * @param retention       velocity kept per tick (air drag), 0 to 1
 * @param gravity         blocks per second squared pulling the cord down
 * @param arc             degrees the stroke sweeps through; it ends {@code arc * 0.12} below the crosshair
 * @param tilt            degrees the swing plane leans off vertical (0 an overhead lash, 90 a flat side sweep);
 *                        each swing alternates sides
 * @param windupTicks     ticks drawing the whip back over the shoulder
 * @param strokeTicks     ticks of the forward stroke (hits count from its start)
 * @param recoverTicks    ticks after the stroke while the cord falls back; hits still count for the first
 *                        {@code hitTicksAfterStroke} of them
 * @param hitTicksAfterStroke ticks after the stroke that still hit
 * @param cooldownTicks   ticks before the next swing (the item cooldown bar)
 * @param damage          damage of a full-strength hit at the tip
 * @param rootDamage      fraction of {@code damage} a hit right at the handle does; it rises to 1 at the tip
 * @param falloffPower    the curve between: 1 linear, above 1 keeps the damage for the outer cord
 * @param tipFraction     the outer fraction of the cord that is the tip
 * @param crackSpeed      tip speed in blocks per second at which the tip cracks: a tip hit then is a crit
 * @param tipCritMultiplier damage multiplier of a crit
 * @param hitRadius       half thickness of the cord for hits, in blocks
 * @param strands         cords per swing (a cat o' nine tails is several), each tested on its own
 * @param strandSpread    degrees between neighbouring strands
 * @param sweep           true: a swing hits every target its cord meets (up to {@code maxTargets});
 *                        false: it stops at the first
 * @param maxTargets      targets one swing may hit, over all strands
 * @param multiHitFalloff each further target of a swing takes this times the previous one's damage...
 * @param multiHitFloor   ...but never less than this fraction
 * @param knockback       knockback of a hit, about vanilla's units
 * @param substeps        physics steps per tick (the tip moves fast; fewer than 6 lets it pass through mobs)
 */
public record WhipTuning(double length, int segments, double stiffness, double guide, double lag, double retention,
                         double gravity, double arc, double tilt, int windupTicks, int strokeTicks, int recoverTicks,
                         int hitTicksAfterStroke, int cooldownTicks, double damage, double rootDamage, double falloffPower,
                         double tipFraction, double crackSpeed, double tipCritMultiplier, double hitRadius, int strands,
                         double strandSpread, boolean sweep, int maxTargets, double multiHitFalloff, double multiHitFloor,
                         double knockback, int substeps) {
    public static final WhipTuning DEFAULT = builder().build();

    public WhipTuning {
        segments = Math.clamp(segments, 4, 64);
        length = Math.max(0.5, length);
        stiffness = Math.clamp(stiffness, 0.0, 1.0);
        guide = Math.clamp(guide, 0.0, 1.0);
        lag = Math.max(0.01, lag);
        retention = Math.clamp(retention, 0.0, 1.0);
        windupTicks = Math.max(0, windupTicks);
        strokeTicks = Math.max(1, strokeTicks);
        recoverTicks = Math.max(0, recoverTicks);
        hitTicksAfterStroke = Math.clamp(hitTicksAfterStroke, 0, recoverTicks);
        cooldownTicks = Math.max(1, cooldownTicks);
        tipFraction = Math.clamp(tipFraction, 0.0, 1.0);
        strands = Math.clamp(strands, 1, 9);
        maxTargets = Math.max(1, maxTargets);
        substeps = Math.clamp(substeps, 1, 32);
    }

    /** Ticks from the click to the end of the swing. */
    public int totalTicks() {
        return windupTicks + strokeTicks + recoverTicks;
    }

    /** True while hits count, at {@code tick} ticks into the swing (fractional for substeps). */
    public boolean hitting(double tick) {
        return tick > windupTicks && tick <= windupTicks + strokeTicks + hitTicksAfterStroke;
    }

    /** Damage multiplier at {@code along} (0 the handle, 1 the tip). */
    public double falloff(double along) {
        double t = Math.clamp(along, 0.0, 1.0);
        return rootDamage + (1.0 - rootDamage) * Math.pow(t, falloffPower);
    }

    /** True when {@code along} is on the tip. */
    public boolean isTip(double along) {
        return along >= 1.0 - tipFraction;
    }

    /** Damage multiplier of a swing's {@code index}th target (0 the first). */
    public double multiHit(int index) {
        return Math.max(multiHitFloor, Math.pow(multiHitFalloff, index));
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link WhipTuning}; see the record for what each field means. */
    public static final class Builder {
        double length = 4.5, stiffness = 0.3, guide = 0.85, lag = 0.14, retention = 0.98, gravity = 20, arc = 150, tilt = 30;
        int segments = 20, windupTicks = 4, strokeTicks = 5, recoverTicks = 8, hitTicksAfterStroke = 3, cooldownTicks = 13;
        double damage = 6, rootDamage = 0.4, falloffPower = 1.5, tipFraction = 0.2, crackSpeed = 25, tipCritMultiplier = 1.75;
        double hitRadius = 0.15, strandSpread = 12, multiHitFalloff = 0.85, multiHitFloor = 0.5, knockback = 0.4;
        int strands = 1, maxTargets = 8, substeps = 8;
        boolean sweep = true;

        Builder() {}

        Builder(WhipTuning t) {
            length = t.length; segments = t.segments; stiffness = t.stiffness; guide = t.guide; lag = t.lag;
            retention = t.retention; gravity = t.gravity; arc = t.arc; tilt = t.tilt; windupTicks = t.windupTicks;
            strokeTicks = t.strokeTicks; recoverTicks = t.recoverTicks; hitTicksAfterStroke = t.hitTicksAfterStroke;
            cooldownTicks = t.cooldownTicks; damage = t.damage; rootDamage = t.rootDamage; falloffPower = t.falloffPower;
            tipFraction = t.tipFraction; crackSpeed = t.crackSpeed; tipCritMultiplier = t.tipCritMultiplier;
            hitRadius = t.hitRadius; strands = t.strands; strandSpread = t.strandSpread; sweep = t.sweep;
            maxTargets = t.maxTargets; multiHitFalloff = t.multiHitFalloff; multiHitFloor = t.multiHitFloor;
            knockback = t.knockback; substeps = t.substeps;
        }

        public Builder length(double v) { length = v; return this; }
        public Builder segments(int v) { segments = v; return this; }
        public Builder stiffness(double v) { stiffness = v; return this; }
        public Builder guide(double v) { guide = v; return this; }
        public Builder lag(double v) { lag = v; return this; }
        public Builder retention(double v) { retention = v; return this; }
        public Builder gravity(double v) { gravity = v; return this; }
        public Builder arc(double v) { arc = v; return this; }
        public Builder tilt(double v) { tilt = v; return this; }
        public Builder windupTicks(int v) { windupTicks = v; return this; }
        public Builder strokeTicks(int v) { strokeTicks = v; return this; }
        public Builder recoverTicks(int v) { recoverTicks = v; return this; }
        public Builder hitTicksAfterStroke(int v) { hitTicksAfterStroke = v; return this; }
        public Builder cooldownTicks(int v) { cooldownTicks = v; return this; }
        public Builder damage(double v) { damage = v; return this; }
        public Builder rootDamage(double v) { rootDamage = v; return this; }
        public Builder falloffPower(double v) { falloffPower = v; return this; }
        public Builder tipFraction(double v) { tipFraction = v; return this; }
        public Builder crackSpeed(double v) { crackSpeed = v; return this; }
        public Builder tipCritMultiplier(double v) { tipCritMultiplier = v; return this; }
        public Builder hitRadius(double v) { hitRadius = v; return this; }
        public Builder strands(int v) { strands = v; return this; }
        public Builder strandSpread(double v) { strandSpread = v; return this; }
        public Builder sweep(boolean v) { sweep = v; return this; }
        public Builder maxTargets(int v) { maxTargets = v; return this; }
        public Builder multiHitFalloff(double v) { multiHitFalloff = v; return this; }
        public Builder multiHitFloor(double v) { multiHitFloor = v; return this; }
        public Builder knockback(double v) { knockback = v; return this; }
        public Builder substeps(int v) { substeps = v; return this; }

        public WhipTuning build() {
            return new WhipTuning(length, segments, stiffness, guide, lag, retention, gravity, arc, tilt, windupTicks,
                strokeTicks, recoverTicks, hitTicksAfterStroke, cooldownTicks, damage, rootDamage, falloffPower,
                tipFraction, crackSpeed, tipCritMultiplier, hitRadius, strands, strandSpread, sweep, maxTargets,
                multiHitFalloff, multiHitFloor, knockback, substeps);
        }
    }
}
