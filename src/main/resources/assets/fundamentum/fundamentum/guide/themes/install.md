---
title: Installing a Theme
chapter: themes
icon: minecraft:chest
order: 1
---
# Installing a Theme

A theme is one `.json` file. Put it in this folder inside the game folder, and make the folder if it is missing:

`config/fundamentum/guide_themes/`

Then reload resources with `F3+T`, or restart the game. The new theme shows in the theme picker under its `name`, or under the file name when it has none.

---

## Other places themes come from

- Mods ship themes inside their jars.
- A resource pack can carry themes at `assets/<namespace>/fundamentum/guide_themes/`, and a pack theme with the same id as a built-in one replaces it.

A file with a bad colour still loads. The bad colour shows as magenta, and the game log names the field.

To write a theme, see [Making a Theme](fundamentum:themes/making).
