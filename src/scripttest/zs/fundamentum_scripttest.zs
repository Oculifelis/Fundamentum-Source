import mods.fundamentum.Fundamentum;

// type-checks a player method without a player to hand
function guildRep(p as crafttweaker.api.entity.type.player.ServerPlayer) as int {
    return Fundamentum.rep(p, "scripttest:guild");
}

println("FUNDCT ok, events " + Fundamentum.eventIds().length);
