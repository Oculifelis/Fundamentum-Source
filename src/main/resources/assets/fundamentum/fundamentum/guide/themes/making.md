---
title: Making a Theme
chapter: themes
icon: minecraft:brush
order: 2
---
# Making a Theme

The quickest start is a copy of a built-in theme. All of them sit in the Fundamentum jar at `assets/fundamentum/fundamentum/guide_themes/`. Copy one into `config/fundamentum/guide_themes/`, rename the file, change its `name` and then its colours.

Every field is optional. Anything a theme leaves out comes from the vanilla container look, so this small file is a whole theme:

`{"name": "Plum", "style": "flat", "colors": {"fill": "#3A2040"}}`

## Style

- `flat` draws one fill and a thin frame.
- `bevel` draws container edges and sunken boxes.
- `textured` tiles block textures across the panel, set under `texture` with a `background` and an optional `border`.

## Colours

Colours are `#RRGGBB`, or `#AARRGGBB` for see-through ones. `colors` covers the panel and the page list, `page` the page text, `well` the boxes behind text and `slot` the item slots. `backdrop` is the colour over the world behind the guide.

## Extras

- `gradient` takes two colours, top and bottom.
- `rainbow` makes a moving rainbow border, for example `{"saturation": 0.55, "speed": 1.0}`.
- `beads` gives the edge a row of beads in one colour.
- `ribbon` hangs a ribbon marker from the top in one colour.

## Tags

`tags` is a list of words the picker filters by, such as `dark`, `light`, `textured` or `animated`.

Mods ship themes at `assets/<namespace>/fundamentum/guide_themes/` in their jar, and a mod's book can pick one with `"theme"` in its `chapters.json`.
