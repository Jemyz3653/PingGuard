#!/usr/bin/env python3
"""
PingGuard asset generator.

Draws the "check your internet connection" mini video (an Ethernet plug being
plugged into / yanked out of a wall socket), the text band in Orbitron, the
signal icons, and writes everything the mod and the server resource pack need:

  src/pack/...                                   -> resource pack for vanilla clients
  src/client/resources/assets/pingguard/...      -> textures/fonts for Fabric clients
  src/main/java/.../CardLayout.java              -> timeline shared with Java code

Run from the repo root:  python3 tools/gen_assets.py
Needs: pycairo, Pillow, fontTools
"""
import io
import json
import math
import os
import shutil
import sys

import cairo
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_DIR = os.path.join(ROOT, "tools", "fonts")
FONT_BOLD = os.path.join(FONT_DIR, "Orbitron-Bold.ttf")
FONT_BLACK = os.path.join(FONT_DIR, "Orbitron-Black.ttf")

PACK = os.path.join(ROOT, "src", "pack")
MOD_ASSETS = os.path.join(ROOT, "src", "client", "resources", "assets", "pingguard")
JAVA_LAYOUT = os.path.join(ROOT, "src", "main", "java", "dev", "jemyz", "pingguard", "CardLayout.java")
PREVIEW_DIR = os.path.join(ROOT, "build", "preview")

# --------------------------------------------------------------------------------------
# Layout of the card in "title font units" (fu). The vanilla title is drawn at 4x scale,
# so 1 fu = 4 GUI pixels. Everything is integer so glyph advances line up exactly.
# --------------------------------------------------------------------------------------
CARD_W = 44          # fu
SCENE_H = 22         # fu  (scene texture 256x128 -> 44x22 fu)
BAND_H = 8           # fu  (band texture 253x46  -> 44x8  fu)
TEXT_BOX_H = 3       # fu  (text textures 48 px tall -> 3 fu)
TEXT_PX_PER_FU = 16
CARD_TOP = -15       # fu, relative to the screen centre (title line top is at -10)
TITLE_LINE_TOP = -10

SCENE_W_PX, SCENE_H_PX = 256, 128
BAND_W_PX, BAND_H_PX = 253, 46

LINE1 = "CHECK YOUR"
LINE2 = "INTERNET CONNECTION"
LINE1_CHUNKS, LINE1_CHUNK_FU = 2, 12   # 24 fu wide image
LINE2_CHUNKS, LINE2_CHUNK_FU = 3, 14   # 42 fu wide image
CAP_FU = 1.8
TRACKING_EM = 0.06

FPS = 10

# Colours
C_BG_TOP = (0x10, 0x1A, 0x2C)
C_BG_BOT = (0x09, 0x10, 0x1D)
C_ACCENT = (0x4F, 0xE3, 0xFF)
C_GREEN = (0x3D, 0xFF, 0x8F)
C_RED = (0xFF, 0x45, 0x4F)
C_AMBER = (0xFF, 0xB8, 0x2E)
C_CABLE = (0x2F, 0x7B, 0xFF)


def rgb(c, a=1.0):
    return (c[0] / 255.0, c[1] / 255.0, c[2] / 255.0, a)


def ease(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def ease_out(t):
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def lerp(a, b, t):
    return a + (b - a) * t


def round_rect(ctx, x, y, w, h, r, corners=(True, True, True, True)):
    tl, tr, br, bl = corners
    ctx.new_sub_path()
    if tr:
        ctx.arc(x + w - r, y + r, r, -math.pi / 2, 0)
    else:
        ctx.move_to(x + w, y)
    if br:
        ctx.arc(x + w - r, y + h - r, r, 0, math.pi / 2)
    else:
        ctx.line_to(x + w, y + h)
    if bl:
        ctx.arc(x + r, y + h - r, r, math.pi / 2, math.pi)
    else:
        ctx.line_to(x, y + h)
    if tl:
        ctx.arc(x + r, y + r, r, math.pi, 3 * math.pi / 2)
    else:
        ctx.line_to(x, y)
    ctx.close_path()


def glow(ctx, x, y, r, color, alpha):
    g = cairo.RadialGradient(x, y, 0, x, y, r)
    g.add_color_stop_rgba(0, *rgb(color, alpha))
    g.add_color_stop_rgba(1, *rgb(color, 0))
    ctx.set_source(g)
    ctx.arc(x, y, r, 0, 2 * math.pi)
    ctx.fill()


def bezier_point(p0, p1, p2, p3, t):
    u = 1 - t
    return (
        u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
        u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1],
    )


# --------------------------------------------------------------------------------------
# Scene drawing (design space 256 x 128, scaled by `s`)
# --------------------------------------------------------------------------------------
PLATE_X0, PLATE_X1 = 62, 73
JACK_Y = 66
X_INSERTED = 59
X_TOUCH = 76
X_AWAY = 150


def draw_background(ctx):
    g = cairo.LinearGradient(0, 0, 0, SCENE_H_PX)
    g.add_color_stop_rgba(0, *rgb(C_BG_TOP))
    g.add_color_stop_rgba(1, *rgb(C_BG_BOT))
    round_rect(ctx, 0, 0, SCENE_W_PX, SCENE_H_PX + 8, 9, (True, True, False, False))
    ctx.set_source(g)
    ctx.fill_preserve()
    ctx.save()
    ctx.clip()
    # tech grid
    ctx.set_line_width(0.6)
    ctx.set_source_rgba(*rgb(C_ACCENT, 0.07))
    for x in range(8, SCENE_W_PX, 16):
        ctx.move_to(x + 0.5, 0)
        ctx.line_to(x + 0.5, SCENE_H_PX)
    for y in range(8, SCENE_H_PX, 16):
        ctx.move_to(0, y + 0.5)
        ctx.line_to(SCENE_W_PX, y + 0.5)
    ctx.stroke()
    # floor glow
    glow(ctx, 150, 140, 120, C_ACCENT, 0.10)
    ctx.restore()


def draw_wall(ctx, led, led_glow):
    # wall body
    g = cairo.LinearGradient(0, 0, PLATE_X0, 0)
    g.add_color_stop_rgba(0, *rgb((0x17, 0x1F, 0x30)))
    g.add_color_stop_rgba(1, *rgb((0x24, 0x2F, 0x46)))
    ctx.save()
    round_rect(ctx, 0, 0, SCENE_W_PX, SCENE_H_PX + 8, 9, (True, True, False, False))
    ctx.clip()
    ctx.rectangle(0, 0, PLATE_X0, SCENE_H_PX)
    ctx.set_source(g)
    ctx.fill()
    # bricks
    ctx.set_line_width(0.8)
    ctx.set_source_rgba(1, 1, 1, 0.05)
    row = 0
    for y in range(0, SCENE_H_PX, 13):
        ctx.move_to(0, y + 0.5)
        ctx.line_to(PLATE_X0, y + 0.5)
        off = 0 if row % 2 == 0 else 14
        for x in range(off, PLATE_X0, 28):
            ctx.move_to(x + 0.5, y)
            ctx.line_to(x + 0.5, y + 13)
        row += 1
    ctx.stroke()
    ctx.restore()
    # wall face edge
    ctx.set_line_width(2)
    ctx.set_source_rgba(*rgb((0x46, 0x56, 0x74)))
    ctx.move_to(PLATE_X0 - 1, 0)
    ctx.line_to(PLATE_X0 - 1, SCENE_H_PX)
    ctx.stroke()

    # wall plate (side profile, slightly visible face)
    py0, py1 = 30, 102
    g = cairo.LinearGradient(PLATE_X0, 0, PLATE_X1, 0)
    g.add_color_stop_rgba(0, *rgb((0xB9, 0xC4, 0xD3)))
    g.add_color_stop_rgba(0.6, *rgb((0xEE, 0xF2, 0xF7)))
    g.add_color_stop_rgba(1, *rgb((0xD5, 0xDD, 0xE8)))
    round_rect(ctx, PLATE_X0, py0, PLATE_X1 - PLATE_X0, py1 - py0, 3)
    ctx.set_source(g)
    ctx.fill_preserve()
    ctx.set_line_width(1)
    ctx.set_source_rgba(*rgb((0x7F, 0x8D, 0xA3)))
    ctx.stroke()
    # screws
    for sy in (35, 97):
        ctx.arc(PLATE_X0 + 6.5, sy, 1.6, 0, 2 * math.pi)
        ctx.set_source_rgba(*rgb((0x8E, 0x9A, 0xAE)))
        ctx.fill()
    # jack opening
    jy0, jy1 = JACK_Y - 10, JACK_Y + 10
    ctx.rectangle(PLATE_X0 + 3, jy0, PLATE_X1 - PLATE_X0 - 3, jy1 - jy0)
    ctx.set_source_rgba(*rgb((0x1C, 0x22, 0x2E)))
    ctx.fill()
    ctx.rectangle(PLATE_X0 + 4, jy1 - 3, PLATE_X1 - PLATE_X0 - 5, 1.6)
    ctx.set_source_rgba(*rgb((0xE8, 0xB9, 0x3C)))
    ctx.fill()
    # LED
    lx, ly = PLATE_X0 + 6.5, 45
    if led == "green":
        glow(ctx, lx, ly, 12 * led_glow + 6, C_GREEN, 0.55)
        col = C_GREEN
    elif led == "red":
        glow(ctx, lx, ly, 12 * led_glow + 6, C_RED, 0.55)
        col = C_RED
    else:
        col = (0x55, 0x60, 0x72)
    ctx.arc(lx, ly, 2.4, 0, 2 * math.pi)
    ctx.set_source_rgba(*rgb(col))
    ctx.fill()


CABLE_ANCHOR = (290, 150)


def plug_transform(ctx, tip_x, tilt_deg, drop):
    ctx.translate(tip_x, JACK_Y + drop)
    ctx.rotate(math.radians(tilt_deg))


def local_to_world(tip_x, tilt_deg, drop, lx, ly):
    a = math.radians(tilt_deg)
    return (tip_x + lx * math.cos(a) - ly * math.sin(a), JACK_Y + drop + lx * math.sin(a) + ly * math.cos(a))


def cable_curve(tip_x, tilt_deg, drop):
    p0 = local_to_world(tip_x, tilt_deg, drop, 44, 0)
    p1 = local_to_world(tip_x, tilt_deg, drop, 80, 0)
    p2 = (CABLE_ANCHOR[0] - 70, CABLE_ANCHOR[1] - 75)
    p3 = CABLE_ANCHOR
    return p0, p1, p2, p3


def draw_cable(ctx, tip_x, tilt_deg, drop, data_phase):
    p0, p1, p2, p3 = cable_curve(tip_x, tilt_deg, drop)

    def path():
        ctx.move_to(*p0)
        ctx.curve_to(*p1, *p2, *p3)

    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    path()
    ctx.set_line_width(11)
    ctx.set_source_rgba(*rgb((0x14, 0x3A, 0x8A)))
    ctx.stroke()
    path()
    ctx.set_line_width(8.5)
    ctx.set_source_rgba(*rgb(C_CABLE))
    ctx.stroke()
    ctx.save()
    ctx.translate(-1.2, -2.2)
    path()
    ctx.set_line_width(1.6)
    ctx.set_source_rgba(1, 1, 1, 0.35)
    ctx.stroke()
    ctx.restore()

    if data_phase is not None:
        for k in range(5):
            t = 1.0 - ((k / 5.0 + data_phase) % 1.0)
            if t < 0.03 or t > 0.97:
                continue
            x, y = bezier_point(p0, p1, p2, p3, t)
            glow(ctx, x, y, 7, C_ACCENT, 0.6)
            ctx.arc(x, y, 1.9, 0, 2 * math.pi)
            ctx.set_source_rgba(0.85, 1, 1, 1)
            ctx.fill()


def draw_plug(ctx, tip_x, tilt_deg, drop):
    ctx.save()
    plug_transform(ctx, tip_x, tilt_deg, drop)
    # boot (strain relief)
    ctx.move_to(27, -9.5)
    ctx.line_to(45, -6)
    ctx.line_to(45, 6)
    ctx.line_to(27, 9.5)
    ctx.close_path()
    g = cairo.LinearGradient(0, -9, 0, 9)
    g.add_color_stop_rgba(0, *rgb((0x6E, 0xA8, 0xFF)))
    g.add_color_stop_rgba(0.5, *rgb(C_CABLE))
    g.add_color_stop_rgba(1, *rgb((0x1B, 0x4C, 0xB8)))
    ctx.set_source(g)
    ctx.fill_preserve()
    ctx.set_line_width(0.8)
    ctx.set_source_rgba(*rgb((0x12, 0x33, 0x80)))
    ctx.stroke()
    ctx.set_line_width(0.9)
    ctx.set_source_rgba(0, 0, 0, 0.25)
    for rx in (31, 35, 39):
        ctx.move_to(rx, -8)
        ctx.line_to(rx, 8)
    ctx.stroke()

    # latch (clip) - attached near the tip, sloping back and up
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    ctx.move_to(5, -8.5)
    ctx.line_to(23, -14)
    ctx.set_line_width(2.6)
    ctx.set_source_rgba(*rgb((0xB5, 0xD6, 0xF2), 0.95))
    ctx.stroke()
    ctx.move_to(14, -11.2)
    ctx.line_to(14, -8.5)
    ctx.set_line_width(1.6)
    ctx.stroke()

    # clear plastic head with chamfered tip
    ctx.move_to(0, -6.5)
    ctx.line_to(2.5, -9)
    ctx.line_to(28, -9)
    ctx.line_to(28, 9)
    ctx.line_to(2.5, 9)
    ctx.line_to(0, 6.5)
    ctx.close_path()
    g = cairo.LinearGradient(0, -9, 0, 9)
    g.add_color_stop_rgba(0, *rgb((0xE9, 0xF5, 0xFF), 0.95))
    g.add_color_stop_rgba(0.5, *rgb((0xC3, 0xE0, 0xF6), 0.90))
    g.add_color_stop_rgba(1, *rgb((0x9C, 0xC3, 0xE4), 0.95))
    ctx.set_source(g)
    ctx.fill_preserve()
    ctx.set_line_width(0.9)
    ctx.set_source_rgba(*rgb((0x6F, 0x9C, 0xC6)))
    ctx.stroke()
    # coloured wires visible through the plastic
    wires = [(0xFF, 0x8A, 0x3D), (0x3D, 0xDC, 0x84), (0x4D, 0xA3, 0xFF), (0xA0, 0x70, 0x3C)]
    ctx.set_line_width(1.3)
    for i, c in enumerate(wires):
        y = -4.5 + i * 3
        ctx.move_to(9, y)
        ctx.line_to(28, y * 0.8)
        ctx.set_source_rgba(*rgb(c, 0.85))
        ctx.stroke()
    # gold contacts
    ctx.rectangle(1.5, 5.2, 8.5, 3)
    ctx.set_source_rgba(*rgb((0xF2, 0xC2, 0x4B)))
    ctx.fill()
    ctx.set_line_width(0.5)
    ctx.set_source_rgba(*rgb((0x9C, 0x74, 0x10)))
    for cx in (3, 4.6, 6.2, 7.8, 9.4):
        ctx.move_to(cx, 5.2)
        ctx.line_to(cx, 8.2)
    ctx.stroke()
    # highlight
    ctx.move_to(4, -7.3)
    ctx.line_to(26, -7.3)
    ctx.set_line_width(1)
    ctx.set_source_rgba(1, 1, 1, 0.7)
    ctx.stroke()
    ctx.restore()


def draw_click(ctx, amount):
    if amount <= 0:
        return
    cx, cy = PLATE_X1 + 2, JACK_Y
    glow(ctx, cx, cy, 26 * amount + 6, C_ACCENT, 0.45 * amount)
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    ctx.set_line_width(1.8)
    for i in range(7):
        a = math.radians(-75 + i * 25)
        r0, r1 = 13, 13 + 9 * amount
        ctx.move_to(cx + math.cos(a) * r0, cy + math.sin(a) * r0 - 2)
        ctx.line_to(cx + math.cos(a) * r1, cy + math.sin(a) * r1 - 2)
    ctx.set_source_rgba(1, 0.97, 0.75, min(1, amount * 1.2))
    ctx.stroke()


def draw_motion(ctx, tip_x, amount):
    if amount <= 0:
        return
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    for i, (dy, ln) in enumerate([(-7, 1.0), (0, 0.75), (7, 0.9)]):
        x1 = tip_x - 4
        x0 = max(PLATE_X1 + 4, x1 - 46 * amount * ln)
        g = cairo.LinearGradient(x0, 0, x1, 0)
        g.add_color_stop_rgba(0, 1, 1, 1, 0)
        g.add_color_stop_rgba(1, 1, 1, 1, 0.75)
        ctx.set_source(g)
        ctx.set_line_width(1.6)
        ctx.move_to(x0, JACK_Y + dy)
        ctx.line_to(x1, JACK_Y + dy)
        ctx.stroke()


def draw_sparks(ctx, amount):
    if amount <= 0:
        return
    cx, cy = PLATE_X1 + 3, JACK_Y
    pts = [(9, -8), (14, -2), (11, 6), (6, 11), (16, 9), (5, -12)]
    for i, (dx, dy) in enumerate(pts):
        r = 1.5 if i % 2 else 1.1
        ctx.arc(cx + dx * (0.6 + amount), cy + dy * (0.6 + amount), r, 0, 2 * math.pi)
        ctx.set_source_rgba(*rgb(C_AMBER if i % 2 else C_RED, amount))
        ctx.fill()


def draw_x_mark(ctx, amount):
    if amount <= 0:
        return
    cx, cy = 112, 34
    r = 9 + 1.2 * amount
    glow(ctx, cx, cy, r + 10, C_RED, 0.35 * amount)
    ctx.arc(cx, cy, r, 0, 2 * math.pi)
    ctx.set_source_rgba(*rgb((0x2A, 0x0E, 0x14), 0.9))
    ctx.fill_preserve()
    ctx.set_line_width(1.8)
    ctx.set_source_rgba(*rgb(C_RED))
    ctx.stroke()
    d = r * 0.42
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    ctx.set_line_width(2.2)
    ctx.move_to(cx - d, cy - d)
    ctx.line_to(cx + d, cy + d)
    ctx.move_to(cx + d, cy - d)
    ctx.line_to(cx - d, cy + d)
    ctx.stroke()


def draw_check(ctx, amount):
    if amount <= 0:
        return
    cx, cy = 112, 34
    r = 9
    glow(ctx, cx, cy, r + 10, C_GREEN, 0.35 * amount)
    ctx.arc(cx, cy, r, 0, 2 * math.pi)
    ctx.set_source_rgba(*rgb((0x0B, 0x26, 0x1A), 0.9 * amount))
    ctx.fill_preserve()
    ctx.set_line_width(1.8)
    ctx.set_source_rgba(*rgb(C_GREEN, amount))
    ctx.stroke()
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    ctx.set_line_join(cairo.LINE_JOIN_ROUND)
    ctx.set_line_width(2.2)
    ctx.move_to(cx - 4, cy + 0.5)
    ctx.line_to(cx - 1, cy + 3.5)
    ctx.line_to(cx + 4.5, cy - 3)
    ctx.stroke()


def draw_border(ctx):
    round_rect(ctx, 0.75, 0.75, SCENE_W_PX - 1.5, SCENE_H_PX + 8, 8.5, (True, True, False, False))
    ctx.set_line_width(1.5)
    ctx.set_source_rgba(*rgb(C_ACCENT, 0.45))
    ctx.stroke()


def render_scene(state, scale):
    w, h = SCENE_W_PX * scale, SCENE_H_PX * scale
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, w, h)
    ctx = cairo.Context(surf)
    ctx.scale(scale, scale)
    ctx.save()
    round_rect(ctx, 0, 0, SCENE_W_PX, SCENE_H_PX + 8, 9, (True, True, False, False))
    ctx.clip()
    draw_background(ctx)
    tip, tilt, drop = state["x"], state.get("tilt", 0.0), state.get("drop", 0.0)
    draw_cable(ctx, tip, tilt, drop, state.get("data"))
    draw_plug(ctx, tip, tilt, drop)
    draw_motion(ctx, tip, state.get("motion", 0))
    draw_wall(ctx, state.get("led", "off"), state.get("led_glow", 1.0))
    draw_click(ctx, state.get("click", 0))
    draw_sparks(ctx, state.get("sparks", 0))
    draw_x_mark(ctx, state.get("xmark", 0))
    draw_check(ctx, state.get("check", 0))
    ctx.restore()
    draw_border(ctx)
    return surface_to_pil(surf)


def surface_to_pil(surf):
    buf = io.BytesIO()
    surf.write_to_png(buf)
    buf.seek(0)
    return Image.open(buf).convert("RGBA")


# --------------------------------------------------------------------------------------
# Storyboard: 50 steps at 10 fps (5 s loop). Each step references a unique frame.
# --------------------------------------------------------------------------------------
def build_storyboard():
    frames = []      # unique frame states
    timeline = []    # step -> frame index

    def add(state, hold=1):
        frames.append(state)
        idx = len(frames) - 1
        timeline.extend([idx] * hold)
        return idx

    away_tilt, away_drop = 9.0, 9.0
    # approach (1.0 s)
    for i in range(10):
        t = ease(i / 9.0)
        add({
            "x": lerp(X_AWAY, X_TOUCH, t),
            "tilt": lerp(away_tilt, 0, ease(min(1, i / 6.0))),
            "drop": lerp(away_drop, 0, ease(min(1, i / 6.0))),
            "led": "red", "led_glow": 0.3,
        })
    # insert (0.3 s)
    for x in (71, 65, X_INSERTED):
        add({"x": x, "led": "red", "led_glow": 0.3})
    # click (0.2 s)
    add({"x": X_INSERTED, "led": "green", "led_glow": 1.0, "click": 1.0, "check": 0.6})
    add({"x": X_INSERTED, "led": "green", "led_glow": 0.8, "click": 0.45, "check": 1.0})
    # connected (1.5 s): data flowing along the cable, 3 frames cycling
    conn = [add({"x": X_INSERTED, "led": "green", "led_glow": 0.7, "data": p / 3.0 * 0.2, "check": 1.0}, 0) for p in range(3)]
    for i in range(15):
        timeline.append(conn[i % 3])
    # yank (0.3 s)
    add({"x": 72, "led": "red", "led_glow": 1.0, "motion": 0.4, "sparks": 1.0})
    add({"x": 108, "tilt": 3, "drop": 2, "led": "red", "led_glow": 0.8, "motion": 1.0, "sparks": 0.6})
    add({"x": 140, "tilt": 7, "drop": 6, "led": "red", "led_glow": 0.6, "motion": 0.7})
    # disconnected (1.7 s): LED blinks, X pulses
    on = add({"x": X_AWAY, "tilt": away_tilt, "drop": away_drop, "led": "red", "led_glow": 1.0, "xmark": 1.0}, 0)
    off = add({"x": X_AWAY, "tilt": away_tilt, "drop": away_drop, "led": "off", "xmark": 0.75}, 0)
    for i in range(17):
        timeline.append(on if (i // 3) % 2 == 0 else off)
    assert len(timeline) == 50, len(timeline)
    return frames, timeline


# --------------------------------------------------------------------------------------
# Band + text
# --------------------------------------------------------------------------------------
def render_band(scale):
    w, h = BAND_W_PX * scale, BAND_H_PX * scale
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, w, h)
    ctx = cairo.Context(surf)
    ctx.scale(scale, scale)
    round_rect(ctx, 0, -10, BAND_W_PX, BAND_H_PX + 10, 9, (False, False, True, True))
    g = cairo.LinearGradient(0, 0, 0, BAND_H_PX)
    g.add_color_stop_rgba(0, *rgb((0x0C, 0x13, 0x22), 0.97))
    g.add_color_stop_rgba(1, *rgb((0x07, 0x0B, 0x15), 0.97))
    ctx.set_source(g)
    ctx.fill()
    # accent divider
    g = cairo.LinearGradient(0, 0, BAND_W_PX, 0)
    g.add_color_stop_rgba(0, *rgb(C_AMBER, 0.0))
    g.add_color_stop_rgba(0.5, *rgb(C_AMBER, 1.0))
    g.add_color_stop_rgba(1, *rgb(C_AMBER, 0.0))
    ctx.rectangle(0, 0, BAND_W_PX, 1.4)
    ctx.set_source(g)
    ctx.fill()
    # border continuing the scene border
    round_rect(ctx, 0.75, -12, BAND_W_PX - 1.5, BAND_H_PX + 11.25, 8.5, (False, False, True, True))
    ctx.set_line_width(1.5)
    ctx.set_source_rgba(*rgb(C_ACCENT, 0.45))
    ctx.stroke()
    return surface_to_pil(surf)


def render_text_line(text, width_px, height_px, cap_px, top_offset_px, color):
    """Text centred horizontally, cap top at top_offset_px. Supersampled for clean edges."""
    ss = 4
    img = Image.new("RGBA", (width_px * ss, height_px * ss), (0, 0, 0, 0))
    font_size = cap_px / 0.72
    font = ImageFont.truetype(FONT_BLACK, int(round(font_size * ss)))
    tracking = TRACKING_EM * font_size * ss
    total = sum(font.getlength(ch) for ch in text) + tracking * (len(text) - 1)
    cap_top = font.getbbox("H")[1]
    draw = ImageDraw.Draw(img)
    x = (width_px * ss - total) / 2
    y = top_offset_px * ss - cap_top
    for ch in text:
        draw.text((x, y), ch, font=font, fill=color + (255,))
        x += font.getlength(ch) + tracking
    img = img.resize((width_px, height_px), Image.LANCZOS)
    return img, total / ss


def mark_right_edge(img):
    """Bitmap font glyph width = right-most non-transparent column. Pin it to the full width."""
    px = img.load()
    w, h = img.size
    r, g, b, a = px[w - 1, h - 1]
    if a == 0:
        px[w - 1, h - 1] = (0, 0, 0, 1)
    return img


# --------------------------------------------------------------------------------------
# Signal icons (actionbar for the pack, HUD for the mod)
# --------------------------------------------------------------------------------------
def render_signal(level, color, size):
    """level = number of lit bars out of 4."""
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    ctx = cairo.Context(surf)
    s = size / 36.0
    ctx.scale(s, s)
    for i in range(4):
        bh = 8 + i * 7
        x = 3 + i * 8.2
        round_rect(ctx, x, 33 - bh, 6, bh, 1.5)
        if i < level:
            ctx.set_source_rgba(*rgb(color))
        else:
            ctx.set_source_rgba(1, 1, 1, 0.28)
        ctx.fill()
    return surface_to_pil(surf)


# --------------------------------------------------------------------------------------
# Writers
# --------------------------------------------------------------------------------------
def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


def write_json(obj, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=True)
        f.write("\n")


FRAME_CHAR0 = 0xE000
BAND_CHAR = 0xE100
LINE1_CHAR0 = 0xE110
LINE2_CHAR0 = 0xE120
SIGNAL_CHARS = {"poor": 0xE200, "bad": 0xE201, "lost": 0xE202}
# space glyphs: name -> (codepoint, advance)
SPACES = {}
_sp = 0xF800
for adv in (-1, -2, -4, -8, -16, -32, -64, 1, 2, 4, 8, 16, 32, 64):
    SPACES[adv] = _sp
    _sp += 1


def ascent_for(top_fu):
    """Bitmap glyph top = line top + (7 - ascent)  ->  ascent = 7 - (top - line_top)."""
    return 7 - (top_fu - TITLE_LINE_TOP)


def main():
    if os.path.exists(PACK):
        shutil.rmtree(PACK)
    for sub in ("textures", "font"):
        p = os.path.join(MOD_ASSETS, sub)
        if os.path.exists(p):
            shutil.rmtree(p)
    os.makedirs(PREVIEW_DIR, exist_ok=True)

    frames, timeline = build_storyboard()
    n = len(frames)
    print(f"{n} unique frames, {len(timeline)} steps")

    # ---------- scene frames ----------
    pack_sheet = Image.new("RGBA", (SCENE_W_PX, SCENE_H_PX * n))
    mod_sheet = Image.new("RGBA", (SCENE_W_PX * 2, SCENE_H_PX * 2 * n))
    for i, st in enumerate(frames):
        pack_sheet.paste(render_scene(st, 1), (0, i * SCENE_H_PX))
        mod_sheet.paste(render_scene(st, 2), (0, i * SCENE_H_PX * 2))
    save(pack_sheet, os.path.join(PACK, "assets/pingguard/textures/font/card_frames.png"))
    save(mod_sheet, os.path.join(MOD_ASSETS, "textures/gui/card_frames.png"))

    band1 = render_band(1)
    band2 = render_band(2)
    save(band1, os.path.join(PACK, "assets/pingguard/textures/font/card_band.png"))
    save(band2, os.path.join(MOD_ASSETS, "textures/gui/card_band.png"))

    # ---------- text lines ----------
    cap_px = int(round(CAP_FU * TEXT_PX_PER_FU))      # 29 px
    box_px = TEXT_BOX_H * TEXT_PX_PER_FU               # 48 px
    band_top = CARD_TOP + SCENE_H                      # 7
    gap_fu = 1.0
    block = CAP_FU * 2 + gap_fu
    cap1_top = band_top + (BAND_H - block) / 2 + 0.15
    cap2_top = cap1_top + CAP_FU + gap_fu
    box1_top = math.floor(cap1_top)
    box2_top = math.floor(cap2_top)
    off1 = int(round((cap1_top - box1_top) * TEXT_PX_PER_FU))
    off2 = int(round((cap2_top - box2_top) * TEXT_PX_PER_FU))
    white = (0xF4, 0xF7, 0xFF)
    l1w = LINE1_CHUNKS * LINE1_CHUNK_FU
    l2w = LINE2_CHUNKS * LINE2_CHUNK_FU
    line1, w1 = render_text_line(LINE1, l1w * TEXT_PX_PER_FU, box_px, cap_px, off1, (0xFF, 0xC4, 0x4D))
    line2, w2 = render_text_line(LINE2, l2w * TEXT_PX_PER_FU, box_px, cap_px, off2, white)
    print(f"text widths: line1 {w1/TEXT_PX_PER_FU:.1f} fu (box {l1w}), line2 {w2/TEXT_PX_PER_FU:.1f} fu (box {l2w})")
    assert w2 / TEXT_PX_PER_FU < l2w - 0.5
    # pin each chunk's right edge so advances are exact
    for img, chunks, cfu in ((line1, LINE1_CHUNKS, LINE1_CHUNK_FU), (line2, LINE2_CHUNKS, LINE2_CHUNK_FU)):
        px = img.load()
        for c in range(chunks):
            x = (c + 1) * cfu * TEXT_PX_PER_FU - 1
            if px[x, box_px - 1][3] == 0:
                px[x, box_px - 1] = (0, 0, 0, 1)
    save(line1, os.path.join(PACK, "assets/pingguard/textures/font/card_line1.png"))
    save(line2, os.path.join(PACK, "assets/pingguard/textures/font/card_line2.png"))
    save(line1, os.path.join(MOD_ASSETS, "textures/gui/card_line1.png"))
    save(line2, os.path.join(MOD_ASSETS, "textures/gui/card_line2.png"))

    # ---------- signal icons ----------
    sig = {
        "poor": render_signal(2, C_AMBER, 36),
        "bad": render_signal(1, C_RED, 36),
        "lost": render_signal(0, C_RED, 36),
    }
    for k, img in sig.items():
        save(mark_right_edge(img.copy()), os.path.join(PACK, f"assets/pingguard/textures/font/signal_{k}.png"))
        save(render_signal({"poor": 2, "bad": 1, "lost": 0}[k], C_AMBER if k == "poor" else C_RED, 72),
             os.path.join(MOD_ASSETS, f"textures/gui/signal_{k}.png"))

    # ---------- fonts ----------
    for base in (os.path.join(PACK, "assets/pingguard"), MOD_ASSETS):
        os.makedirs(os.path.join(base, "font"), exist_ok=True)
        shutil.copy(FONT_BLACK, os.path.join(base, "font", "orbitron_black.ttf"))
        write_json({"providers": [
            {"type": "ttf", "file": "pingguard:orbitron_black.ttf", "size": 9.0, "oversample": 6.0},
            {"type": "reference", "id": "minecraft:default"},
        ]}, os.path.join(base, "font", "orbitron.json"))

    providers = []
    providers.append({
        "type": "bitmap", "file": "pingguard:font/card_frames.png",
        "height": SCENE_H, "ascent": ascent_for(CARD_TOP),
        "chars": [chr(FRAME_CHAR0 + i) for i in range(n)],
    })
    providers.append({
        "type": "bitmap", "file": "pingguard:font/card_band.png",
        "height": BAND_H, "ascent": ascent_for(band_top),
        "chars": [chr(BAND_CHAR)],
    })
    providers.append({
        "type": "bitmap", "file": "pingguard:font/card_line1.png",
        "height": TEXT_BOX_H, "ascent": ascent_for(box1_top),
        "chars": ["".join(chr(LINE1_CHAR0 + i) for i in range(LINE1_CHUNKS))],
    })
    providers.append({
        "type": "bitmap", "file": "pingguard:font/card_line2.png",
        "height": TEXT_BOX_H, "ascent": ascent_for(box2_top),
        "chars": ["".join(chr(LINE2_CHAR0 + i) for i in range(LINE2_CHUNKS))],
    })
    providers.append({"type": "space", "advances": {chr(cp): adv for adv, cp in SPACES.items()}})
    write_json({"providers": providers}, os.path.join(PACK, "assets/pingguard/font/card.json"))

    # actionbar icons (1x scale, sits on the text line)
    write_json({"providers": [
        {"type": "bitmap", "file": f"pingguard:font/signal_{k}.png", "height": 9, "ascent": 8,
         "chars": [chr(cp)]} for k, cp in SIGNAL_CHARS.items()
    ] + [{"type": "space", "advances": {chr(cp): adv for adv, cp in SPACES.items()}}]},
        os.path.join(PACK, "assets/pingguard/font/icons.json"))

    # ---------- shader ----------
    write_shader(timeline, n)

    # ---------- pack.mcmeta + icon ----------
    write_json({"pack": {
        "description": {"text": "PingGuard - connection warnings", "color": "aqua"},
        "min_format": 97, "max_format": 97,
    }}, os.path.join(PACK, "pack.mcmeta"))
    icon = render_scene(frames[timeline[20]], 1).crop((40, 0, 168, 128)).resize((128, 128), Image.LANCZOS)
    save(icon, os.path.join(PACK, "pack.png"))
    save(icon, os.path.join(MOD_ASSETS, "icon.png"))
    shutil.copy(os.path.join(FONT_DIR, "OFL.txt"), os.path.join(PACK, "ORBITRON_OFL.txt"))

    # ---------- Java layout constants ----------
    write_java(timeline, n, box1_top, box2_top, l1w, l2w)

    # ---------- previews ----------
    write_previews(frames, timeline, line1, line2, band2)
    print("done")


SHADER_TEMPLATE = r'''#version 330
#extension GL_ARB_separate_shader_objects : require

// PingGuard: vanilla minecraft:core/text.vsh (26.3) + connection-warning card logic.
// Glyphs coloured #FE50xx / #FE51xx are PingGuard card glyphs. The title alpha is used
// as a clock (see PingGuard README / VanillaCard.java):
//   #FE50xx  "armed"  : title fades in over 510 ticks (alpha +1 every 2 ticks). PingGuard
//                        restarts it every 250 ms from its own thread, so alpha stays tiny and the
//                        card hidden. If nothing arrives for 2 s, alpha keeps growing -> card appears.
//   #FE51xx  "forced" : title fades out over 510 ticks from alpha 255; always visible.
//   xx = frame id (0..%(nframes_minus1)d) or 255 for always-visible parts (band, text).

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

const int PG_REVEAL_ALPHA = %(reveal)d;
const int PG_STEPS = %(steps)d;
const int PG_TIMELINE[%(steps)d] = int[](%(timeline)s);

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
'''

REVEAL_ALPHA = 20   # 40 ticks (2 s) without any refresh from the server -> no flicker on jittery links


def write_shader(timeline, n):
    src = SHADER_TEMPLATE % {
        "nframes_minus1": n - 1,
        "reveal": REVEAL_ALPHA,
        "steps": len(timeline),
        "timeline": ", ".join(str(i) for i in timeline),
    }
    p = os.path.join(PACK, "assets/minecraft/shaders/core/text.vsh")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", newline="\n") as f:
        f.write(src)


JAVA_TEMPLATE = '''package dev.jemyz.pingguard;

// GENERATED by tools/gen_assets.py - do not edit by hand.
public final class CardLayout {
\tprivate CardLayout() {
\t}

\t/** Frames per second of the card animation. */
\tpublic static final int FPS = %(fps)d;
\t/** Unique frames in card_frames.png (stacked vertically). */
\tpublic static final int FRAME_COUNT = %(n)d;
\t/** step -> frame index; one step = 1/FPS s. */
\tpublic static final int[] TIMELINE = {%(timeline)s};

\t/** Title-glyph code points (font pingguard:card). */
\tpublic static final int FRAME_CHAR0 = 0x%(frame0)X;
\tpublic static final int BAND_CHAR = 0x%(band)X;
\tpublic static final int LINE1_CHAR0 = 0x%(l1)X;
\tpublic static final int LINE1_CHUNKS = %(l1c)d;
\tpublic static final int LINE1_CHUNK_FU = %(l1cfu)d;
\tpublic static final int LINE2_CHAR0 = 0x%(l2)X;
\tpublic static final int LINE2_CHUNKS = %(l2c)d;
\tpublic static final int LINE2_CHUNK_FU = %(l2cfu)d;
\tpublic static final int SIGNAL_POOR = 0x%(sp)X;
\tpublic static final int SIGNAL_BAD = 0x%(sb)X;
\tpublic static final int SIGNAL_LOST = 0x%(sl)X;
\t/** Space glyph code point for an advance (font pingguard:card / pingguard:icons). */
\tpublic static int spaceChar(int advance) {
\t\treturn switch (advance) {
%(spaces)s
\t\t\tdefault -> throw new IllegalArgumentException("no space glyph for " + advance);
\t\t};
\t}

\t/** Geometry in title font units (1 fu = 4 GUI px), relative to the screen centre. */
\tpublic static final int CARD_W = %(cw)d;
\tpublic static final int SCENE_H = %(sh)d;
\tpublic static final int BAND_H = %(bh)d;
\tpublic static final int CARD_TOP = %(ct)d;
\tpublic static final int TEXT_BOX_H = %(tbh)d;
\tpublic static final int LINE1_TOP = %(b1)d;
\tpublic static final int LINE2_TOP = %(b2)d;
\tpublic static final int LINE1_W = %(l1w)d;
\tpublic static final int LINE2_W = %(l2w)d;

\t/** Alpha (out of 255) at which an armed card becomes visible; see text.vsh. */
\tpublic static final int REVEAL_ALPHA = %(reveal)d;
\t/** Fade length used as the shader clock; alpha changes by exactly 1 every 2 ticks. */
\tpublic static final int CLOCK_TICKS = 510;
}
'''


def write_java(timeline, n, b1, b2, l1w, l2w):
    spaces = "\n".join(f"\t\t\tcase {adv} -> 0x{cp:X};" for adv, cp in SPACES.items())
    src = JAVA_TEMPLATE % {
        "fps": FPS, "n": n, "timeline": ", ".join(map(str, timeline)),
        "frame0": FRAME_CHAR0, "band": BAND_CHAR, "l1": LINE1_CHAR0, "l1c": LINE1_CHUNKS,
        "l1cfu": LINE1_CHUNK_FU, "l2": LINE2_CHAR0, "l2c": LINE2_CHUNKS, "l2cfu": LINE2_CHUNK_FU,
        "sp": SIGNAL_CHARS["poor"], "sb": SIGNAL_CHARS["bad"], "sl": SIGNAL_CHARS["lost"],
        "spaces": spaces, "cw": CARD_W, "sh": SCENE_H, "bh": BAND_H, "ct": CARD_TOP,
        "tbh": TEXT_BOX_H, "b1": b1, "b2": b2, "l1w": l1w, "l2w": l2w, "reveal": REVEAL_ALPHA,
    }
    os.makedirs(os.path.dirname(JAVA_LAYOUT), exist_ok=True)
    with open(JAVA_LAYOUT, "w", newline="\n") as f:
        f.write(src)


def compose_card(scene2x, band2x, line1, line2, box1_top, box2_top):
    """Full card at 8 px per fu (mod resolution), for previews."""
    fu = 8
    W = CARD_W * fu
    Hc = (SCENE_H + BAND_H) * fu
    card = Image.new("RGBA", (W, Hc), (0, 0, 0, 0))
    card.alpha_composite(scene2x.resize((W, SCENE_H * fu), Image.LANCZOS), (0, 0))
    card.alpha_composite(band2x.resize((W, BAND_H * fu), Image.LANCZOS), (0, SCENE_H * fu))
    for img, top in ((line1, box1_top), (line2, box2_top)):
        w = img.size[0] * fu // TEXT_PX_PER_FU
        x = (W - w) // 2
        y = (top - CARD_TOP) * fu
        card.alpha_composite(img.resize((w, TEXT_BOX_H * fu), Image.LANCZOS), (x, y))
    return card


def write_previews(frames, timeline, line1, line2, band2):
    band_top = CARD_TOP + SCENE_H
    block = CAP_FU * 2 + 1.0
    cap1_top = band_top + (BAND_H - block) / 2 + 0.15
    b1 = math.floor(cap1_top)
    b2 = math.floor(cap1_top + CAP_FU + 1.0)
    rendered = [render_scene(f, 2) for f in frames]
    cards = []
    for idx in timeline:
        card = compose_card(rendered[idx], band2, line1, line2, b1, b2)
        bg = Image.new("RGBA", card.size, (40, 46, 40, 255))
        bg.alpha_composite(card)
        cards.append(bg.convert("RGB"))
    cards[0].save(os.path.join(PREVIEW_DIR, "card.gif"), save_all=True, append_images=cards[1:],
                  duration=1000 // FPS, loop=0, optimize=False)
    for i in (5, 12, 20, 31, 40):
        cards[i].save(os.path.join(PREVIEW_DIR, f"card_step{i:02d}.png"))


if __name__ == "__main__":
    main()
