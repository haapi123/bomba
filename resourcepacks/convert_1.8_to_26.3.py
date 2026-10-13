#!/usr/bin/env python3
"""Convert a Minecraft 1.8.9 resource pack so it loads on Java Edition 26.3 (resource pack format 97).

    python3 convert_1.8_to_26.3.py <old pack .zip or folder> <26.3 client.jar> <output .zip>

The 26.3 client jar is only read, never shipped: it supplies the GUI sprite metadata
(nine-slice borders) and lets the converter check that every file it writes is one the game reads.

Every coordinate below was checked against the vanilla 1.8.9 and 26.3 jars: where the art did not
change between the two, the old atlas region and the new sprite are pixel-identical.
"""
import io
import json
import os
import re
import sys
import zipfile

import numpy as np
from PIL import Image

TEX = 'assets/minecraft/textures/'
SPRITES = TEX + 'gui/sprites/'

COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
          'silver', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black']
WOODS = {'oak': 'oak', 'spruce': 'spruce', 'birch': 'birch', 'jungle': 'jungle',
         'acacia': 'acacia', 'big_oak': 'dark_oak'}

# textures/blocks/<old>.png -> textures/block/<new>.png
BLOCKS = {
    'anvil_base': 'anvil', 'anvil_top_damaged_0': 'anvil_top',
    'anvil_top_damaged_1': 'chipped_anvil_top', 'anvil_top_damaged_2': 'damaged_anvil_top',
    'beacon': 'beacon', 'bedrock': 'bedrock', 'bookshelf': 'bookshelf',
    'brewing_stand': 'brewing_stand', 'brewing_stand_base': 'brewing_stand_base',
    'brick': 'bricks', 'cactus_bottom': 'cactus_bottom', 'cactus_side': 'cactus_side',
    'cactus_top': 'cactus_top', 'cake_bottom': 'cake_bottom', 'cake_inner': 'cake_inner',
    'cake_side': 'cake_side', 'cake_top': 'cake_top',
    'cauldron_bottom': 'cauldron_bottom', 'cauldron_inner': 'cauldron_inner',
    'cauldron_side': 'cauldron_side', 'cauldron_top': 'cauldron_top', 'clay': 'clay',
    'coal_block': 'coal_block', 'coal_ore': 'coal_ore', 'coarse_dirt': 'coarse_dirt',
    'cobblestone': 'cobblestone', 'cobblestone_mossy': 'mossy_cobblestone',
    'command_block': ['command_block_back', 'command_block_side', 'command_block_front',
                      'command_block_conditional'],
    'comparator_off': 'comparator', 'comparator_on': 'comparator_on',
    'crafting_table_front': 'crafting_table_front', 'crafting_table_side': 'crafting_table_side',
    'crafting_table_top': 'crafting_table_top',
    'daylight_detector_inverted_top': 'daylight_detector_inverted_top',
    'daylight_detector_side': 'daylight_detector_side', 'daylight_detector_top': 'daylight_detector_top',
    'deadbush': 'dead_bush', 'diamond_block': 'diamond_block', 'diamond_ore': 'diamond_ore',
    'dirt': 'dirt', 'dirt_podzol_side': 'podzol_side', 'dirt_podzol_top': 'podzol_top',
    'dispenser_front_horizontal': 'dispenser_front', 'dispenser_front_vertical': 'dispenser_front_vertical',
    'door_wood_lower': 'oak_door_bottom', 'door_wood_upper': 'oak_door_top',
    'door_iron_lower': 'iron_door_bottom', 'door_iron_upper': 'iron_door_top',
    'double_plant_fern_bottom': 'large_fern_bottom', 'double_plant_fern_top': 'large_fern_top',
    'double_plant_grass_bottom': 'tall_grass_bottom', 'double_plant_grass_top': 'tall_grass_top',
    'double_plant_paeonia_bottom': 'peony_bottom', 'double_plant_paeonia_top': 'peony_top',
    'double_plant_rose_bottom': 'rose_bush_bottom', 'double_plant_rose_top': 'rose_bush_top',
    'double_plant_sunflower_back': 'sunflower_back', 'double_plant_sunflower_bottom': 'sunflower_bottom',
    'double_plant_sunflower_front': 'sunflower_front', 'double_plant_sunflower_top': 'sunflower_top',
    'double_plant_syringa_bottom': 'lilac_bottom', 'double_plant_syringa_top': 'lilac_top',
    'dragon_egg': 'dragon_egg', 'dropper_front_horizontal': 'dropper_front',
    'dropper_front_vertical': 'dropper_front_vertical', 'emerald_block': 'emerald_block',
    'emerald_ore': 'emerald_ore', 'enchanting_table_bottom': 'enchanting_table_bottom',
    'enchanting_table_side': 'enchanting_table_side', 'enchanting_table_top': 'enchanting_table_top',
    'endframe_eye': 'end_portal_frame_eye', 'endframe_side': 'end_portal_frame_side',
    'endframe_top': 'end_portal_frame_top', 'end_stone': 'end_stone',
    'farmland_dry': 'farmland', 'farmland_wet': 'farmland_moist', 'fern': 'fern',
    'fire_layer_0': 'fire_0', 'fire_layer_1': 'fire_1',
    'flower_allium': 'allium', 'flower_blue_orchid': 'blue_orchid', 'flower_dandelion': 'dandelion',
    'flower_houstonia': 'azure_bluet', 'flower_oxeye_daisy': 'oxeye_daisy', 'flower_rose': 'poppy',
    'flower_tulip_orange': 'orange_tulip', 'flower_tulip_pink': 'pink_tulip',
    'flower_tulip_red': 'red_tulip', 'flower_tulip_white': 'white_tulip',
    'flower_pot': 'flower_pot', 'furnace_front_off': 'furnace_front', 'furnace_front_on': 'furnace_front_on',
    'furnace_side': 'furnace_side', 'furnace_top': 'furnace_top', 'glass': 'glass',
    'glass_pane_top': 'glass_pane_top', 'glowstone': 'glowstone', 'gold_block': 'gold_block',
    'gold_ore': 'gold_ore', 'grass_side': 'grass_block_side', 'grass_side_overlay': 'grass_block_side_overlay',
    'grass_side_snowed': 'grass_block_snow', 'grass_top': 'grass_block_top', 'gravel': 'gravel',
    'hardened_clay': 'terracotta', 'hay_block_side': 'hay_block_side', 'hay_block_top': 'hay_block_top',
    'hopper_inside': 'hopper_inside', 'hopper_outside': 'hopper_outside', 'hopper_top': 'hopper_top',
    'ice': 'ice', 'ice_packed': 'packed_ice', 'iron_bars': 'iron_bars', 'iron_block': 'iron_block',
    'iron_ore': 'iron_ore', 'iron_trapdoor': 'iron_trapdoor', 'itemframe_background': 'item_frame',
    'jukebox_side': 'jukebox_side', 'jukebox_top': 'jukebox_top', 'ladder': 'ladder',
    'lapis_block': 'lapis_block', 'lapis_ore': 'lapis_ore', 'lava_flow': 'lava_flow',
    'lava_still': 'lava_still', 'lever': 'lever',
    'melon_side': 'melon_side', 'melon_top': 'melon_top',
    'melon_stem_connected': 'attached_melon_stem', 'melon_stem_disconnected': 'melon_stem',
    'mob_spawner': 'spawner', 'mushroom_block_inside': 'mushroom_block_inside',
    'mushroom_block_skin_brown': 'brown_mushroom_block', 'mushroom_block_skin_red': 'red_mushroom_block',
    'mushroom_block_skin_stem': 'mushroom_stem', 'mushroom_brown': 'brown_mushroom',
    'mushroom_red': 'red_mushroom', 'mycelium_side': 'mycelium_side', 'mycelium_top': 'mycelium_top',
    'netherrack': 'netherrack', 'nether_brick': 'nether_bricks',
    'nether_wart_stage_0': 'nether_wart_stage0', 'nether_wart_stage_1': 'nether_wart_stage1',
    'nether_wart_stage_2': 'nether_wart_stage2', 'noteblock': 'note_block', 'obsidian': 'obsidian',
    'piston_bottom': 'piston_bottom', 'piston_inner': 'piston_inner', 'piston_side': 'piston_side',
    'piston_top_normal': 'piston_top', 'piston_top_sticky': 'piston_top_sticky',
    'portal': 'nether_portal',
    'prismarine_bricks': 'prismarine_bricks', 'prismarine_dark': 'dark_prismarine',
    'prismarine_rough': 'prismarine',
    'pumpkin_face_off': 'carved_pumpkin', 'pumpkin_face_on': 'jack_o_lantern',
    'pumpkin_side': 'pumpkin_side', 'pumpkin_top': 'pumpkin_top',
    'pumpkin_stem_connected': 'attached_pumpkin_stem', 'pumpkin_stem_disconnected': 'pumpkin_stem',
    'quartz_block_bottom': 'quartz_block_bottom', 'quartz_block_chiseled': 'chiseled_quartz_block',
    'quartz_block_chiseled_top': 'chiseled_quartz_block_top', 'quartz_block_lines': 'quartz_pillar_side',
    'quartz_block_lines_top': 'quartz_pillar_top', 'quartz_block_side': 'quartz_block_side',
    'quartz_block_top': 'quartz_block_top', 'quartz_ore': 'nether_quartz_ore',
    'rail_activator': 'activator_rail', 'rail_activator_powered': 'activator_rail_on',
    'rail_detector': 'detector_rail', 'rail_detector_powered': 'detector_rail_on',
    'rail_golden': 'powered_rail', 'rail_golden_powered': 'powered_rail_on',
    'rail_normal': 'rail', 'rail_normal_turned': 'rail_corner',
    'redstone_block': 'redstone_block', 'redstone_lamp_off': 'redstone_lamp',
    'redstone_lamp_on': 'redstone_lamp_on', 'redstone_ore': 'redstone_ore',
    'redstone_torch_off': 'redstone_torch_off', 'redstone_torch_on': 'redstone_torch',
    'red_sand': 'red_sand', 'red_sandstone_bottom': 'red_sandstone_bottom',
    'red_sandstone_carved': 'chiseled_red_sandstone', 'red_sandstone_normal': 'red_sandstone',
    'red_sandstone_smooth': 'cut_red_sandstone', 'red_sandstone_top': 'red_sandstone_top',
    'reeds': 'sugar_cane', 'repeater_off': 'repeater', 'repeater_on': 'repeater_on', 'sand': 'sand',
    'sandstone_bottom': 'sandstone_bottom', 'sandstone_carved': 'chiseled_sandstone',
    'sandstone_normal': 'sandstone', 'sandstone_smooth': 'cut_sandstone', 'sandstone_top': 'sandstone_top',
    'sea_lantern': 'sea_lantern', 'slime': 'slime_block', 'snow': 'snow', 'soul_sand': 'soul_sand',
    'stone': 'stone', 'stone_andesite': 'andesite', 'stone_andesite_smooth': 'polished_andesite',
    'stone_diorite': 'diorite', 'stone_diorite_smooth': 'polished_diorite',
    'stone_granite': 'granite', 'stone_granite_smooth': 'polished_granite',
    'stone_slab_side': 'smooth_stone_slab_side', 'stone_slab_top': 'smooth_stone',
    'stonebrick': 'stone_bricks', 'stonebrick_carved': 'chiseled_stone_bricks',
    'stonebrick_cracked': 'cracked_stone_bricks', 'stonebrick_mossy': 'mossy_stone_bricks',
    'tallgrass': 'short_grass', 'tnt_bottom': 'tnt_bottom', 'tnt_side': 'tnt_side', 'tnt_top': 'tnt_top',
    'torch_on': 'torch', 'trapdoor': 'oak_trapdoor', 'trip_wire': 'tripwire',
    'trip_wire_source': 'tripwire_hook', 'vine': 'vine', 'water_flow': 'water_flow',
    'water_still': 'water_still', 'waterlily': 'lily_pad', 'web': 'cobweb',
    # 26.3 builds beds from block models again, so the 1.8 faces fit (red was the only colour then).
    'bed_head_top': 'red_bed_head_up', 'bed_feet_top': 'red_bed_foot_up',
    'bed_head_side': ['red_bed_head_east', 'red_bed_head_west'],
    'bed_feet_side': ['red_bed_foot_east', 'red_bed_foot_west'],
    'bed_head_end': 'bed_head_north', 'bed_feet_end': 'red_bed_foot_south',
    'redstone_dust_cross': 'redstone_dust_dot',
    'redstone_dust_line': ['redstone_dust_line0', 'redstone_dust_line1'],
}
for _i in range(10):
    BLOCKS['destroy_stage_%d' % _i] = 'destroy_stage_%d' % _i
for _i in range(8):
    BLOCKS['wheat_stage_%d' % _i] = 'wheat_stage%d' % _i
for _i in range(4):
    BLOCKS['carrots_stage_%d' % _i] = 'carrots_stage%d' % _i
    BLOCKS['potatoes_stage_%d' % _i] = 'potatoes_stage%d' % _i
for _i in range(3):
    BLOCKS['cocoa_stage_%d' % _i] = 'cocoa_stage%d' % _i
for _c in COLORS:
    _m = 'light_gray' if _c == 'silver' else _c
    BLOCKS['wool_colored_' + _c] = _m + '_wool'
    BLOCKS['glass_' + _c] = _m + '_stained_glass'
    BLOCKS['glass_pane_top_' + _c] = _m + '_stained_glass_pane_top'
    BLOCKS['hardened_clay_stained_' + _c] = _m + '_terracotta'
for _old, _new in WOODS.items():
    BLOCKS['log_' + _old] = _new + '_log'
    BLOCKS['log_' + _old + '_top'] = _new + '_log_top'
    BLOCKS['planks_' + _old] = _new + '_planks'
    BLOCKS['leaves_' + _old] = _new + '_leaves'
    BLOCKS['sapling_' + ('roofed_oak' if _old == 'big_oak' else _old)] = _new + '_sapling'
    if _old != 'oak':
        _door = 'dark_oak' if _old == 'big_oak' else _old
        BLOCKS['door_%s_lower' % _door] = _new + '_door_bottom'
        BLOCKS['door_%s_upper' % _door] = _new + '_door_top'

# textures/items/<old>.png -> textures/item/<new>.png
ITEMS = {
    'apple': 'apple', 'apple_golden': 'golden_apple', 'arrow': 'arrow',
    'beef_cooked': 'cooked_beef', 'beef_raw': 'beef', 'porkchop_cooked': 'cooked_porkchop',
    'porkchop_raw': 'porkchop', 'chicken_cooked': 'cooked_chicken', 'chicken_raw': 'chicken',
    'bow_standby': 'bow', 'bow_pulling_0': 'bow_pulling_0',
    'bow_pulling_1': 'bow_pulling_1', 'bow_pulling_2': 'bow_pulling_2',
    'bucket_empty': 'bucket', 'bucket_lava': 'lava_bucket', 'bucket_milk': 'milk_bucket',
    'bucket_water': 'water_bucket', 'fishing_rod_cast': 'fishing_rod_cast', 'fishing_rod_uncast': 'fishing_rod',
    'potion_bottle_drinkable': 'potion', 'potion_bottle_splash': 'splash_potion',
    'potion_overlay': 'potion_overlay', 'potion_bottle_empty': 'glass_bottle',
    'ender_pearl': 'ender_pearl', 'snowball': 'snowball', 'egg': 'egg', 'stick': 'stick',
    'book_enchanted': 'enchanted_book', 'experience_bottle': 'experience_bottle',
    'flint_and_steel': 'flint_and_steel', 'bread': 'bread', 'carrot_golden': 'golden_carrot',
}
for _mat, _new in (('wood', 'wooden'), ('stone', 'stone'), ('iron', 'iron'), ('gold', 'golden'),
                   ('diamond', 'diamond')):
    for _tool in ('sword', 'pickaxe', 'axe', 'shovel', 'hoe'):
        ITEMS['%s_%s' % (_mat, _tool)] = '%s_%s' % (_new, _tool)
for _mat, _new in (('leather', 'leather'), ('chainmail', 'chainmail'), ('iron', 'iron'),
                   ('gold', 'golden'), ('diamond', 'diamond')):
    for _piece in ('helmet', 'chestplate', 'leggings', 'boots'):
        ITEMS['%s_%s' % (_mat, _piece)] = '%s_%s' % (_new, _piece)

# models/armor/<mat>_layer_N.png -> entity/equipment/...
ARMOR = {'leather': 'leather', 'chainmail': 'chainmail', 'iron': 'iron', 'gold': 'gold', 'diamond': 'diamond'}

# 1.8 gui/icons.png (256 base) -> 26.3 sprites. (x, y, w, h)
ICONS = {
    'hud/crosshair': (0, 0, 15, 15),
    'hud/heart/container': (16, 0, 9, 9), 'hud/heart/container_blinking': (25, 0, 9, 9),
    'hud/heart/full': (52, 0, 9, 9), 'hud/heart/half': (61, 0, 9, 9),
    'hud/heart/full_blinking': (70, 0, 9, 9), 'hud/heart/half_blinking': (79, 0, 9, 9),
    'hud/heart/poisoned_full': (88, 0, 9, 9), 'hud/heart/poisoned_half': (97, 0, 9, 9),
    'hud/heart/poisoned_full_blinking': (106, 0, 9, 9), 'hud/heart/poisoned_half_blinking': (115, 0, 9, 9),
    'hud/heart/withered_full': (124, 0, 9, 9), 'hud/heart/withered_half': (133, 0, 9, 9),
    'hud/heart/withered_full_blinking': (142, 0, 9, 9), 'hud/heart/withered_half_blinking': (151, 0, 9, 9),
    'hud/heart/absorbing_full': (160, 0, 9, 9), 'hud/heart/absorbing_half': (169, 0, 9, 9),
    'hud/heart/absorbing_full_blinking': (160, 0, 9, 9), 'hud/heart/absorbing_half_blinking': (169, 0, 9, 9),
    'hud/heart/container_hardcore': (16, 45, 9, 9), 'hud/heart/container_hardcore_blinking': (25, 45, 9, 9),
    'hud/heart/hardcore_full': (52, 45, 9, 9), 'hud/heart/hardcore_half': (61, 45, 9, 9),
    'hud/heart/hardcore_full_blinking': (70, 45, 9, 9), 'hud/heart/hardcore_half_blinking': (79, 45, 9, 9),
    'hud/heart/poisoned_hardcore_full': (88, 45, 9, 9), 'hud/heart/poisoned_hardcore_half': (97, 45, 9, 9),
    'hud/heart/poisoned_hardcore_full_blinking': (106, 45, 9, 9),
    'hud/heart/poisoned_hardcore_half_blinking': (115, 45, 9, 9),
    'hud/heart/withered_hardcore_full': (124, 45, 9, 9), 'hud/heart/withered_hardcore_half': (133, 45, 9, 9),
    'hud/heart/withered_hardcore_full_blinking': (142, 45, 9, 9),
    'hud/heart/withered_hardcore_half_blinking': (151, 45, 9, 9),
    'hud/heart/absorbing_hardcore_full': (160, 45, 9, 9), 'hud/heart/absorbing_hardcore_half': (169, 45, 9, 9),
    'hud/heart/absorbing_hardcore_full_blinking': (160, 45, 9, 9),
    'hud/heart/absorbing_hardcore_half_blinking': (169, 45, 9, 9),
    'hud/heart/vehicle_container': (52, 9, 9, 9), 'hud/heart/vehicle_full': (88, 9, 9, 9),
    'hud/heart/vehicle_half': (97, 9, 9, 9),
    'hud/armor_empty': (16, 9, 9, 9), 'hud/armor_half': (25, 9, 9, 9), 'hud/armor_full': (34, 9, 9, 9),
    'hud/air': (16, 18, 9, 9), 'hud/air_bursting': (25, 18, 9, 9),
    'hud/food_empty': (16, 27, 9, 9), 'hud/food_empty_hunger': (133, 27, 9, 9),
    'hud/food_full': (52, 27, 9, 9), 'hud/food_half': (61, 27, 9, 9),
    'hud/food_full_hunger': (88, 27, 9, 9), 'hud/food_half_hunger': (97, 27, 9, 9),
    'hud/experience_bar_background': (0, 64, 182, 5), 'hud/experience_bar_progress': (0, 69, 182, 5),
    'boss_bar/pink_background': (0, 74, 182, 5), 'boss_bar/pink_progress': (0, 79, 182, 5),
    'hud/jump_bar_background': (0, 84, 182, 5), 'hud/jump_bar_progress': (0, 89, 182, 5),
    'icon/ping_5': (0, 176, 10, 8), 'icon/ping_4': (0, 184, 10, 8), 'icon/ping_3': (0, 192, 10, 8),
    'icon/ping_2': (0, 200, 10, 8), 'icon/ping_1': (0, 208, 10, 8), 'icon/ping_unknown': (0, 216, 10, 8),
    'server_list/ping_5': (0, 176, 10, 8), 'server_list/ping_4': (0, 184, 10, 8),
    'server_list/ping_3': (0, 192, 10, 8), 'server_list/ping_2': (0, 200, 10, 8),
    'server_list/ping_1': (0, 208, 10, 8), 'server_list/unreachable': (0, 216, 10, 8),
    'server_list/pinging_1': (10, 176, 10, 8), 'server_list/pinging_2': (10, 184, 10, 8),
    'server_list/pinging_3': (10, 192, 10, 8), 'server_list/pinging_4': (10, 200, 10, 8),
    'server_list/pinging_5': (10, 208, 10, 8),
}

# 1.8 gui/widgets.png (256 base)
WIDGETS = {
    'hud/hotbar': (0, 0, 182, 22), 'hud/hotbar_selection': (0, 22, 24, 23),
    'widget/button_disabled': (0, 46, 200, 20), 'widget/button': (0, 66, 200, 20),
    'widget/button_highlighted': (0, 86, 200, 20),
    'widget/slider': (0, 46, 200, 20), 'widget/slider_highlighted': (0, 46, 200, 20),
}

# Container side sprites: (source texture, x, y, w, h) in the 256 base
CONTAINER_SPRITES = {
    'container/furnace/lit_progress': ('furnace', 176, 0, 14, 14),
    'container/furnace/burn_progress': ('furnace', 176, 14, 24, 16),
    'container/brewing_stand/brew_progress': ('brewing_stand', 176, 0, 9, 28),
    'container/brewing_stand/bubbles': ('brewing_stand', 185, 0, 12, 29),
    'container/anvil/error': ('anvil', 176, 0, 28, 21),
    'container/anvil/text_field': ('anvil', 0, 166, 110, 16),
    'container/anvil/text_field_disabled': ('anvil', 0, 182, 110, 16),
    'container/beacon/button': ('beacon', 0, 219, 22, 22),
    'container/beacon/button_selected': ('beacon', 22, 219, 22, 22),
    'container/beacon/button_disabled': ('beacon', 44, 219, 22, 22),
    'container/beacon/button_highlighted': ('beacon', 66, 219, 22, 22),
    'container/beacon/confirm': ('beacon', 90, 220, 18, 18),
    'container/beacon/cancel': ('beacon', 112, 220, 18, 18),
    'container/enchanting_table/enchantment_slot': ('enchanting_table', 0, 166, 108, 19),
    'container/enchanting_table/enchantment_slot_disabled': ('enchanting_table', 0, 185, 108, 19),
    'container/enchanting_table/enchantment_slot_highlighted': ('enchanting_table', 0, 204, 108, 19),
    'container/enchanting_table/level_1': ('enchanting_table', 0, 223, 16, 16),
    'container/enchanting_table/level_2': ('enchanting_table', 16, 223, 16, 16),
    'container/enchanting_table/level_3': ('enchanting_table', 32, 223, 16, 16),
    'container/enchanting_table/level_1_disabled': ('enchanting_table', 0, 239, 16, 16),
    'container/enchanting_table/level_2_disabled': ('enchanting_table', 16, 239, 16, 16),
    'container/enchanting_table/level_3_disabled': ('enchanting_table', 32, 239, 16, 16),
    'container/horse/chest_slots': ('horse', 0, 166, 90, 54),
    'container/slot': ('horse', 0, 220, 18, 18),
    'container/creative_inventory/scroller': ('creative_inventory/tabs', 232, 0, 12, 15),
    'container/creative_inventory/scroller_disabled': ('creative_inventory/tabs', 244, 0, 12, 15),
    'statistics/header': ('stats_icons', 0, 18, 18, 18),
    'statistics/item_crafted': ('stats_icons', 18, 18, 18, 18),
    'statistics/item_used': ('stats_icons', 36, 18, 18, 18),
    'statistics/block_mined': ('stats_icons', 54, 18, 18, 18),
    'statistics/item_broken': ('stats_icons', 72, 18, 18, 18),
    'statistics/sort_down': ('stats_icons', 18, 0, 18, 18),
    'statistics/sort_up': ('stats_icons', 36, 0, 18, 18),
}
CONTAINER_BASE = {'stats_icons': 128}

# 1.8 status effect icons: row/column in the 18px grid at y=198 of gui/container/inventory.png
EFFECTS = {
    'speed': (0, 0), 'slowness': (1, 0), 'haste': (2, 0), 'mining_fatigue': (3, 0), 'strength': (4, 0),
    'weakness': (5, 0), 'poison': (6, 0), 'regeneration': (7, 0), 'invisibility': (0, 1), 'hunger': (1, 1),
    'jump_boost': (2, 1), 'nausea': (3, 1), 'night_vision': (4, 1), 'blindness': (5, 1),
    'resistance': (6, 1), 'fire_resistance': (7, 1), 'water_breathing': (0, 2), 'wither': (1, 2),
    'absorption': (2, 2), 'health_boost': (2, 2),
}

# 1.8 particle/particles.png cells (128 base, 8px cells) -> textures/particle/<name>.png
PARTICLES = {'bubble': (0, 16), 'flame': (0, 24), 'lava': (8, 24), 'note': (0, 32),
             'critical_hit': (8, 32), 'enchanted_hit': (16, 32), 'heart': (0, 40), 'angry': (8, 40),
             'glint': (16, 40), 'drip_hang': (0, 56), 'drip_fall': (8, 56), 'drip_land': (16, 56)}
for _i in range(8):
    PARTICLES['generic_%d' % _i] = (8 * _i, 0)
    PARTICLES['effect_%d' % _i] = (8 * _i, 64)
    PARTICLES['spell_%d' % _i] = (8 * _i, 72)
    PARTICLES['spark_%d' % _i] = (8 * _i, 80)
for _i in range(4):
    PARTICLES['splash_%d' % _i] = (24 + 8 * _i, 8)
for _i, _ch in enumerate('abcdefghijklmnopqrstuvwxyz'):
    PARTICLES['sga_' + _ch] = (8 * ((_i + 1) % 16), 112 + 8 * ((_i + 1) // 16))

# MCPatcher potion names -> modern effect ids (OptiFine color.properties)
POTION_KEYS = {
    'moveSpeed': 'speed', 'moveSlowdown': 'slowness', 'digSpeed': 'haste', 'digSlowDown': 'mining_fatigue',
    'damageBoost': 'strength', 'heal': 'instant_health', 'harm': 'instant_damage', 'jump': 'jump_boost',
    'confusion': 'nausea', 'regeneration': 'regeneration', 'resistance': 'resistance',
    'fireResistance': 'fire_resistance', 'waterBreathing': 'water_breathing', 'invisibility': 'invisibility',
    'blindness': 'blindness', 'nightVision': 'night_vision', 'hunger': 'hunger', 'weakness': 'weakness',
    'poison': 'poison', 'wither': 'wither', 'healthBoost': 'health_boost', 'absorption': 'absorption',
    'saturation': 'saturation', 'water': 'water',
}


class Source:
    """The old pack, as a folder or a zip, with its root at the folder holding pack.mcmeta."""

    def __init__(self, path):
        self.files = {}
        if os.path.isdir(path):
            for root, _, names in os.walk(path):
                for n in names:
                    full = os.path.join(root, n)
                    with open(full, 'rb') as f:
                        self.files[os.path.relpath(full, path).replace(os.sep, '/')] = f.read()
        else:
            with zipfile.ZipFile(path) as z:
                for n in z.namelist():
                    if not n.endswith('/'):
                        self.files[n] = z.read(n)
        roots = [n[:-len('pack.mcmeta')] for n in self.files if n.endswith('pack.mcmeta')]
        if not roots:
            sys.exit('no pack.mcmeta in ' + path)
        root = min(roots, key=len)
        self.files = {n[len(root):]: d for n, d in self.files.items() if n.startswith(root)}
        self.used = set()

    def get(self, rel):
        if rel in self.files:
            self.used.add(rel)
            return self.files[rel]
        return None

    def image(self, rel):
        data = self.get(rel)
        return Image.open(io.BytesIO(data)).convert('RGBA') if data is not None else None


class Vanilla:
    def __init__(self, jar):
        self.z = zipfile.ZipFile(jar)
        self.names = set(self.z.namelist())
        version = json.loads(self.z.read('version.json'))
        self.pack_format = version['pack_version']['resource_major']

    def has(self, rel):
        return rel in self.names

    def json(self, rel):
        return json.loads(self.z.read(rel)) if rel in self.names else None


class Output:
    def __init__(self, vanilla):
        self.files = {}
        self.vanilla = vanilla
        self.unknown = []

    def put(self, rel, data):
        if rel.startswith('assets/minecraft/') and not rel.startswith('assets/minecraft/optifine/') \
                and not self.vanilla.has(rel) and not rel.endswith('.mcmeta'):
            self.unknown.append(rel)
        self.files[rel] = data

    def has(self, rel):
        return rel in self.files

    def image(self, rel, im):
        buf = io.BytesIO()
        im.save(buf, 'PNG', optimize=True)
        self.put(rel, buf.getvalue())

    def json(self, rel, obj):
        self.put(rel, (json.dumps(obj, indent=2, ensure_ascii=False) + '\n').encode('utf-8'))


def is_blank(im):
    return im.getchannel('A').getextrema()[1] == 0


def scale_of(im, base):
    return im.width / base


def crop(im, s, x, y, w, h):
    return im.crop((round(x * s), round(y * s), round((x + w) * s), round((y + h) * s)))


def texture_meta(vanilla, target, pack_meta):
    """Pack animation wins; vanilla's non-animation texture settings (mipmap strategy, cutoff) are kept."""
    van = vanilla.json(target + '.mcmeta') or {}
    meta = {k: v for k, v in van.items() if k != 'animation'}
    if pack_meta:
        meta.update(pack_meta)
    return meta or None


def put_texture(src, out, vanilla, old_rel, target, im=None, transform=None):
    if im is None:
        im = src.image(old_rel)
    if transform:
        im = transform(im)
    out.image(target, im)
    pack_meta = src.get(old_rel + '.mcmeta')
    pack_meta = json.loads(pack_meta) if pack_meta else None
    meta = texture_meta(vanilla, target, pack_meta)
    if meta:
        out.json(target + '.mcmeta', meta)


def put_sprite(out, vanilla, name, im):
    """A GUI sprite; empty crops are skipped so the vanilla sprite stays."""
    if im is None or is_blank(im):
        return False
    rel = SPRITES + name + '.png'
    out.image(rel, im)
    meta = vanilla.json(rel + '.mcmeta')
    if meta and 'animation' not in meta:
        out.json(rel + '.mcmeta', meta)
    return True


def to_grey_water(im):
    """1.8 water is blue and untinted; since 1.13 the texture is grey and the biome tints it."""
    a = np.asarray(im).astype(np.float64)
    rgb = a[..., :3]
    lum = rgb @ np.array([0.299, 0.587, 0.114])
    mean = lum[a[..., 3] > 0].mean() if (a[..., 3] > 0).any() else 1
    grey = np.clip(lum / max(mean, 1) * 180.0, 0, 255)
    out = np.dstack([grey, grey, grey, a[..., 3]]).astype(np.uint8)
    return Image.fromarray(out, 'RGBA')


def move_region(im, s, box, dx, dy, background):
    """Move box=(x0, y0, x1, y1) by (dx, dy), filling the gap with the window colour found at background."""
    x0, y0, x1, y1 = [round(v * s) for v in box]
    piece = im.crop((x0, y0, x1, y1))
    fill = im.getpixel((round(background[0] * s), round(background[1] * s)))
    im.paste(Image.new('RGBA', piece.size, fill), (x0, y0))
    im.paste(piece, (x0 + round(dx * s), y0 + round(dy * s)))


def copy_region(im, s, box, to):
    x0, y0, x1, y1 = [round(v * s) for v in box]
    im.paste(im.crop((x0, y0, x1, y1)), (round(to[0] * s), round(to[1] * s)))


def fit_inventory(im):
    """1.9 added the off-hand slot and 1.12 moved the crafting grid up and right."""
    s = scale_of(im, 256)
    move_region(im, s, (86, 24, 162, 63), 10, -8, (168, 70))
    copy_region(im, s, (7, 61, 25, 79), (76, 61))
    return im


def fit_creative_inventory(im):
    """The survival tab of the creative screen gained an off-hand slot and moved the armour over."""
    s = scale_of(im, 256)
    move_region(im, s, (7, 4, 81, 51), 45, 0, (150, 20))
    copy_region(im, s, (52, 4, 70, 22), (34, 19))
    return im


def fit_brewing_stand(im):
    """1.9 added the blaze powder slot at the left."""
    s = scale_of(im, 256)
    copy_region(im, s, (78, 16, 96, 34), (16, 16))
    return im


def creative_tab(tabs, s, col, row):
    """1.8 tabs are 28 wide, 26.3 tabs 26: drop two middle columns so both edges survive."""
    x = (0, 28, 56, 84, 112, 112, 140)[col]
    t = crop(tabs, s, x, row * 32, 28, 32)
    left = t.crop((0, 0, round(13 * s), t.height))
    right = t.crop((round(15 * s), 0, t.width, t.height))
    out = Image.new('RGBA', (left.width + right.width, t.height))
    out.paste(left, (0, 0))
    out.paste(right, (left.width, 0))
    return out


def convert_gui(src, out, vanilla):
    g = 'assets/minecraft/textures/gui/'

    icons = src.image(g + 'icons.png')
    if icons:
        s = scale_of(icons, 256)
        for name, box in ICONS.items():
            put_sprite(out, vanilla, name, crop(icons, s, *box))

    widgets = src.image(g + 'widgets.png')
    if widgets:
        s = scale_of(widgets, 256)
        for name, box in WIDGETS.items():
            put_sprite(out, vanilla, name, crop(widgets, s, *box))
        for name, y in (('widget/slider_handle', 66), ('widget/slider_handle_highlighted', 86)):
            left, right = crop(widgets, s, 0, y, 4, 20), crop(widgets, s, 196, y, 4, 20)
            handle = Image.new('RGBA', (left.width * 2, left.height))
            handle.paste(left, (0, 0))
            handle.paste(right, (left.width, 0))
            put_sprite(out, vanilla, name, handle)
        # The off-hand slot is a single hotbar cell with both edges, one pixel down.
        hotbar = crop(widgets, s, 0, 0, 182, 22)
        cell = Image.new('RGBA', (round(22 * s), round(22 * s)))
        cell.paste(hotbar.crop((0, 0, round(21 * s), hotbar.height)), (0, 0))
        cell.paste(hotbar.crop((round(181 * s), 0, hotbar.width, hotbar.height)), (round(21 * s), 0))
        if not is_blank(cell):
            for name, x in (('hud/hotbar_offhand_left', 0), ('hud/hotbar_offhand_right', 7)):
                off = Image.new('RGBA', (round(29 * s), round(24 * s)))
                off.paste(cell, (round(x * s), round(1 * s)))
                put_sprite(out, vanilla, name, off)

    containers = {}
    for name in ('anvil', 'beacon', 'brewing_stand', 'crafting_table', 'dispenser', 'enchanting_table',
                 'furnace', 'generic_54', 'hopper', 'horse', 'inventory', 'stats_icons',
                 'creative_inventory/tabs', 'creative_inventory/tab_items',
                 'creative_inventory/tab_item_search', 'creative_inventory/tab_inventory'):
        im = src.image(g + 'container/' + name + '.png')
        if im is not None:
            containers[name] = im

    for name, (tex, x, y, w, h) in CONTAINER_SPRITES.items():
        im = containers.get(tex)
        if im is not None:
            put_sprite(out, vanilla, name, crop(im, scale_of(im, CONTAINER_BASE.get(tex, 256)), x, y, w, h))

    inv = containers.get('inventory')
    if inv is not None:
        s = scale_of(inv, 256)
        # 26.3 draws the horse's saddle and armour slots as a sprite; fall back to a hotbar slot.
        if not out.has(SPRITES + 'container/slot.png'):
            put_sprite(out, vanilla, 'container/slot', crop(inv, s, 7, 141, 18, 18))
        for effect, (col, row) in EFFECTS.items():
            icon = crop(inv, s, col * 18, 198 + row * 18, 18, 18)
            if not is_blank(icon):
                out.image(TEX + 'mob_effect/' + effect + '.png', icon)
        bg = crop(inv, s, 0, 166, 140, 32)
        if not is_blank(bg):
            # Packs often draw the box narrower than 140; the right edge is wherever the art ends.
            bg = bg.crop((0, 0, bg.getchannel('A').getbbox()[2], bg.height))
            box = Image.new('RGBA', (round(32 * s), round(32 * s)))
            box.paste(bg.crop((0, 0, round(16 * s), bg.height)), (0, 0))
            box.paste(bg.crop((bg.width - round(16 * s), 0, bg.width, bg.height)), (round(16 * s), 0))
            put_sprite(out, vanilla, 'container/inventory/effect_background', box)
            put_sprite(out, vanilla, 'container/inventory/effect_background_ambient', box)

    tabs = containers.get('creative_inventory/tabs')
    if tabs is not None:
        s = scale_of(tabs, 256)
        for row, kind in enumerate(('top_unselected', 'top_selected', 'bottom_unselected', 'bottom_selected')):
            for col in range(7):
                put_sprite(out, vanilla, 'container/creative_inventory/tab_%s_%d' % (kind, col + 1),
                           creative_tab(tabs, s, col, row))

    fits = {'inventory': fit_inventory, 'brewing_stand': fit_brewing_stand,
            'creative_inventory/tab_inventory': fit_creative_inventory}
    for name, im in containers.items():
        if name in ('stats_icons', 'creative_inventory/tabs'):
            continue
        if name in fits:
            im = fits[name](im.copy())
        out.image(TEX + 'gui/container/' + name + '.png', im)


def convert_particles(src, out):
    im = src.image(TEX + 'particle/particles.png')
    if im is None:
        return
    s = scale_of(im, 128)
    for name, (x, y) in PARTICLES.items():
        cell = crop(im, s, x, y, 8, 8)
        if not is_blank(cell):
            out.image(TEX + 'particle/' + name + '.png', cell)
    flash = crop(im, s, 32, 16, 32, 32)
    if not is_blank(flash):
        out.image(TEX + 'particle/flash.png', flash)


def convert_blocks_items(src, out, vanilla):
    for folder, table, new_folder in (('blocks', BLOCKS, 'block'), ('items', ITEMS, 'item')):
        prefix = TEX + folder + '/'
        for rel in sorted(src.files):
            if not rel.startswith(prefix) or not rel.endswith('.png') or '/' in rel[len(prefix):]:
                continue
            old = rel[len(prefix):-4]
            if old in ('redstone_dust_line_overlay', 'redstone_dust_cross_overlay'):
                continue
            if old not in table:
                continue
            targets = table[old] if isinstance(table[old], list) else [table[old]]
            for target in targets:
                transform = None
                if old in ('water_still', 'water_flow'):
                    transform = to_grey_water
                elif old in ('bed_head_top', 'bed_feet_top'):
                    transform = lambda im: im.rotate(90)
                elif target.endswith('_west') and old.startswith('bed_'):
                    transform = lambda im: im.transpose(Image.FLIP_LEFT_RIGHT)
                elif old == 'redstone_dust_line':
                    transform = lambda im: im.rotate(90)
                put_texture(src, out, vanilla, rel, TEX + new_folder + '/' + target + '.png',
                            transform=transform)
    overlay = src.image(TEX + 'blocks/redstone_dust_line_overlay.png')
    src.get(TEX + 'blocks/redstone_dust_cross_overlay.png')
    if overlay is not None:
        put_texture(src, out, vanilla, TEX + 'blocks/redstone_dust_line_overlay.png',
                    TEX + 'block/redstone_dust_overlay.png', im=overlay.rotate(90))


def convert_armor(src, out):
    for old, new in ARMOR.items():
        for layer, folder in (('1', 'humanoid'), ('2', 'humanoid_leggings')):
            base = TEX + 'models/armor/%s_layer_%s' % (old, layer)
            for suffix in ('', '_overlay'):
                data = src.get(base + suffix + '.png')
                if data is not None:
                    out.put(TEX + 'entity/equipment/%s/%s%s.png' % (folder, new, suffix), data)


def convert_font(src, out):
    """OptiFine read the HD font from mcpatcher/font; vanilla reads textures/font."""
    for name in ('ascii', 'ascii_sga'):
        data = src.get('assets/minecraft/mcpatcher/font/%s.png' % name) or src.get(TEX + 'font/%s.png' % name)
        src.get('assets/minecraft/mcpatcher/font/%s.properties' % name)
        if data is not None:
            out.put(TEX + 'font/%s.png' % name, data)


def convert_title(src, out):
    for i in range(6):
        rel = TEX + 'gui/title/background/panorama_%d.png' % i
        data = src.get(rel)
        if data is not None:
            out.put(rel, data)


def convert_optifine(src, out):
    """MCPatcher features need OptiFine or a mod (Continuity, FabricSkyBoxes, Polytone) on 26.3."""
    mp = 'assets/minecraft/mcpatcher/'
    of = 'assets/minecraft/optifine/'
    for rel in sorted(src.files):
        if not rel.startswith(mp):
            continue
        sub = rel[len(mp):]
        if sub.startswith('ctm/') and (sub.endswith('.png') or sub.endswith('.properties')):
            data = src.get(rel)
            if sub.endswith('.properties'):
                props = parse_properties(data.decode('utf-8', 'replace'))
                name = os.path.basename(sub)[:-len('.properties')]
                if 'matchBlocks' not in props and 'matchTiles' not in props:
                    props['matchBlocks'] = name
                data = format_properties(props)
            out.put(of + sub, data)
        elif sub.startswith('sky/') and sub.endswith('.properties'):
            data = src.get(rel)
            props = parse_properties(data.decode('utf-8', 'replace'))
            folder = os.path.dirname(sub)
            source = props.get('source', '')
            if source.startswith('./'):
                tex = mp + folder + '/' + source[2:]
                if src.get(tex) is not None:
                    out.put(of + folder + '/' + source[2:], src.files[tex])
            out.put(of + sub, data)
        elif sub.startswith('colormap/') and sub.endswith('.png'):
            out.put(of + sub, src.get(rel))
        elif sub == 'color.properties':
            props = parse_properties(src.get(rel).decode('utf-8', 'replace'))
            kept = {}
            for k, v in props.items():
                if k.startswith('potion.'):
                    old = k[len('potion.'):]
                    if old in POTION_KEYS:
                        kept['potion.' + POTION_KEYS[old]] = v
                elif not k.startswith('egg.'):
                    kept[k] = v
            out.put(of + 'color.properties', format_properties(kept))


def parse_properties(text):
    props = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line[0] in '#!' or '=' not in line:
            continue
        k, v = line.split('=', 1)
        props[k.strip()] = v.strip()
    return props


def format_properties(props):
    return ''.join('%s=%s\n' % kv for kv in props.items()).encode('utf-8')


def convert(src_path, jar_path, out_path):
    src = Source(src_path)
    vanilla = Vanilla(jar_path)
    out = Output(vanilla)
    report = []

    old_meta = json.loads(src.get('pack.mcmeta').decode('utf-8-sig'))
    description = old_meta.get('pack', {}).get('description', '')
    out.json('pack.mcmeta', {'pack': {'description': description,
                                      'min_format': vanilla.pack_format,
                                      'max_format': vanilla.pack_format}})
    if src.get('pack.png') is not None:
        out.put('pack.png', src.files['pack.png'])

    convert_blocks_items(src, out, vanilla)
    convert_armor(src, out)
    convert_gui(src, out, vanilla)
    convert_particles(src, out)
    convert_font(src, out)
    convert_title(src, out)
    convert_optifine(src, out)

    if out.unknown:
        sys.exit('not read by 26.3: ' + ', '.join(out.unknown))

    junk = re.compile(r'(Thumbs\.db|\.DS_Store|\.pdn)$')
    for rel in sorted(src.files):
        if rel not in src.used and not junk.search(rel):
            report.append('%s: left out' % rel)

    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel in sorted(out.files):
            info = zipfile.ZipInfo(rel, date_time=(2026, 9, 15, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, out.files[rel])
    return out, report


if __name__ == '__main__':
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    result, notes = convert(*sys.argv[1:])
    print('wrote %d files to %s' % (len(result.files), sys.argv[3]))
    for note in notes:
        print('  ' + note)
