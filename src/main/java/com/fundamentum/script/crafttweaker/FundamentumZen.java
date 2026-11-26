package com.fundamentum.script.crafttweaker;

import com.blamejared.crafttweaker.api.annotation.ZenRegister;
import com.fundamentum.script.FScript;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.openzen.zencode.java.ZenCodeType;

/**
 * CraftTweaker support: {@link FScript} as {@code mods.fundamentum.Fundamentum}. CraftTweaker finds this class by its
 * annotation; nothing loads it without CraftTweaker.
 * <pre>
 * import mods.fundamentum.Fundamentum;
 * Fundamentum.addRep(player, "mymod:guild", 10);
 * </pre>
 */
@ZenRegister
@ZenCodeType.Name("mods.fundamentum.Fundamentum")
public final class FundamentumZen {
    private FundamentumZen() { }

    private static String[] arr(List<String> list) { return list.toArray(new String[0]); }

    @ZenCodeType.Method public static String[] eventIds() { return arr(FScript.eventIds()); }

    // factions
    @ZenCodeType.Method public static String[] factions() { return arr(FScript.factions()); }
    @ZenCodeType.Method public static int rep(ServerPlayer p, String faction) { return FScript.rep(p, faction); }
    @ZenCodeType.Method public static boolean setRep(ServerPlayer p, String faction, int value) { return FScript.setRep(p, faction, value); }
    @ZenCodeType.Method public static void addRep(ServerPlayer p, String faction, int delta) { FScript.addRep(p, faction, delta); }
    @ZenCodeType.Method @ZenCodeType.Nullable public static String rank(ServerPlayer p, String faction) { return FScript.rank(p, faction); }
    @ZenCodeType.Method public static boolean isMember(ServerPlayer p, String faction) { return FScript.isMember(p, faction); }
    @ZenCodeType.Method @ZenCodeType.Nullable public static String join(ServerPlayer p, String faction) { return FScript.join(p, faction); }
    @ZenCodeType.Method public static boolean leave(ServerPlayer p, String faction) { return FScript.leave(p, faction); }
    @ZenCodeType.Method public static double alignment(ServerPlayer p, String axis) { return FScript.alignment(p, axis); }
    @ZenCodeType.Method public static void addAlignment(ServerPlayer p, String axis, double delta) { FScript.addAlignment(p, axis, delta); }
    @ZenCodeType.Method public static String attitude(Entity mob, ServerPlayer p) { return FScript.attitude(mob, p); }
    @ZenCodeType.Method public static int act(ServerPlayer p, String action) { return FScript.act(p, action); }

    // quests
    @ZenCodeType.Method public static String[] quests() { return arr(FScript.quests()); }
    @ZenCodeType.Method @ZenCodeType.Nullable public static String acceptQuest(ServerPlayer p, String quest) { return FScript.acceptQuest(p, quest); }
    @ZenCodeType.Method public static boolean completeQuest(ServerPlayer p, String quest) { return FScript.completeQuest(p, quest); }
    @ZenCodeType.Method public static boolean failQuest(ServerPlayer p, String quest) { return FScript.failQuest(p, quest); }
    @ZenCodeType.Method public static boolean questActive(ServerPlayer p, String quest) { return FScript.questActive(p, quest); }
    @ZenCodeType.Method public static boolean questDone(ServerPlayer p, String quest) { return FScript.questDone(p, quest); }
    @ZenCodeType.Method public static int questSignal(ServerPlayer p, String type, String context, int amount) { return FScript.questSignal(p, type, context, amount); }

    // religion
    @ZenCodeType.Method public static String[] gods() { return arr(FScript.gods()); }
    @ZenCodeType.Method public static int devotion(ServerPlayer p, String god) { return FScript.devotion(p, god); }
    @ZenCodeType.Method public static void addDevotion(ServerPlayer p, String god, int delta) { FScript.addDevotion(p, god, delta); }
    @ZenCodeType.Method public static boolean follows(ServerPlayer p, String god) { return FScript.follows(p, god); }
    @ZenCodeType.Method @ZenCodeType.Nullable public static String follow(ServerPlayer p, String god) { return FScript.follow(p, god); }
    @ZenCodeType.Method public static boolean forsake(ServerPlayer p, String god) { return FScript.forsake(p, god); }
    @ZenCodeType.Method public static double favour(ServerPlayer p, String god) { return FScript.favour(p, god); }
    @ZenCodeType.Method public static double addFavour(ServerPlayer p, String god, double delta) { return FScript.addFavour(p, god, delta); }
    @ZenCodeType.Method @ZenCodeType.Nullable public static String boon(ServerPlayer p, String god, String boon) { return FScript.boon(p, god, boon); }

    // unlocks and stats
    @ZenCodeType.Method public static boolean hasUnlock(ServerPlayer p, String unlock) { return FScript.hasUnlock(p, unlock); }
    @ZenCodeType.Method public static int unlockLevel(ServerPlayer p, String unlock) { return FScript.unlockLevel(p, unlock); }
    @ZenCodeType.Method public static boolean grantUnlock(ServerPlayer p, String unlock, int level) { return FScript.grantUnlock(p, unlock, level); }
    @ZenCodeType.Method public static double stat(LivingEntity e, String stat) { return FScript.stat(e, stat); }

    // mana, stamina and poise
    @ZenCodeType.Method public static double mana(LivingEntity e) { return FScript.mana(e); }
    @ZenCodeType.Method public static double manaMax(LivingEntity e) { return FScript.manaMax(e); }
    @ZenCodeType.Method public static boolean spendMana(LivingEntity e, double amount) { return FScript.spendMana(e, amount); }
    @ZenCodeType.Method public static double restoreMana(LivingEntity e, double amount) { return FScript.restoreMana(e, amount); }
    @ZenCodeType.Method public static double stamina(LivingEntity e) { return FScript.stamina(e); }
    @ZenCodeType.Method public static boolean spendStamina(LivingEntity e, double amount) { return FScript.spendStamina(e, amount); }
    @ZenCodeType.Method public static double restoreStamina(LivingEntity e, double amount) { return FScript.restoreStamina(e, amount); }
    @ZenCodeType.Method public static double poise(LivingEntity e) { return FScript.poise(e); }
    @ZenCodeType.Method public static boolean staggered(LivingEntity e) { return FScript.staggered(e); }
    @ZenCodeType.Method public static boolean stagger(LivingEntity target, Entity from, int ticks) { return FScript.stagger(target, from, ticks); }
}
