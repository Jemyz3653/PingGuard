#version 330
#extension GL_ARB_separate_shader_objects : require

// PingGuard: vanilla minecraft:core/text.vsh (26.3) + connection-warning card logic.
// Glyphs coloured #FE50xx / #FE51xx are PingGuard card glyphs. The title alpha is used
// as a clock (see PingGuard README / VanillaCard.java):
//   #FE50xx  "armed"  : title fades in over 510 ticks (alpha +1 every 2 ticks). The server
//                        restarts it every 10 ticks, so alpha stays tiny and the card hidden.
//                        If the server goes silent, alpha keeps growing -> card appears.
//   #FE51xx  "forced" : title fades out over 510 ticks from alpha 255; always visible.
//   xx = frame id (0..22) or 255 for always-visible parts (band, text).

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

const int PG_REVEAL_ALPHA = 11;
const int PG_STEPS = 50;
const int PG_TIMELINE[50] = int[](0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 15, 16, 17, 15, 16, 17, 15, 16, 17, 15, 16, 17, 18, 19, 20, 21, 21, 21, 22, 22, 22, 21, 21, 21, 22, 22, 22, 21, 21, 21, 22, 22);

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;

    ivec4 pg = ivec4(round(Color * 255.0));
    if (pg.r == 254 && (pg.g == 80 || pg.g == 81)) {
        bool visible;
        int t;
        if (pg.g == 80) {
            visible = pg.a >= PG_REVEAL_ALPHA;
            t = pg.a - PG_REVEAL_ALPHA;
        } else {
            visible = true;
            t = 255 - pg.a;
        }
        if (pg.b != 255) {
            int step = t - (t / PG_STEPS) * PG_STEPS;
            visible = visible && PG_TIMELINE[max(step, 0)] == pg.b;
        }
        if (visible) {
            vertexColor = vec4(1.0);
        } else {
            vertexColor = vec4(0.0);
            gl_Position = vec4(-4.0, -4.0, 0.0, 1.0);
        }
    }
}
