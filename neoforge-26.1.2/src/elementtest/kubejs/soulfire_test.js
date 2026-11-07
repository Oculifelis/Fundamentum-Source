// Fundamentum soul fire KubeJS check (dev only). With the KubeJS, Rhino and Better Advanced Tooltips jars in libs/, copy
// this into run/kubejs/server_scripts/ and run gradlew runServer -Pelementtest; it logs KJSSOULFIRE PASS/FAIL and DONE.
const SoulFire = Java.loadClass('com.fundamentum.api.SoulFire')
const JEntityType = Java.loadClass('net.minecraft.world.entity.EntityType')
const JSpawnReason = Java.loadClass('net.minecraft.world.entity.EntitySpawnReason')
const JItemStack = Java.loadClass('net.minecraft.world.item.ItemStack')
const JItems = Java.loadClass('net.minecraft.world.item.Items')
const MCBlocks = Java.loadClass('net.minecraft.world.level.block.Blocks')

// the soul fire tags work with KubeJS's tag events
ServerEvents.tags('item', event => {
  event.add('fundamentum:soul_fire_weapons', 'minecraft:netherite_sword')
})

let kjsTicks = 0
ServerEvents.tick(event => {
  kjsTicks++
  if (kjsTicks != 60) return
  let ok = 0
  let total = 0
  const check = (name, pass) => {
    total++
    if (pass) ok++
    console.info('KJSSOULFIRE ' + (pass ? 'PASS ' : 'FAIL ') + name)
  }
  const level = event.server.overworld()
  const pig = JEntityType.PIG.create(level, JSpawnReason.COMMAND)
  pig.setPos(2.5, 246, 10.5)
  pig.setNoGravity(true)
  level.addFreshEntity(pig)
  SoulFire.ignite(pig, 100)
  check('ignite: on fire and soul burning', pig.isOnFire() && SoulFire.isSoulBurning(pig))
  SoulFire.clear(pig)
  check('clear: still on fire, not soul burning', pig.isOnFire() && !SoulFire.isSoulBurning(pig))
  pig.discard()
  check('tag added by ServerEvents.tags is in SoulFire.WEAPONS', new JItemStack(JItems.NETHERITE_SWORD)['is(net.minecraft.tags.TagKey)'](SoulFire.WEAPONS))
  check('vanilla soul fire is in SoulFire.SOURCES', MCBlocks.SOUL_FIRE.defaultBlockState()['is(net.minecraft.tags.TagKey)'](SoulFire.SOURCES))
  console.info('KJSSOULFIRE DONE ' + ok + '/' + total + ' failed=' + (total - ok))
})
