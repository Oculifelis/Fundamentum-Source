// Fundamentum script test, only in -Pscripttest runs (ScriptSelfTest checks what this does)

// a targeted handler: copies each Script Guild rep change onto Script Echo, and refuses any change to 900 or more
FundamentumEvents.event('fundamentum:rep_changed', e => {
  if ('' + e.get('faction') != 'scripttest:guild') return
  if (e.get('to') >= 900) {
    e.cancel()
    return
  }
  Fundamentum.addRep(e.player, 'scripttest:echo', e.get('to') - e.get('from'))
})

// an untargeted handler: hears every event, and keeps the player out of Script Closed
FundamentumEvents.event(e => {
  if (e.id == 'fundamentum:faction_joined' && '' + e.get('faction') == 'scripttest:closed') e.cancel()
})

console.info('FUNDSCRIPT loaded, factions ' + Fundamentum.factions().size())
