package dev.craftium.client;

import java.util.Map;

/** Written by {@link InteractionGameTest} when run with CRAFTIUM_INTERACTION_UPDATE=1: "<blocks changed> <hash> client:<hash of the client trace>" for each scenario. */
final class InteractionExpected {
    private InteractionExpected() {}
    static final Map<String, String> CASES = Map.ofEntries(
        Map.entry("annotation_draw", "0 e3b0c44298fc1c14 client:84b4717c886c8e78"),
        Map.entry("annotation_text", "0 e3b0c44298fc1c14 client:72b7b11121166d7b"),
        Map.entry("box_select_then_delete", "217 6855adc00a877a6c client:b8be6df33eab1fa0"),
        Map.entry("click_biome_painter", "18 4d3ffdfd066347ab client:bf5ab63e9e09e3a1"),
        Map.entry("click_clentaminator", "69 4b3965adca77d4ca client:045218b9ad10ae82"),
        Map.entry("click_distort", "100 533b6b0e51fbf18c client:3071d5b8ebf7b479"),
        Map.entry("click_elevation", "54 565ce77714c2782f client:9e2b7977cb96050b"),
        Map.entry("click_erase", "303 3094dffaaaff9c59 client:825cc326623df329"),
        Map.entry("click_extrude_platform", "50 3f37e4f6bad8f2ff client:81f03b259ab59904"),
        Map.entry("click_flatten", "102 ac232ab0276252a9 client:8071e17ec30798e5"),
        Map.entry("click_floodfill", "200 24a5296619fe879e client:4d578aba892534ec"),
        Map.entry("click_fluid_ball_snow", "25 498418b229bef930 client:2bfbeee8b2b7c926"),
        Map.entry("click_freehand_draw", "389 c2c7cdff1be685b3 client:3c2ef5591782bd45"),
        Map.entry("click_line", "237 e1c930898bd69b92 client:42d2a9c77100a270"),
        Map.entry("click_melt", "18 2556e83bffc729ae client:0b268ae6bcc316bb"),
        Map.entry("click_noise_painter", "79 255cad36df5e2b0c client:c25df09af6895739"),
        Map.entry("click_painter", "79 c653078ef63b82aa client:d70a83eb0d907f26"),
        Map.entry("click_rock", "160 6e7f73ed276cc9bb client:2c57797d22008764"),
        Map.entry("click_roughen", "14 933672983c4f7284 client:cf5990f9793cf8fe"),
        Map.entry("click_script_brush", "126 2c5d09e908e98721 client:d9cb72767462f2cb"),
        Map.entry("click_sculpt_draw", "54 4c3b087839e43052 client:0eacb3018cfa2d6f"),
        Map.entry("click_shatter", "123 5006069c8bfb6289 client:272162757d963e0e"),
        Map.entry("click_smooth", "22 5fcde5b3d6613080 client:e365c1f760a6e7c8"),
        Map.entry("click_stamp", "135 09aefbd5a39c2b5f client:15a0e4716272b8e3"),
        Map.entry("click_text", "46 ebdf08c11b839e7c client:9fca458c14db188d"),
        Map.entry("click_tinker", "1 954256ab78d77f94 client:ce0d84ae6cea1afa"),
        Map.entry("click_weld", "33 fb0243d44565b3b0 client:7a80e942a54ca65f"),
        Map.entry("extrude_large_click_confirmed", "50 3f37e4f6bad8f2ff client:7e289dd1f1eea8f6"),
        Map.entry("floodfill_large_click_confirmed", "200 24a5296619fe879e client:f41c929c6535af29"),
        Map.entry("freehand_select", "0 e3b0c44298fc1c14 client:2c482ed6a50f4133"),
        Map.entry("gradient_shift_anchors_then_paint", "191 3332c6fabfaa9983 client:0c436b5ef6269875"),
        Map.entry("lasso_select", "0 e3b0c44298fc1c14 client:cc5ce22b5dfbbfab"),
        Map.entry("magic_select_then_delete", "1 190519c7522bf592 client:d35a1009e1bbf239"),
        Map.entry("modelling_points_then_enter", "65 fd97a2fc72652935 client:01b2add6eb8b68f9"),
        Map.entry("modify_anchor_then_enter", "18 1466c91c0e26337a client:a916d53ba9eada89"),
        Map.entry("painter_click_then_undo", "0 e3b0c44298fc1c14 client:eb61151a29e84893"),
        Map.entry("painter_stroke", "53 0fc8e34ad7f4b177 client:2ea2b1fbfcce495b"),
        Map.entry("path_points_then_enter", "176 237f4c896f41d0f1 client:def786758b22410b"),
        Map.entry("pivot_click", "0 e3b0c44298fc1c14 client:679e0c6b217c8dc9"),
        Map.entry("ruler_two_points", "0 e3b0c44298fc1c14 client:5d362a12ea0e0e4f"),
        Map.entry("shape_place_then_enter", "179 a6444e45722f629e client:6f39d38aab1792dc"),
        Map.entry("shape_place_then_escape", "0 e3b0c44298fc1c14 client:ccff6ed6c854dcb6"),
        Map.entry("slope_two_clicks", "137 65571690faa01045 client:0eba367e98bda580"),
        Map.entry("smear_clipboard", "55 48e735b6da62e9c3 client:ab6c8b397319821e")
    );
}
