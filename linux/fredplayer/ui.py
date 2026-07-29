from __future__ import annotations

from collections import OrderedDict
import colorsys
import ctypes
import math
from pathlib import Path
import random
from string import Template
import sys
import threading
import time
from typing import Callable

import cairo
import gi
try:
    from OpenGL import GL
    import numpy as np
except Exception:  # pragma: no cover - optional GPU renderer
    GL = None
    np = None

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("Gio", "2.0")
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import Gdk, GdkPixbuf, Gio, GLib, Gtk, Pango  # noqa: E402

from . import APP_ID, APP_NAME
from .audio import BackgroundPrecomputer, NormalizingAudioPlayer, PlayerCallbacks, PrecomputeCallbacks
from . import latency
from .leveling import LevelingSettings
from .mpris import MprisServer
from . import remote
from .store import (
    AUDIO_EXTENSIONS,
    PlaylistEntry,
    ProfileCache,
    SpeakerLatency,
    StateStore,
    StoredState,
    SpectrumCache,
    WaveformCache,
    WindowState,
    collect_audio_files,
    device_id,
    friendly_path,
    is_audio_file,
    merge_entries,
    track_info,
    track_info_for_entry,
)
from .visualization import FFT_SIZE_OPTIONS, RealtimeAnalyzer, VisualizationFrame, VisualizationSettings


_CSS_PROVIDER: Gtk.CssProvider | None = None
_THEME_SIGNAL_IDS: list[int] = []
_GNOME_INTERFACE_SETTINGS: Gio.Settings | None = None
VISUAL_SAMPLE_RATE = 48_000
APP_ICON_PATH = Path(__file__).resolve().parent.parent / "assets" / "fredplayer-icon.png"


class CairoVisualizerView(Gtk.Image):
    def __init__(self) -> None:
        super().__init__()
        self.get_style_context().add_class("visualizer")
        self.set_hexpand(True)
        self.set_vexpand(False)
        self.set_size_request(-1, 220)
        self.settings = VisualizationSettings()
        self.frame = RealtimeAnalyzer(VISUAL_SAMPLE_RATE, settings=self.settings).silence()
        self.smoothed_spectrum = list(self.frame.spectrum)
        self.fft_scale = "log"
        self.connect("size-allocate", lambda *_args: self.render())
        GLib.idle_add(self.render)

    def set_frame(self, frame: VisualizationFrame) -> None:
        self.frame = frame
        self.smoothed_spectrum = smooth_spectrum(
            self.smoothed_spectrum,
            frame.spectrum,
            self.settings.fft_smoothing,
        )
        self.render()

    def clear(self) -> None:
        self.set_frame(RealtimeAnalyzer(VISUAL_SAMPLE_RATE, settings=self.settings).silence())

    def set_fft_scale(self, fft_scale: str) -> None:
        self.set_visualization_settings(
            VisualizationSettings(
                update_fps=self.settings.update_fps,
                waveform_window_ms=self.settings.waveform_window_ms,
                fft_scale=fft_scale,
                fft_columns=self.settings.fft_columns,
                fft_size=self.settings.fft_size,
                fft_smoothing=self.settings.fft_smoothing,
            )
        )

    def set_visualization_settings(self, settings: VisualizationSettings) -> None:
        self.settings = settings
        self.fft_scale = settings.fft_scale
        self.smoothed_spectrum = [0.0 for _ in range(settings.fft_columns)]
        self.clear()

    def render(self) -> bool:
        allocation = self.get_allocation()
        width = max(640, allocation.width)
        height = max(220, allocation.height)
        surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, width, height)
        cr = cairo.Context(surface)
        self._render_to_context(cr, width, height)
        self.set_from_pixbuf(surface_to_pixbuf(surface, width, height))
        return False

    def _render_to_context(self, cr, width: int, height: int) -> None:
        dark = is_dark_theme()
        bg = (0.07, 0.08, 0.09) if dark else (0.98, 0.99, 1.0)
        panel = (0.10, 0.12, 0.13) if dark else (0.94, 0.96, 0.98)
        grid = (0.25, 0.29, 0.32, 0.52) if dark else (0.72, 0.76, 0.80, 0.55)
        text = (0.84, 0.86, 0.84, 0.86) if dark else (0.24, 0.28, 0.32, 0.86)
        wave = (0.16, 0.79, 0.72) if dark else (0.00, 0.49, 0.45)

        rounded_rect(cr, 0.5, 0.5, width - 1.0, height - 1.0, 8.0)
        set_source_rgb(cr, *bg)
        cr.fill_preserve()
        set_source_rgba(cr, *grid)
        cr.set_line_width(1.0)
        cr.stroke()

        margin = 14.0
        label_h = 18.0
        inner_x = margin
        inner_w = max(1.0, width - (margin * 2.0))
        wave_y = margin + label_h
        wave_h = max(46.0, height * 0.34)
        fft_y = wave_y + wave_h + 18.0
        fft_h = max(48.0, height - fft_y - margin)

        self._label(cr, "Waveform", inner_x, margin, text)
        self._label(cr, f"FFT Spectrum ({self.fft_scale})", inner_x, fft_y - label_h, text)
        self._panel(cr, inner_x, wave_y, inner_w, wave_h, panel, grid)
        self._panel(cr, inner_x, fft_y, inner_w, fft_h, panel, grid)
        self._draw_waveform(cr, inner_x, wave_y, inner_w, wave_h, wave, grid)
        self._draw_spectrum(cr, inner_x, fft_y, inner_w, fft_h, dark)

    def _panel(self, cr, x: float, y: float, width: float, height: float, panel, grid) -> None:
        rounded_rect(cr, x, y, width, height, 6.0)
        set_source_rgb(cr, *panel)
        cr.fill()
        cr.set_line_width(1.0)
        set_source_rgba(cr, *grid)
        for fraction in (0.25, 0.5, 0.75):
            line_y = y + (height * fraction)
            cr.move_to(x, line_y)
            cr.line_to(x + width, line_y)
        cr.stroke()

    def _draw_waveform(self, cr, x: float, y: float, width: float, height: float, color, grid) -> None:
        values = self.frame.waveform
        if not values:
            return
        center = y + (height * 0.5)
        scale = height * 0.46
        set_source_rgba(cr, *grid)
        cr.set_line_width(1.0)
        cr.move_to(x, center)
        cr.line_to(x + width, center)
        cr.stroke()

        set_source_rgb(cr, *color)
        cr.set_line_width(1.6)
        points: list[tuple[float, float]] = []
        for index, sample in enumerate(values):
            px = x + (width * index / max(1, len(values) - 1))
            py = center - (sample * scale)
            points.append((px, py))
            if index == 0:
                cr.move_to(px, py)
            else:
                cr.line_to(px, py)
        cr.stroke()
        if points:
            cr.move_to(points[0][0], center)
            for px, py in points:
                cr.line_to(px, py)
            cr.line_to(points[-1][0], center)
            cr.close_path()
            set_source_rgba(cr, color[0], color[1], color[2], 0.18)
            cr.fill()

    def _draw_spectrum(self, cr, x: float, y: float, width: float, height: float, dark: bool) -> None:
        values = self.smoothed_spectrum
        if not values:
            return
        gap = 1.5
        bar_w = max(2.0, (width - (gap * (len(values) - 1))) / len(values))
        for index, value in enumerate(values):
            amount = max(0.0, min(1.0, value))
            bar_h = max(1.0, amount * height)
            hue = 0.62 - (0.62 * index / max(1, len(values) - 1))
            saturation = 0.88 if dark else 0.78
            brightness = 0.95 if dark else 0.78
            red, green, blue = colorsys.hsv_to_rgb(hue, saturation, brightness)
            set_source_rgb(cr, red, green, blue)
            bx = x + (index * (bar_w + gap))
            by = y + height - bar_h
            cr.rectangle(bx, by, bar_w, bar_h)
            cr.fill()

    def _label(self, cr, text: str, x: float, y: float, color) -> None:
        set_source_rgba(cr, *color)
        cr.select_font_face("Sans", 0, 0)
        cr.set_font_size(12.0)
        cr.move_to(x, y + 12.0)
        cr.show_text(text)


class OpenGLVisualizerView(Gtk.GLArea):
    VERTEX_SHADER = """
        #version 150
        in vec2 position;
        in vec4 color;
        out vec4 vertex_color;
        void main() {
            vertex_color = color;
            gl_Position = vec4(position, 0.0, 1.0);
        }
    """
    FRAGMENT_SHADER = """
        #version 150
        in vec4 vertex_color;
        out vec4 fragment_color;
        void main() {
            fragment_color = vertex_color;
        }
    """

    def __init__(self) -> None:
        super().__init__()
        self.get_style_context().add_class("visualizer")
        self.set_hexpand(True)
        self.set_vexpand(False)
        self.set_size_request(-1, 220)
        self.set_required_version(3, 2)
        self.set_has_alpha(True)
        self.set_has_depth_buffer(False)
        self.set_has_stencil_buffer(False)
        self.set_auto_render(False)
        self.settings = VisualizationSettings()
        self.frame = RealtimeAnalyzer(VISUAL_SAMPLE_RATE, settings=self.settings).silence()
        self.smoothed_spectrum = list(self.frame.spectrum)
        self.fft_scale = self.settings.fft_scale
        self.program = 0
        self.vao = 0
        self.vbo = 0
        self.gl_error = ""
        self.connect("realize", self._on_realize)
        self.connect("unrealize", self._on_unrealize)
        self.connect("render", self._on_render)
        self.connect("resize", lambda *_args: self.queue_render())

    def set_frame(self, frame: VisualizationFrame) -> None:
        self.frame = frame
        self.smoothed_spectrum = smooth_spectrum(
            self.smoothed_spectrum,
            frame.spectrum,
            self.settings.fft_smoothing,
        )
        self.queue_render()

    def clear(self) -> None:
        self.set_frame(RealtimeAnalyzer(VISUAL_SAMPLE_RATE, settings=self.settings).silence())

    def set_fft_scale(self, fft_scale: str) -> None:
        self.set_visualization_settings(
            VisualizationSettings(
                update_fps=self.settings.update_fps,
                waveform_window_ms=self.settings.waveform_window_ms,
                fft_scale=fft_scale,
                fft_columns=self.settings.fft_columns,
                fft_size=self.settings.fft_size,
                fft_smoothing=self.settings.fft_smoothing,
            )
        )

    def set_visualization_settings(self, settings: VisualizationSettings) -> None:
        self.settings = settings
        self.fft_scale = settings.fft_scale
        self.smoothed_spectrum = [0.0 for _ in range(settings.fft_columns)]
        self.clear()

    def _on_realize(self, _area: Gtk.GLArea) -> None:
        if GL is None or np is None:
            self.gl_error = "OpenGL renderer dependencies are missing"
            return
        self.make_current()
        error = self.get_error()
        if error is not None:
            self.gl_error = str(error)
            return
        try:
            self.program = compile_gl_program(self.VERTEX_SHADER, self.FRAGMENT_SHADER)
            self.vao = int(GL.glGenVertexArrays(1))
            self.vbo = int(GL.glGenBuffers(1))
            GL.glEnable(GL.GL_BLEND)
            GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA)
        except Exception as error:
            self.gl_error = str(error)

    def _on_unrealize(self, _area: Gtk.GLArea) -> None:
        if GL is not None:
            self.make_current()
            if self.vao:
                GL.glDeleteVertexArrays(1, [self.vao])
            if self.vbo:
                GL.glDeleteBuffers(1, [self.vbo])
            if self.program:
                GL.glDeleteProgram(self.program)
        self.vao = 0
        self.vbo = 0
        self.program = 0

    def _on_render(self, _area: Gtk.GLArea, _context: Gdk.GLContext) -> bool:
        if GL is None or np is None:
            return True
        self.make_current()
        if self.get_error() is not None:
            return True
        try:
            self._render_gl()
        except Exception as error:
            self.gl_error = str(error)
        return True

    def _render_gl(self) -> None:
        width = max(1, self.get_allocated_width() * self.get_scale_factor())
        height = max(1, self.get_allocated_height() * self.get_scale_factor())
        dark = is_dark_theme()
        bg = (0.07, 0.08, 0.09, 1.0) if dark else (0.98, 0.99, 1.0, 1.0)
        panel = (0.10, 0.12, 0.13, 1.0) if dark else (0.94, 0.96, 0.98, 1.0)
        grid = (0.25, 0.29, 0.32, 0.58) if dark else (0.72, 0.76, 0.80, 0.62)
        wave = (0.16, 0.79, 0.72, 1.0) if dark else (0.00, 0.49, 0.45, 1.0)
        wave_fill = (wave[0], wave[1], wave[2], 0.18)

        GL.glViewport(0, 0, width, height)
        GL.glClearColor(*bg)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        GL.glEnable(GL.GL_BLEND)
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA)

        margin = 14.0 * self.get_scale_factor()
        inner_x = margin
        inner_w = max(1.0, width - (margin * 2.0))
        wave_y = margin
        wave_h = max(46.0, height * 0.36)
        fft_y = wave_y + wave_h + (18.0 * self.get_scale_factor())
        fft_h = max(48.0, height - fft_y - margin)

        rects: list[float] = []
        self._append_rect(rects, 0.0, 0.0, width, height, bg, width, height)
        self._append_rect(rects, inner_x, wave_y, inner_w, wave_h, panel, width, height)
        self._append_rect(rects, inner_x, fft_y, inner_w, fft_h, panel, width, height)
        self._append_spectrum(rects, inner_x, fft_y, inner_w, fft_h, dark, width, height)
        self._draw_vertices(rects, GL.GL_TRIANGLES)

        waveform_fill: list[float] = []
        self._append_waveform_fill(waveform_fill, inner_x, wave_y, inner_w, wave_h, wave_fill, width, height)
        self._draw_vertices(waveform_fill, GL.GL_TRIANGLE_STRIP)

        lines: list[float] = []
        self._append_panel_lines(lines, inner_x, wave_y, inner_w, wave_h, grid, width, height)
        self._append_panel_lines(lines, inner_x, fft_y, inner_w, fft_h, grid, width, height)
        self._append_waveform_line(lines, inner_x, wave_y, inner_w, wave_h, wave, width, height)
        GL.glLineWidth(1.0 * self.get_scale_factor())
        self._draw_vertices(lines, GL.GL_LINES)

    def _append_spectrum(
        self,
        vertices: list[float],
        x: float,
        y: float,
        width: float,
        height: float,
        dark: bool,
        surface_width: int,
        surface_height: int,
    ) -> None:
        values = self.smoothed_spectrum
        if not values:
            return
        gap = max(1.0, min(3.0, width / max(1, len(values) * 40.0)))
        bar_w = max(1.0, (width - (gap * (len(values) - 1))) / len(values))
        for index, value in enumerate(values):
            amount = max(0.0, min(1.0, value))
            bar_h = max(1.0, amount * height)
            hue = 0.62 - (0.62 * index / max(1, len(values) - 1))
            saturation = 0.88 if dark else 0.78
            brightness = 0.95 if dark else 0.78
            red, green, blue = colorsys.hsv_to_rgb(hue, saturation, brightness)
            bx = x + (index * (bar_w + gap))
            by = y + height - bar_h
            self._append_rect(vertices, bx, by, bar_w, bar_h, (red, green, blue, 1.0), surface_width, surface_height)

    def _append_waveform_fill(
        self,
        vertices: list[float],
        x: float,
        y: float,
        width: float,
        height: float,
        color: tuple[float, float, float, float],
        surface_width: int,
        surface_height: int,
    ) -> None:
        values = self.frame.waveform
        if not values:
            return
        center = y + (height * 0.5)
        scale = height * 0.46
        for index, sample in enumerate(values):
            px = x + (width * index / max(1, len(values) - 1))
            py = center - (sample * scale)
            self._append_vertex(vertices, px, center, color, surface_width, surface_height)
            self._append_vertex(vertices, px, py, color, surface_width, surface_height)

    def _append_waveform_line(
        self,
        vertices: list[float],
        x: float,
        y: float,
        width: float,
        height: float,
        color: tuple[float, float, float, float],
        surface_width: int,
        surface_height: int,
    ) -> None:
        values = self.frame.waveform
        if not values:
            return
        center = y + (height * 0.5)
        scale = height * 0.46
        previous: tuple[float, float] | None = None
        for index, sample in enumerate(values):
            point = (
                x + (width * index / max(1, len(values) - 1)),
                center - (sample * scale),
            )
            if previous is not None:
                self._append_vertex(vertices, previous[0], previous[1], color, surface_width, surface_height)
                self._append_vertex(vertices, point[0], point[1], color, surface_width, surface_height)
            previous = point

    def _append_panel_lines(
        self,
        vertices: list[float],
        x: float,
        y: float,
        width: float,
        height: float,
        color: tuple[float, float, float, float],
        surface_width: int,
        surface_height: int,
    ) -> None:
        self._append_line(vertices, x, y, x + width, y, color, surface_width, surface_height)
        self._append_line(vertices, x + width, y, x + width, y + height, color, surface_width, surface_height)
        self._append_line(vertices, x + width, y + height, x, y + height, color, surface_width, surface_height)
        self._append_line(vertices, x, y + height, x, y, color, surface_width, surface_height)
        for fraction in (0.25, 0.5, 0.75):
            line_y = y + (height * fraction)
            self._append_line(vertices, x, line_y, x + width, line_y, color, surface_width, surface_height)

    def _append_line(
        self,
        vertices: list[float],
        x1: float,
        y1: float,
        x2: float,
        y2: float,
        color: tuple[float, float, float, float],
        surface_width: int,
        surface_height: int,
    ) -> None:
        self._append_vertex(vertices, x1, y1, color, surface_width, surface_height)
        self._append_vertex(vertices, x2, y2, color, surface_width, surface_height)

    def _append_rect(
        self,
        vertices: list[float],
        x: float,
        y: float,
        width: float,
        height: float,
        color: tuple[float, float, float, float],
        surface_width: int,
        surface_height: int,
    ) -> None:
        x2 = x + width
        y2 = y + height
        for point in ((x, y), (x2, y), (x2, y2), (x, y), (x2, y2), (x, y2)):
            self._append_vertex(vertices, point[0], point[1], color, surface_width, surface_height)

    def _append_vertex(
        self,
        vertices: list[float],
        x: float,
        y: float,
        color: tuple[float, float, float, float],
        surface_width: int,
        surface_height: int,
    ) -> None:
        vertices.extend(((x / surface_width * 2.0) - 1.0, 1.0 - (y / surface_height * 2.0), *color))

    def _draw_vertices(self, vertices: list[float], mode: int) -> None:
        if not vertices or not self.program or not self.vao or not self.vbo or np is None:
            return
        data = np.asarray(vertices, dtype=np.float32)
        GL.glUseProgram(self.program)
        GL.glBindVertexArray(self.vao)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, self.vbo)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, data.nbytes, data, GL.GL_STREAM_DRAW)
        stride = 6 * 4
        GL.glEnableVertexAttribArray(0)
        GL.glEnableVertexAttribArray(1)
        GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, False, stride, ctypes.c_void_p(0))
        GL.glVertexAttribPointer(1, 4, GL.GL_FLOAT, False, stride, ctypes.c_void_p(8))
        GL.glDrawArrays(mode, 0, len(vertices) // 6)
        GL.glDisableVertexAttribArray(0)
        GL.glDisableVertexAttribArray(1)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
        GL.glBindVertexArray(0)


def compile_gl_program(vertex_source: str, fragment_source: str) -> int:
    if GL is None:
        raise RuntimeError("OpenGL is not available")
    vertex_shader = compile_gl_shader(GL.GL_VERTEX_SHADER, vertex_source)
    fragment_shader = compile_gl_shader(GL.GL_FRAGMENT_SHADER, fragment_source)
    program = GL.glCreateProgram()
    GL.glAttachShader(program, vertex_shader)
    GL.glAttachShader(program, fragment_shader)
    GL.glBindAttribLocation(program, 0, "position")
    GL.glBindAttribLocation(program, 1, "color")
    GL.glLinkProgram(program)
    GL.glDeleteShader(vertex_shader)
    GL.glDeleteShader(fragment_shader)
    if not GL.glGetProgramiv(program, GL.GL_LINK_STATUS):
        message = GL.glGetProgramInfoLog(program).decode("utf-8", "replace")
        GL.glDeleteProgram(program)
        raise RuntimeError(f"OpenGL shader link failed: {message}")
    return int(program)


def compile_gl_shader(shader_type: int, source: str) -> int:
    if GL is None:
        raise RuntimeError("OpenGL is not available")
    shader = GL.glCreateShader(shader_type)
    GL.glShaderSource(shader, source)
    GL.glCompileShader(shader)
    if not GL.glGetShaderiv(shader, GL.GL_COMPILE_STATUS):
        message = GL.glGetShaderInfoLog(shader).decode("utf-8", "replace")
        GL.glDeleteShader(shader)
        raise RuntimeError(f"OpenGL shader compile failed: {message}")
    return int(shader)


VisualizerView = OpenGLVisualizerView if GL is not None and np is not None else CairoVisualizerView


def smooth_spectrum(
    previous: list[float],
    spectrum: tuple[float, ...],
    smoothing_percent: float,
) -> list[float]:
    if len(previous) != len(spectrum):
        return list(spectrum)
    smoothing = max(0.0, min(1.0, smoothing_percent / 100.0))
    if smoothing <= 0.0:
        return list(spectrum)

    rise_alpha = 1.0 - (smoothing * 0.55)
    decay_alpha = 1.0 - (smoothing * 0.90)
    return [
        old + ((new - old) * (rise_alpha if new > old else decay_alpha))
        for old, new in zip(previous, spectrum)
    ]


def rounded_rect(cr, x: float, y: float, width: float, height: float, radius: float) -> None:
    radius = min(radius, width / 2.0, height / 2.0)
    cr.new_sub_path()
    cr.arc(x + width - radius, y + radius, radius, -math.pi / 2.0, 0.0)
    cr.arc(x + width - radius, y + height - radius, radius, 0.0, math.pi / 2.0)
    cr.arc(x + radius, y + height - radius, radius, math.pi / 2.0, math.pi)
    cr.arc(x + radius, y + radius, radius, math.pi, 3.0 * math.pi / 2.0)
    cr.close_path()


def set_source_rgb(cr, red: float, green: float, blue: float) -> None:
    cr.set_source_rgb(blue, green, red)


def set_source_rgba(cr, red: float, green: float, blue: float, alpha: float) -> None:
    cr.set_source_rgba(blue, green, red, alpha)


def surface_to_pixbuf(surface: cairo.ImageSurface, width: int, height: int) -> GdkPixbuf.Pixbuf:
    surface.flush()
    stride = surface.get_stride()
    # Cairo ARGB32 is BGRA on little-endian systems. Visualizer colors are
    # drawn red/blue-swapped so GTK can consume the bytes as RGBA directly.
    pixels = bytes(surface.get_data()[: stride * height])
    return GdkPixbuf.Pixbuf.new_from_bytes(
        GLib.Bytes(pixels),
        GdkPixbuf.Colorspace.RGB,
        True,
        8,
        width,
        height,
        stride,
    )


def make_icon_button(icon_name: str, tooltip: str, primary: bool = False) -> Gtk.Button:
    button = Gtk.Button()
    button.set_hexpand(False)
    button.set_halign(Gtk.Align.CENTER)
    button.get_style_context().add_class("icon-button")
    if primary:
        button.get_style_context().add_class("primary-button")
    set_button_icon(button, icon_name, tooltip)
    return button


def make_text_button(label: str) -> Gtk.Button:
    button = Gtk.Button(label=label)
    button.set_hexpand(False)
    button.set_halign(Gtk.Align.START)
    return button


def set_button_icon(button: Gtk.Button, icon_name: str, tooltip: str) -> None:
    image = Gtk.Image.new_from_icon_name(icon_name, Gtk.IconSize.LARGE_TOOLBAR)
    image.show()
    button.set_image(image)
    button.set_always_show_image(True)
    button.set_tooltip_text(tooltip)
    button.set_size_request(44, 40)


def format_bytes(value: int) -> str:
    amount = float(max(0, value))
    for unit in ("B", "KB", "MB", "GB"):
        if amount < 1024.0 or unit == "GB":
            return f"{amount:.1f} {unit}" if unit != "B" else f"{int(amount)} B"
        amount /= 1024.0
    return f"{amount:.1f} GB"


def format_time_ms(value: int) -> str:
    total_seconds = max(0, int(value) // 1000)
    hours, remainder = divmod(total_seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{seconds:02d}"
    return f"{minutes}:{seconds:02d}"


class FredPlayerApp(Gtk.Application):
    def __init__(self) -> None:
        super().__init__(application_id=APP_ID)
        self.window: FredPlayerWindow | None = None

    def do_activate(self) -> None:
        install_css()
        if self.window is None:
            self.window = FredPlayerWindow(self)
        self.window.show_all()
        self.window.restore_saved_placement()
        self.window.present()


class FredPlayerWindow(Gtk.ApplicationWindow):
    def __init__(self, app: Gtk.Application) -> None:
        super().__init__(application=app, title=APP_NAME)
        if APP_ICON_PATH.is_file():
            self.set_icon_from_file(str(APP_ICON_PATH))
        self.set_default_size(860, 760)
        self.set_border_width(0)

        self.store = StateStore()
        state = self.store.load()
        self.named_playlists = {
            name: list(entries)
            for name, entries in state.named_playlists.items()
        }
        self.active_playlist_name = state.active_playlist
        self.playlist = self.named_playlists[self.active_playlist_name]
        self.output_level = state.output_level
        self.leveling_strength = state.leveling_strength
        self.leveling_settings = state.leveling_settings
        self.visualization_settings = state.visualization_settings
        self.server_base_url = state.server_base_url
        self.server_token = state.server_token
        self.shuffle_enabled = state.shuffle_enabled
        self.speaker_latencies = dict(state.speaker_latencies)
        self.selected_microphone = state.selected_microphone
        self.window_state = state.window_state
        self._is_maximized = state.window_state.maximized

        self.set_size_request(480, 620)
        self.set_default_size(max(480, self.window_state.width), max(620, self.window_state.height))
        self.playback_requested = False
        self.audio_actually_playing = False
        self.current_index = -1
        self.current_path = ""
        self.shuffle_bag: list[int] = []
        self.track_history: list[str] = []
        self._visualization_lock = threading.Lock()
        self._pending_visualization_frame: VisualizationFrame | None = None
        self._visualization_idle_scheduled = False
        self._latest_visualization_frame = RealtimeAnalyzer(
            VISUAL_SAMPLE_RATE,
            settings=self.visualization_settings,
        ).silence()
        self._last_visual_clock_render_at = 0.0
        self._seek_dragging = False
        self._playlist_selector_updating = False
        self._destroyed = False
        self._track_list_generation = 0
        self._liam_wait_dialog: Gtk.Dialog | None = None
        self._save_state_debounce_id: int | None = None
        self._latency_route: latency.AudioOutput | None = None
        self._latency_system_ms: int | None = None
        self._latency_system_route_key = ""
        self._latency_probe_running = False
        self._latency_route_check_running = False
        self._latency_calibrating = False
        self._resume_after_latency_calibration = False

        self.player = NormalizingAudioPlayer(
            PlayerCallbacks(
                on_track_started=lambda path: GLib.idle_add(self._on_track_started, path),
                on_track_finished=lambda path: GLib.idle_add(self._on_track_finished, path),
                on_error=lambda path, error: GLib.idle_add(self._on_track_error, path, error),
                on_status=lambda path, status: GLib.idle_add(self._on_track_status, path, status),
                on_visualization=self._queue_visualization_frame,
            )
        )
        self.player.set_output_level(self.output_level)
        self.player.set_leveling_strength(self.leveling_strength)
        self.player.set_leveling_settings(self.leveling_settings)
        self.player.set_visualization_settings(self.visualization_settings)
        self.player.set_visual_delay_ms(0)
        self.player.set_server_config(self.server_base_url, self.server_token)
        self.precomputer = BackgroundPrecomputer(
            PrecomputeCallbacks(
                on_status=lambda path, status: GLib.idle_add(self._on_precompute_status, path, status),
            )
        )

        self._build_ui()
        self.mpris = MprisServer(self)
        self._update_all()
        self._schedule_background_precompute()
        self.connect("configure-event", self._on_configure_event)
        self.connect("window-state-event", self._on_window_state_event)
        self.connect("key-press-event", self._on_key_press)
        self.connect("destroy", self._on_destroy)
        GLib.timeout_add(4, self._on_visual_clock_tick)
        GLib.timeout_add(500, self._on_progress_tick)
        GLib.timeout_add_seconds(3, self._on_latency_route_tick)
        GLib.timeout_add_seconds(15, self._on_cache_status_tick)

    def _build_ui(self) -> None:
        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        root.get_style_context().add_class("app-root")
        self.add(root)

        self.page_stack = Gtk.Stack()
        self.page_stack.set_homogeneous(False)
        self.page_stack.set_transition_type(Gtk.StackTransitionType.SLIDE_LEFT_RIGHT)
        self.page_stack.set_transition_duration(180)
        root.pack_start(self.page_stack, True, True, 0)

        player_page = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        settings_page = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        settings_scroller = Gtk.ScrolledWindow()
        settings_scroller.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.NEVER)
        settings_scroller.set_hexpand(True)
        settings_scroller.set_vexpand(True)
        try:
            settings_scroller.set_propagate_natural_width(False)
        except AttributeError:
            pass
        settings_scroller.add(settings_page)
        self.page_stack.add_named(player_page, "player")
        self.page_stack.add_named(settings_scroller, "settings")

        header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=18)
        header.get_style_context().add_class("top-bar")
        header.set_margin_top(18)
        header.set_margin_bottom(14)
        header.set_margin_start(20)
        header.set_margin_end(20)
        player_page.pack_start(header, False, False, 0)

        now_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3)
        now_box.set_hexpand(True)
        header.pack_start(now_box, True, True, 0)

        app_title = Gtk.Label(label=APP_NAME)
        app_title.get_style_context().add_class("app-title")
        app_title.set_halign(Gtk.Align.START)
        now_box.pack_start(app_title, False, False, 0)

        self.now_title_label = Gtk.Label(label="No song selected")
        self.now_title_label.get_style_context().add_class("now-title")
        self.now_title_label.set_halign(Gtk.Align.FILL)
        self.now_title_label.set_xalign(0.0)
        self.now_title_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.now_title_label.set_max_width_chars(1)
        self.now_title_label.set_hexpand(True)
        now_box.pack_start(self.now_title_label, False, False, 0)

        self.now_meta_label = Gtk.Label(label="")
        self.now_meta_label.get_style_context().add_class("now-meta")
        self.now_meta_label.set_halign(Gtk.Align.FILL)
        self.now_meta_label.set_xalign(0.0)
        self.now_meta_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.now_meta_label.set_max_width_chars(1)
        self.now_meta_label.set_hexpand(True)
        now_box.pack_start(self.now_meta_label, False, False, 0)

        self.seek_adjustment = Gtk.Adjustment(
            value=0,
            lower=0,
            upper=1,
            step_increment=1,
            page_increment=10,
        )
        self.seek_scale = Gtk.Scale(
            orientation=Gtk.Orientation.HORIZONTAL,
            adjustment=self.seek_adjustment,
        )
        self.seek_scale.set_draw_value(False)
        self.seek_scale.set_hexpand(True)
        self.seek_scale.set_sensitive(False)
        self.seek_scale.set_tooltip_text("Track position")
        self.seek_scale.connect("button-press-event", self._on_seek_press)
        self.seek_scale.connect("button-release-event", self._on_seek_release)
        self.seek_scale.connect("value-changed", self._on_seek_value_changed)
        now_box.pack_start(self.seek_scale, False, False, 2)

        time_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        now_box.pack_start(time_row, False, False, 0)
        self.elapsed_label = Gtk.Label(label="0:00")
        self.elapsed_label.get_style_context().add_class("muted")
        self.elapsed_label.set_halign(Gtk.Align.START)
        time_row.pack_start(self.elapsed_label, False, False, 0)
        self.duration_label = Gtk.Label(label="--:--")
        self.duration_label.get_style_context().add_class("muted")
        self.duration_label.set_halign(Gtk.Align.END)
        self.duration_label.set_hexpand(True)
        time_row.pack_start(self.duration_label, True, True, 0)

        status_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        now_box.pack_start(status_row, False, False, 0)

        self.state_label = Gtk.Label(label="Paused")
        self.state_label.get_style_context().add_class("muted")
        self.state_label.set_halign(Gtk.Align.FILL)
        self.state_label.set_xalign(0.0)
        self.state_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.state_label.set_max_width_chars(1)
        self.state_label.set_hexpand(True)
        status_row.pack_start(self.state_label, True, True, 0)

        self.playlist_count_label = Gtk.Label()
        self.playlist_count_label.get_style_context().add_class("muted")
        self.playlist_count_label.set_halign(Gtk.Align.START)
        self.playlist_count_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.playlist_count_label.set_max_width_chars(24)
        status_row.pack_start(self.playlist_count_label, False, False, 0)

        action_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        header.pack_start(action_box, False, False, 0)

        transport = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        transport.set_halign(Gtk.Align.END)
        action_box.pack_start(transport, False, False, 0)

        previous_button = make_icon_button("media-skip-backward-symbolic", "Previous")
        previous_button.connect("clicked", lambda _button: self._previous_track())
        transport.pack_start(previous_button, False, False, 0)

        self.play_button = make_icon_button("media-playback-start-symbolic", "Play", primary=True)
        self.play_button.connect("clicked", lambda _button: self._toggle_playback())
        transport.pack_start(self.play_button, False, False, 0)

        stop_button = make_icon_button("media-playback-stop-symbolic", "Stop")
        stop_button.connect("clicked", lambda _button: self.media_stop())
        transport.pack_start(stop_button, False, False, 0)

        next_button = make_icon_button("media-skip-forward-symbolic", "Next")
        next_button.connect("clicked", lambda _button: self._skip_track())
        transport.pack_start(next_button, False, False, 0)

        settings_button = make_text_button("Settings")
        settings_button.connect("clicked", lambda _button: self._show_settings())
        action_box.pack_start(settings_button, False, False, 0)

        separator = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
        player_page.pack_start(separator, False, False, 0)

        player_visualizer_panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        player_visualizer_panel.get_style_context().add_class("visualizer-panel")
        player_visualizer_panel.set_margin_start(20)
        player_visualizer_panel.set_margin_end(20)
        player_visualizer_panel.set_margin_top(14)
        player_visualizer_panel.set_margin_bottom(18)
        player_page.pack_start(player_visualizer_panel, True, True, 0)

        player_visualizer_header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        player_visualizer_panel.pack_start(player_visualizer_header, False, False, 0)

        player_visualizer_title = Gtk.Label(label="Real-time analysis")
        player_visualizer_title.get_style_context().add_class("section-title")
        player_visualizer_title.set_halign(Gtk.Align.START)
        player_visualizer_header.pack_start(player_visualizer_title, False, False, 0)

        self.visualizer_status_label = Gtk.Label(label=self._visualizer_default_text())
        self.visualizer_status_label.get_style_context().add_class("muted")
        self.visualizer_status_label.set_halign(Gtk.Align.FILL)
        self.visualizer_status_label.set_xalign(1.0)
        self.visualizer_status_label.set_hexpand(True)
        self.visualizer_status_label.set_ellipsize(Pango.EllipsizeMode.START)
        self.visualizer_status_label.set_max_width_chars(1)
        player_visualizer_header.pack_start(self.visualizer_status_label, True, True, 0)

        self.visualizer_view = VisualizerView()
        self.visualizer_view.set_visualization_settings(self.visualization_settings)
        self.visualizer_view.set_vexpand(True)
        self.visualizer_view.set_size_request(-1, 320)
        player_visualizer_panel.pack_start(self.visualizer_view, True, True, 0)

        settings_header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        settings_header.set_margin_top(14)
        settings_header.set_margin_bottom(10)
        settings_header.set_margin_start(18)
        settings_header.set_margin_end(18)
        settings_page.pack_start(settings_header, False, False, 0)

        back_button = make_text_button("Back")
        back_button.connect("clicked", lambda _button: self._show_player())
        settings_header.pack_start(back_button, False, False, 0)

        settings_title = Gtk.Label(label="Settings")
        settings_title.get_style_context().add_class("section-title")
        settings_title.set_halign(Gtk.Align.START)
        settings_header.pack_start(settings_title, False, False, 0)

        visualizer_panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        visualizer_panel.get_style_context().add_class("visualizer-panel")
        visualizer_panel.set_margin_start(20)
        visualizer_panel.set_margin_end(20)
        visualizer_panel.set_margin_top(14)
        visualizer_panel.set_margin_bottom(14)
        settings_page.pack_start(visualizer_panel, False, True, 0)

        visualizer_header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        visualizer_panel.pack_start(visualizer_header, False, False, 0)

        visualizer_title = Gtk.Label(label="Visualization")
        visualizer_title.get_style_context().add_class("section-title")
        visualizer_title.set_halign(Gtk.Align.START)
        visualizer_header.pack_start(visualizer_title, False, False, 0)

        visualizer_description = Gtk.Label(label="Display and analysis controls")
        visualizer_description.get_style_context().add_class("muted")
        visualizer_description.set_halign(Gtk.Align.END)
        visualizer_description.set_hexpand(True)
        visualizer_header.pack_start(visualizer_description, True, True, 0)

        visualizer_controls = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=14)
        visualizer_controls.get_style_context().add_class("visualizer-controls")
        visualizer_panel.pack_start(visualizer_controls, False, False, 0)

        self.visual_rate_label, self.visual_rate_scale = self._add_compact_scale(
            visualizer_controls,
            "Rate",
            5,
            144,
            round(self.visualization_settings.update_fps),
            lambda value: f"{value:.0f} FPS",
            self._on_visual_update_rate_changed,
        )

        self.visual_window_label, self.visual_window_scale = self._add_compact_scale(
            visualizer_controls,
            "Waveform",
            10,
            500,
            round(self.visualization_settings.waveform_window_ms),
            lambda value: f"{value:.0f} ms",
            self._on_visual_waveform_window_changed,
        )

        self.fft_resolution_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3)
        self.fft_resolution_box.set_size_request(145, -1)
        visualizer_controls.pack_start(self.fft_resolution_box, False, False, 0)

        fft_resolution_label = Gtk.Label(label="FFT resolution")
        fft_resolution_label.set_halign(Gtk.Align.START)
        fft_resolution_label.set_xalign(0.0)
        self.fft_resolution_box.pack_start(fft_resolution_label, False, False, 0)

        self.fft_resolution_combo = Gtk.ComboBoxText()
        for size in FFT_SIZE_OPTIONS:
            self.fft_resolution_combo.append(str(size), str(size))
        self.fft_resolution_combo.set_active_id(str(self.visualization_settings.fft_size))
        self.fft_resolution_combo.connect("changed", self._on_fft_resolution_changed)
        self.fft_resolution_box.pack_start(self.fft_resolution_combo, False, False, 0)

        self.visual_columns_label, self.visual_columns_scale = self._add_compact_scale(
            visualizer_controls,
            "Display bars",
            24,
            256,
            round(self.visualization_settings.fft_columns),
            lambda value: f"{value:.0f}",
            self._on_visual_fft_columns_changed,
        )

        self.visual_smoothing_label, self.visual_smoothing_scale = self._add_compact_scale(
            visualizer_controls,
            "FFT smoothing",
            0,
            100,
            round(self.visualization_settings.fft_smoothing),
            lambda value: f"{value:.0f}%",
            self._on_visual_fft_smoothing_changed,
        )

        fft_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3)
        fft_box.set_size_request(150, -1)
        visualizer_controls.pack_start(fft_box, False, False, 0)

        fft_label = Gtk.Label(label="FFT scale")
        fft_label.set_halign(Gtk.Align.START)
        fft_label.set_xalign(0.0)
        fft_box.pack_start(fft_label, False, False, 0)

        self.fft_scale_combo = Gtk.ComboBoxText()
        self.fft_scale_combo.append("log", "Log")
        self.fft_scale_combo.append("linear", "Linear")
        self.fft_scale_combo.set_active_id(self.visualization_settings.fft_scale)
        self.fft_scale_combo.connect("changed", self._on_fft_scale_changed)
        fft_box.pack_start(self.fft_scale_combo, False, False, 0)

        self.cache_status_label = Gtk.Label(label=self._cache_status_text())
        self.cache_status_label.get_style_context().add_class("muted")
        self.cache_status_label.set_halign(Gtk.Align.FILL)
        self.cache_status_label.set_xalign(0.0)
        self.cache_status_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.cache_status_label.set_max_width_chars(1)
        self.cache_status_label.set_hexpand(True)
        self.cache_status_label.set_tooltip_text(self.cache_status_label.get_text())
        visualizer_panel.pack_start(self.cache_status_label, False, False, 0)

        visual_separator = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
        settings_page.pack_start(visual_separator, False, False, 0)

        content = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL)
        content.set_wide_handle(True)
        content.set_position(700)
        settings_page.pack_start(content, True, True, 0)

        playlist_panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        playlist_panel.set_margin_top(16)
        playlist_panel.set_margin_bottom(16)
        playlist_panel.set_margin_start(18)
        playlist_panel.set_margin_end(12)
        playlist_panel.set_size_request(460, -1)
        content.add1(playlist_panel)

        playlist_header = Gtk.Label(label="Playlist editor")
        playlist_header.get_style_context().add_class("section-title")
        playlist_header.set_halign(Gtk.Align.START)
        playlist_panel.pack_start(playlist_header, False, False, 0)

        playlist_switcher = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        playlist_panel.pack_start(playlist_switcher, False, False, 0)

        self.playlist_selector = Gtk.ComboBoxText()
        self.playlist_selector.set_hexpand(True)
        self.playlist_selector.connect("changed", self._on_playlist_selected)
        playlist_switcher.pack_start(self.playlist_selector, True, True, 0)

        new_playlist = make_text_button("New")
        new_playlist.connect("clicked", lambda _button: self._create_playlist())
        playlist_switcher.pack_start(new_playlist, False, False, 0)

        rename_playlist = make_text_button("Rename")
        rename_playlist.connect("clicked", lambda _button: self._rename_playlist())
        playlist_switcher.pack_start(rename_playlist, False, False, 0)

        delete_playlist = make_text_button("Delete")
        delete_playlist.connect("clicked", lambda _button: self._delete_playlist())
        playlist_switcher.pack_start(delete_playlist, False, False, 0)

        library_buttons = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        playlist_panel.pack_start(library_buttons, False, False, 0)

        add_files = make_text_button("Add files")
        add_files.connect("clicked", lambda _button: self._choose_files())
        library_buttons.pack_start(add_files, False, False, 0)

        add_folder = make_text_button("Add folder")
        add_folder.connect("clicked", lambda _button: self._choose_folder())
        library_buttons.pack_start(add_folder, False, False, 0)

        add_from_server = make_text_button("Add from server")
        add_from_server.connect("clicked", lambda _button: self._open_server_library_dialog())
        library_buttons.pack_start(add_from_server, False, False, 0)

        ask_liam = make_text_button("Ask Liam")
        ask_liam.connect("clicked", lambda _button: self._open_ask_liam_dialog())
        library_buttons.pack_start(ask_liam, False, False, 0)

        shuffle = make_text_button("Shuffle")
        shuffle.connect("clicked", lambda _button: self._shuffle_playlist())
        library_buttons.pack_start(shuffle, False, False, 0)

        clear = make_text_button("Clear")
        clear.connect("clicked", lambda _button: self._clear_playlist())
        library_buttons.pack_start(clear, False, False, 0)

        shared_playlists = make_text_button("Shared playlists")
        shared_playlists.connect("clicked", lambda _button: self._open_shared_playlists())
        playlist_panel.pack_start(shared_playlists, False, False, 0)

        self.shuffle_toggle = Gtk.CheckButton(label="Shuffle playback")
        self.shuffle_toggle.set_active(self.shuffle_enabled)
        self.shuffle_toggle.connect("toggled", self._on_shuffle_toggled)
        playlist_panel.pack_start(self.shuffle_toggle, False, False, 0)

        notebook = Gtk.Notebook()
        notebook.set_hexpand(True)
        notebook.set_vexpand(True)
        playlist_panel.pack_start(notebook, True, True, 0)

        self.file_list = Gtk.ListBox()
        self.file_list.set_selection_mode(Gtk.SelectionMode.NONE)
        tracks_scroller = self._make_scroller(self.file_list)
        notebook.append_page(tracks_scroller, Gtk.Label(label="Tracks"))

        self.folder_list = Gtk.ListBox()
        self.folder_list.set_selection_mode(Gtk.SelectionMode.NONE)
        folders_scroller = self._make_scroller(self.folder_list)
        notebook.append_page(folders_scroller, Gtk.Label(label="Folders"))

        level_scroller = Gtk.ScrolledWindow()
        level_scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        level_scroller.set_hexpand(True)
        level_scroller.set_vexpand(True)
        level_scroller.set_min_content_width(390)
        level_scroller.set_min_content_height(260)
        content.add2(level_scroller)

        level_panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=9)
        level_panel.get_style_context().add_class("level-panel")
        level_panel.set_margin_top(16)
        level_panel.set_margin_bottom(16)
        level_panel.set_margin_start(12)
        level_panel.set_margin_end(18)
        level_scroller.add(level_panel)

        level_title = Gtk.Label(label="Leveling")
        level_title.get_style_context().add_class("section-title")
        level_title.set_halign(Gtk.Align.START)
        level_panel.pack_start(level_title, False, False, 0)

        self.output_label, self.output_scale = self._add_scale(
            level_panel,
            "Output level",
            10,
            100,
            round(self.output_level * 100),
            lambda value: f"{value:.0f}%",
            self._on_output_level_changed,
        )

        self.strength_label, self.strength_scale = self._add_scale(
            level_panel,
            "Leveling strength",
            0,
            100,
            round(self.leveling_strength * 100),
            lambda value: f"{value:.0f}%",
            self._on_leveling_strength_changed,
        )

        advanced_title = Gtk.Label(label="Advanced")
        advanced_title.get_style_context().add_class("subtle-heading")
        advanced_title.set_halign(Gtk.Align.START)
        advanced_title.set_margin_top(8)
        level_panel.pack_start(advanced_title, False, False, 0)
        self._add_advanced_controls(level_panel)
        self._add_latency_controls(level_panel)
        self._refresh_playlist_selector()
        self._refresh_microphones()
        self._refresh_saved_speaker_latencies()
        self._start_latency_route_check(probe_if_changed=True)
        self.page_stack.set_visible_child_name("player")

    def _add_advanced_controls(self, parent: Gtk.Box) -> None:
        self._add_scale(
            parent,
            "Startup scan",
            0,
            45,
            round(self.leveling_settings.analysis_seconds),
            lambda value: "Off" if value == 0 else f"{value:.0f} s",
            lambda value: self._update_leveling_settings(analysis_seconds=value),
        )
        self._add_scale(
            parent,
            "Attack",
            1,
            250,
            round(self.leveling_settings.level_attack_ms),
            lambda value: f"{value:.0f} ms",
            lambda value: self._update_leveling_settings(level_attack_ms=value),
        )
        self._add_scale(
            parent,
            "Decay",
            100,
            5000,
            round(self.leveling_settings.level_release_ms),
            lambda value: f"{value:.0f} ms",
            lambda value: self._update_leveling_settings(level_release_ms=value),
        )
        self._add_scale(
            parent,
            "Cut speed",
            5,
            500,
            round(self.leveling_settings.gain_down_ms),
            lambda value: f"{value:.0f} ms",
            lambda value: self._update_leveling_settings(gain_down_ms=value),
        )
        self._add_scale(
            parent,
            "Recovery",
            5,
            100,
            round(self.leveling_settings.gain_up_ms / 100),
            lambda value: f"{value / 10:.1f} s",
            lambda value: self._update_leveling_settings(gain_up_ms=value * 100),
        )
        self._add_scale(
            parent,
            "Compressor threshold",
            30,
            95,
            round(self.leveling_settings.compressor_threshold * 100),
            lambda value: f"{value:.0f}%",
            lambda value: self._update_leveling_settings(compressor_threshold=value / 100),
        )
        self._add_scale(
            parent,
            "Output ceiling",
            50,
            100,
            round(self.leveling_settings.output_ceiling * 100),
            lambda value: f"{value:.0f}%",
            lambda value: self._update_leveling_settings(output_ceiling=value / 100),
        )

    def _add_latency_controls(self, parent: Gtk.Box) -> None:
        separator = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
        separator.set_margin_top(10)
        separator.set_margin_bottom(6)
        parent.pack_start(separator, False, False, 0)

        title = Gtk.Label(label="Speaker latency")
        title.get_style_context().add_class("subtle-heading")
        title.set_halign(Gtk.Align.START)
        parent.pack_start(title, False, False, 0)

        self.latency_output_label = Gtk.Label(label="Current output: checking…")
        self.latency_output_label.set_halign(Gtk.Align.FILL)
        self.latency_output_label.set_xalign(0.0)
        self.latency_output_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.latency_output_label.set_max_width_chars(1)
        parent.pack_start(self.latency_output_label, False, False, 0)

        self.latency_applied_label = Gtk.Label(label="Visualization delay: checking…")
        self.latency_applied_label.get_style_context().add_class("muted")
        self.latency_applied_label.set_halign(Gtk.Align.START)
        self.latency_applied_label.set_xalign(0.0)
        self.latency_applied_label.set_line_wrap(True)
        parent.pack_start(self.latency_applied_label, False, False, 0)

        microphone_label = Gtk.Label(label="Calibration microphone")
        microphone_label.set_halign(Gtk.Align.START)
        parent.pack_start(microphone_label, False, False, 0)

        self.latency_microphone_combo = Gtk.ComboBoxText()
        self.latency_microphone_combo.set_hexpand(True)
        self.latency_microphone_combo.connect("changed", self._on_latency_microphone_changed)
        parent.pack_start(self.latency_microphone_combo, False, False, 0)

        self.latency_system_button = make_text_button("Refresh system estimate")
        self.latency_system_button.connect("clicked", lambda _button: self._start_system_latency_probe())
        parent.pack_start(self.latency_system_button, False, False, 0)

        self.latency_calibrate_button = make_text_button("Calibrate with microphone")
        self.latency_calibrate_button.connect("clicked", lambda _button: self._confirm_latency_calibration())
        parent.pack_start(self.latency_calibrate_button, False, False, 0)

        disclosure = Gtk.Label(
            label=(
                "The system estimate comes from the local audio stack. Microphone calibration measures "
                "the complete speaker path; microphone audio is processed only in memory and is never saved or uploaded."
            )
        )
        disclosure.get_style_context().add_class("muted")
        disclosure.set_halign(Gtk.Align.START)
        disclosure.set_xalign(0.0)
        disclosure.set_line_wrap(True)
        parent.pack_start(disclosure, False, False, 0)

        saved_title = Gtk.Label(label="Saved speaker calibrations")
        saved_title.get_style_context().add_class("subtle-heading")
        saved_title.set_halign(Gtk.Align.START)
        saved_title.set_margin_top(8)
        parent.pack_start(saved_title, False, False, 0)

        self.latency_saved_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        parent.pack_start(self.latency_saved_box, False, False, 0)

        self.latency_clear_all_button = make_text_button("Clear all speaker calibrations")
        self.latency_clear_all_button.connect("clicked", lambda _button: self._clear_all_speaker_latencies())
        parent.pack_start(self.latency_clear_all_button, False, False, 0)

    def _add_scale(
        self,
        parent: Gtk.Box,
        name: str,
        minimum: int,
        maximum: int,
        initial: int,
        formatter: Callable[[float], str],
        on_changed: Callable[[float], None],
    ) -> tuple[Gtk.Label, Gtk.Scale]:
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        row.set_margin_top(1)
        row.set_margin_bottom(1)
        parent.pack_start(row, False, False, 0)

        label = Gtk.Label()
        label.set_halign(Gtk.Align.START)
        label.set_width_chars(26)
        label.set_xalign(0.0)
        row.pack_start(label, False, False, 0)

        adjustment = Gtk.Adjustment(
            value=max(minimum, min(maximum, initial)),
            lower=minimum,
            upper=maximum,
            step_increment=1,
            page_increment=max(1, (maximum - minimum) // 10),
        )
        scale = Gtk.Scale(orientation=Gtk.Orientation.HORIZONTAL, adjustment=adjustment)
        scale.set_draw_value(False)
        scale.set_digits(0)
        scale.set_hexpand(True)
        row.pack_start(scale, True, True, 0)

        def update_label(value: float) -> None:
            label.set_text(f"{name}: {formatter(value)}")

        update_label(adjustment.get_value())

        def changed(_scale: Gtk.Scale) -> None:
            value = adjustment.get_value()
            update_label(value)
            on_changed(value)

        scale.connect("value-changed", changed)
        return label, scale

    def _add_compact_scale(
        self,
        parent: Gtk.Box,
        name: str,
        minimum: int,
        maximum: int,
        initial: int,
        formatter: Callable[[float], str],
        on_changed: Callable[[float], None],
    ) -> tuple[Gtk.Label, Gtk.Scale]:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3)
        box.set_hexpand(True)
        parent.pack_start(box, True, True, 0)

        label = Gtk.Label()
        label.set_halign(Gtk.Align.START)
        label.set_xalign(0.0)
        box.pack_start(label, False, False, 0)

        adjustment = Gtk.Adjustment(
            value=max(minimum, min(maximum, initial)),
            lower=minimum,
            upper=maximum,
            step_increment=1,
            page_increment=max(1, (maximum - minimum) // 10),
        )
        scale = Gtk.Scale(orientation=Gtk.Orientation.HORIZONTAL, adjustment=adjustment)
        scale.set_draw_value(False)
        scale.set_digits(0)
        scale.set_hexpand(True)
        box.pack_start(scale, False, False, 0)

        def update_label(value: float) -> None:
            label.set_text(f"{name}: {formatter(value)}")

        update_label(adjustment.get_value())

        def changed(_scale: Gtk.Scale) -> None:
            value = adjustment.get_value()
            update_label(value)
            on_changed(value)

        scale.connect("value-changed", changed)
        return label, scale

    def _make_scroller(self, list_box: Gtk.ListBox) -> Gtk.ScrolledWindow:
        scroller = Gtk.ScrolledWindow()
        scroller.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
        scroller.set_vexpand(True)
        scroller.set_hexpand(True)
        scroller.add(list_box)
        return scroller

    def restore_saved_placement(self) -> None:
        state = self.window_state
        x = state.x
        y = state.y
        if state.maximized and state.monitor_width > 0 and state.monitor_height > 0:
            x = state.monitor_x + 80
            y = state.monitor_y + 80
        if self._point_is_on_display(x, y):
            self.move(x, y)

        if state.maximized:
            GLib.idle_add(self._maximize_after_show)

    def _maximize_after_show(self) -> bool:
        self.maximize()
        return False

    def _on_configure_event(self, _widget: Gtk.Widget, _event: Gdk.EventConfigure) -> bool:
        if not self._is_maximized:
            self._remember_window_state(write=False)
        return False

    def _on_window_state_event(self, _widget: Gtk.Widget, event: Gdk.EventWindowState) -> bool:
        self._is_maximized = bool(event.new_window_state & Gdk.WindowState.MAXIMIZED)
        self._remember_window_state(write=False)
        return False

    def _remember_window_state(self, write: bool) -> None:
        window = self.get_window()
        if window is None:
            return

        monitor_geometry = self._current_monitor_geometry()
        if self._is_maximized:
            x = self.window_state.x
            y = self.window_state.y
            width = self.window_state.width
            height = self.window_state.height
        else:
            x, y = self.get_position()
            width, height = self.get_size()

        self.window_state = WindowState(
            x=max(-20000, int(x)),
            y=max(-20000, int(y)),
            width=max(480, int(width)),
            height=max(620, int(height)),
            maximized=self._is_maximized,
            monitor_x=monitor_geometry.x,
            monitor_y=monitor_geometry.y,
            monitor_width=monitor_geometry.width,
            monitor_height=monitor_geometry.height,
        )
        if write:
            self._save_state()

    def _current_monitor_geometry(self) -> Gdk.Rectangle:
        display = Gdk.Display.get_default()
        window = self.get_window()
        monitor = None
        if display is not None and window is not None:
            try:
                monitor = display.get_monitor_at_window(window)
            except Exception:
                monitor = None
        if monitor is None and display is not None and display.get_n_monitors() > 0:
            monitor = display.get_monitor(0)
        if monitor is not None:
            return monitor.get_geometry()
        return Gdk.Rectangle()

    def _point_is_on_display(self, x: int, y: int) -> bool:
        display = Gdk.Display.get_default()
        if display is None:
            return True
        for index in range(display.get_n_monitors()):
            geometry = display.get_monitor(index).get_geometry()
            if geometry.x <= x < geometry.x + geometry.width and geometry.y <= y < geometry.y + geometry.height:
                return True
        return False

    def _show_settings(self) -> None:
        self.page_stack.set_visible_child_name("settings")
        self._refresh_microphones()
        self._start_latency_route_check(probe_if_changed=False)
        self._start_system_latency_probe()

    def _show_player(self) -> None:
        self.page_stack.set_visible_child_name("player")

    def _refresh_microphones(self) -> None:
        if not hasattr(self, "latency_microphone_combo"):
            return
        choices = latency.microphones()
        selected = self.selected_microphone
        self.latency_microphone_combo.remove_all()
        for microphone in choices:
            suffix = " (default)" if microphone.default else ""
            self.latency_microphone_combo.append(microphone.key, microphone.label + suffix)
        available = {microphone.key for microphone in choices}
        if selected not in available:
            selected = choices[0].key if choices else ""
        self.selected_microphone = selected
        if selected:
            self.latency_microphone_combo.set_active_id(selected)
        elif not choices:
            self.latency_microphone_combo.append("", "No microphone found")
            self.latency_microphone_combo.set_active(0)
        self._update_latency_controls()

    def _on_latency_microphone_changed(self, combo: Gtk.ComboBoxText) -> None:
        selected = combo.get_active_id() or ""
        if selected == self.selected_microphone:
            return
        self.selected_microphone = selected
        self._save_state()
        self._update_latency_controls()

    def _on_latency_route_tick(self) -> bool:
        if self._destroyed:
            return False
        self._start_latency_route_check(probe_if_changed=True)
        return True

    def _start_latency_route_check(self, probe_if_changed: bool) -> None:
        if self._destroyed or self._latency_route_check_running or self._latency_calibrating:
            return
        self._latency_route_check_running = True

        def worker() -> None:
            route = latency.current_output()
            GLib.idle_add(self._on_latency_route_checked, route, probe_if_changed)

        threading.Thread(target=worker, name="FredPlayerOutputRoute", daemon=True).start()

    def _on_latency_route_checked(
        self,
        route: latency.AudioOutput,
        probe_if_changed: bool,
    ) -> bool:
        self._latency_route_check_running = False
        if self._destroyed:
            return False
        changed = self._latency_route is None or route.key != self._latency_route.key
        self._latency_route = route
        if changed:
            self._latency_system_ms = None
            self._latency_system_route_key = ""
        self._apply_output_latency()
        self._update_latency_controls()
        if changed and probe_if_changed:
            self._start_system_latency_probe()
        return False

    def _start_system_latency_probe(self) -> None:
        if self._destroyed or self._latency_probe_running or self._latency_calibrating:
            return
        route = self._latency_route
        if route is None:
            self._start_latency_route_check(probe_if_changed=True)
            return
        self._latency_probe_running = True
        self._update_latency_controls()

        def worker() -> None:
            try:
                measured_ms = latency.probe_system_latency(route)
                error = ""
            except Exception as exc:
                measured_ms = None
                error = str(exc).strip() or "The audio system did not report latency"
            GLib.idle_add(self._on_system_latency_probed, route, measured_ms, error)

        threading.Thread(target=worker, name="FredPlayerLatencyProbe", daemon=True).start()

    def _on_system_latency_probed(
        self,
        route: latency.AudioOutput,
        measured_ms: int | None,
        error: str,
    ) -> bool:
        self._latency_probe_running = False
        if self._destroyed:
            return False
        if self._latency_route is not None and route.key == self._latency_route.key:
            self._latency_system_ms = measured_ms
            self._latency_system_route_key = route.key if measured_ms is not None else ""
            self._apply_output_latency()
            if error:
                self.latency_applied_label.set_tooltip_text(error)
        self._update_latency_controls()
        return False

    def _apply_output_latency(self) -> None:
        route = self._latency_route
        delay_ms = 0
        source = "no estimate"
        if route is not None:
            calibration = self.speaker_latencies.get(route.key)
            if calibration is not None:
                delay_ms = calibration.delay_ms
                source = "microphone calibration"
            elif self._latency_system_route_key == route.key and self._latency_system_ms is not None:
                delay_ms = self._latency_system_ms
                source = "system estimate"
        self.player.set_visual_delay_ms(delay_ms)
        if hasattr(self, "latency_applied_label"):
            system_text = (
                f"{self._latency_system_ms} ms"
                if route is not None
                and self._latency_system_route_key == route.key
                and self._latency_system_ms is not None
                else "unavailable"
            )
            self.latency_applied_label.set_text(
                f"Visualization delay: {delay_ms} ms ({source}); system reports {system_text}"
            )

    def _update_latency_controls(self) -> None:
        if not hasattr(self, "latency_output_label"):
            return
        route = self._latency_route
        route_text = route.label if route is not None else "checking…"
        if route is not None and route.bluetooth:
            route_text += " (Bluetooth)"
        self.latency_output_label.set_text(f"Current output: {route_text}")
        self.latency_output_label.set_tooltip_text(route_text)
        busy = self._latency_probe_running or self._latency_calibrating
        self.latency_system_button.set_sensitive(not busy and route is not None)
        self.latency_system_button.set_label(
            "Reading system estimate…" if self._latency_probe_running else "Refresh system estimate"
        )
        self.latency_calibrate_button.set_sensitive(
            not busy and bool(self.selected_microphone) and route is not None
        )
        self.latency_calibrate_button.set_label(
            "Calibrating…" if self._latency_calibrating else "Calibrate with microphone"
        )
        self.latency_clear_all_button.set_sensitive(bool(self.speaker_latencies) and not busy)
        self._apply_output_latency()

    def _confirm_latency_calibration(self) -> None:
        if self._latency_calibrating or not self.selected_microphone:
            return
        route = self._latency_route
        if route is None:
            self._set_state("No audio output is available")
            return
        dialog = Gtk.MessageDialog(
            transient_for=self,
            flags=Gtk.DialogFlags.MODAL,
            message_type=Gtk.MessageType.QUESTION,
            buttons=Gtk.ButtonsType.CANCEL,
            text=f'Calibrate speaker delay for "{route.label}"?',
        )
        dialog.format_secondary_text(
            "FredPlayer will pause music and play a short audible chirp. Keep the selected microphone near the "
            "speaker in a quiet room. Microphone audio is processed only in memory and is never saved or uploaded."
        )
        dialog.add_button("Start calibration", Gtk.ResponseType.OK)
        try:
            if dialog.run() != Gtk.ResponseType.OK:
                return
        finally:
            dialog.destroy()

        self._resume_after_latency_calibration = self.playback_requested
        if self._resume_after_latency_calibration:
            self._pause_playback()
        self._latency_calibrating = True
        self._set_state("Calibrating speaker latency")
        self._update_latency_controls()
        microphone_key = self.selected_microphone

        def worker() -> None:
            try:
                result = latency.calibrate_with_microphone(microphone_key)
                error = ""
            except Exception as exc:
                result = None
                error = str(exc).strip() or "Speaker latency calibration failed"
            GLib.idle_add(self._on_latency_calibration_finished, result, error)

        threading.Thread(target=worker, name="FredPlayerLatencyCalibration", daemon=True).start()

    def _on_latency_calibration_finished(
        self,
        result: latency.CalibrationResult | None,
        error: str,
    ) -> bool:
        self._latency_calibrating = False
        if self._destroyed:
            return False
        if result is not None:
            self.speaker_latencies[result.output.key] = SpeakerLatency(
                key=result.output.key,
                label=result.output.label,
                delay_ms=result.delay_ms,
            )
            self._latency_route = result.output
            self._save_state()
            self._refresh_saved_speaker_latencies()
            self._apply_output_latency()
            self._set_state(f"Speaker latency calibrated: {result.delay_ms} ms")
        else:
            self._set_state(error)
        self._update_latency_controls()
        if self._resume_after_latency_calibration:
            self._resume_after_latency_calibration = False
            self._start_or_resume()
        return False

    def _refresh_saved_speaker_latencies(self) -> None:
        if not hasattr(self, "latency_saved_box"):
            return
        for child in list(self.latency_saved_box.get_children()):
            self.latency_saved_box.remove(child)
        for calibration in sorted(self.speaker_latencies.values(), key=lambda item: item.label.casefold()):
            row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
            label = Gtk.Label(label=f"{calibration.label} · {calibration.delay_ms} ms")
            label.set_halign(Gtk.Align.START)
            label.set_hexpand(True)
            label.set_ellipsize(Pango.EllipsizeMode.END)
            label.set_max_width_chars(1)
            row.pack_start(label, True, True, 0)
            clear = make_text_button("Clear")
            clear.connect(
                "clicked",
                lambda _button, key=calibration.key: self._confirm_clear_speaker_latency(key),
            )
            row.pack_start(clear, False, False, 0)
            self.latency_saved_box.pack_start(row, False, False, 0)
        if not self.speaker_latencies:
            empty = Gtk.Label(label="No saved speaker calibrations")
            empty.get_style_context().add_class("muted")
            empty.set_halign(Gtk.Align.START)
            self.latency_saved_box.pack_start(empty, False, False, 0)
        self.latency_saved_box.show_all()
        self._update_latency_controls()

    def _confirm_clear_speaker_latency(self, key: str) -> None:
        calibration = self.speaker_latencies.get(key)
        if calibration is None:
            return
        dialog = Gtk.MessageDialog(
            transient_for=self,
            flags=Gtk.DialogFlags.MODAL,
            message_type=Gtk.MessageType.QUESTION,
            buttons=Gtk.ButtonsType.CANCEL,
            text=f'Clear calibration for "{calibration.label}"?',
        )
        dialog.add_button("Clear", Gtk.ResponseType.OK)
        try:
            if dialog.run() != Gtk.ResponseType.OK:
                return
        finally:
            dialog.destroy()
        self.speaker_latencies.pop(key, None)
        self._save_state()
        self._refresh_saved_speaker_latencies()
        self._apply_output_latency()

    def _clear_all_speaker_latencies(self) -> None:
        if not self.speaker_latencies:
            return
        dialog = Gtk.MessageDialog(
            transient_for=self,
            flags=Gtk.DialogFlags.MODAL,
            message_type=Gtk.MessageType.QUESTION,
            buttons=Gtk.ButtonsType.CANCEL,
            text="Clear all saved speaker calibrations?",
        )
        dialog.add_button("Clear all", Gtk.ResponseType.OK)
        try:
            if dialog.run() != Gtk.ResponseType.OK:
                return
        finally:
            dialog.destroy()
        self.speaker_latencies.clear()
        self._save_state()
        self._refresh_saved_speaker_latencies()
        self._apply_output_latency()

    def _on_key_press(self, _widget: Gtk.Widget, event: Gdk.EventKey) -> bool:
        if event.keyval == Gdk.KEY_Escape and self.page_stack.get_visible_child_name() == "settings":
            self._show_player()
            return True
        return False

    def _on_seek_press(self, _scale: Gtk.Scale, _event: Gdk.EventButton) -> bool:
        self._seek_dragging = True
        return False

    def _on_seek_release(self, _scale: Gtk.Scale, _event: Gdk.EventButton) -> bool:
        if not self._seek_dragging:
            return False
        self._seek_dragging = False
        self.media_seek(round(self.seek_adjustment.get_value()))
        return False

    def _on_seek_value_changed(self, _scale: Gtk.Scale) -> None:
        if self._seek_dragging:
            self.elapsed_label.set_text(format_time_ms(round(self.seek_adjustment.get_value())))

    def _on_progress_tick(self) -> bool:
        if self._destroyed:
            return False
        position_ms, duration_ms = self.media_progress()
        seekable = bool(self.current_path and duration_ms > 0)
        self.seek_scale.set_sensitive(seekable)
        if duration_ms > 0:
            self.seek_adjustment.set_upper(max(1, duration_ms))
            self.seek_adjustment.set_step_increment(1000)
            self.seek_adjustment.set_page_increment(10_000)
            self.duration_label.set_text(format_time_ms(duration_ms))
            if not self._seek_dragging:
                self.seek_adjustment.set_value(min(position_ms, duration_ms))
                self.elapsed_label.set_text(format_time_ms(position_ms))
        else:
            self.duration_label.set_text("--:--")
            if not self._seek_dragging:
                self.seek_adjustment.set_upper(1)
                self.seek_adjustment.set_value(0)
                self.elapsed_label.set_text("0:00")
        return True

    def _sync_active_playlist(self) -> None:
        self.named_playlists[self.active_playlist_name] = self.playlist

    def _refresh_playlist_selector(self) -> None:
        if not hasattr(self, "playlist_selector"):
            return
        self._playlist_selector_updating = True
        try:
            self.playlist_selector.remove_all()
            for name in self.named_playlists:
                self.playlist_selector.append(name, name)
            self.playlist_selector.set_active_id(self.active_playlist_name)
        finally:
            self._playlist_selector_updating = False

    def _prompt_playlist_name(self, title: str, initial: str = "") -> str | None:
        dialog = Gtk.Dialog(title=title, transient_for=self, flags=Gtk.DialogFlags.MODAL)
        dialog.add_buttons(Gtk.STOCK_CANCEL, Gtk.ResponseType.CANCEL, "Save", Gtk.ResponseType.OK)
        entry = Gtk.Entry()
        entry.set_text(initial)
        entry.set_activates_default(True)
        entry.set_margin_top(12)
        entry.set_margin_bottom(12)
        entry.set_margin_start(12)
        entry.set_margin_end(12)
        dialog.get_content_area().add(entry)
        dialog.set_default_response(Gtk.ResponseType.OK)
        dialog.show_all()
        try:
            if dialog.run() != Gtk.ResponseType.OK:
                return None
            name = entry.get_text().strip()
            if not name:
                self._set_state("Playlist name cannot be empty")
                return None
            return name
        finally:
            dialog.destroy()

    def _create_playlist(self) -> None:
        name = self._prompt_playlist_name("Create playlist")
        if name is None:
            return
        if name in self.named_playlists:
            self._set_state(f'Playlist "{name}" already exists')
            return
        self._sync_active_playlist()
        self.named_playlists[name] = []
        self._switch_playlist(name, f'Created playlist "{name}"')

    def _rename_playlist(self) -> None:
        old_name = self.active_playlist_name
        new_name = self._prompt_playlist_name("Rename playlist", old_name)
        if new_name is None or new_name == old_name:
            return
        if new_name in self.named_playlists:
            self._set_state(f'Playlist "{new_name}" already exists')
            return
        self._sync_active_playlist()
        self.named_playlists = {
            (new_name if name == old_name else name): entries
            for name, entries in self.named_playlists.items()
        }
        self.active_playlist_name = new_name
        self.playlist = self.named_playlists[new_name]
        self._save_state()
        self._refresh_playlist_selector()
        self._update_transport()
        self._set_state(f'Renamed playlist to "{new_name}"')

    def _delete_playlist(self) -> None:
        name = self.active_playlist_name
        dialog = Gtk.MessageDialog(
            transient_for=self,
            flags=Gtk.DialogFlags.MODAL,
            message_type=Gtk.MessageType.WARNING,
            buttons=Gtk.ButtonsType.CANCEL,
            text=f'Delete playlist "{name}"?',
        )
        dialog.format_secondary_text(
            "The audio files and any shared server copy will remain. Only this device copy is deleted."
        )
        dialog.add_button("Delete", Gtk.ResponseType.OK)
        try:
            if dialog.run() != Gtk.ResponseType.OK:
                return
        finally:
            dialog.destroy()

        self.named_playlists.pop(name, None)
        if not self.named_playlists:
            self.named_playlists[StateStore.DEFAULT_PLAYLIST_NAME] = []
        next_name = next(iter(self.named_playlists))
        self._switch_playlist(next_name, f'Deleted playlist "{name}"')

    def _on_playlist_selected(self, combo: Gtk.ComboBoxText) -> None:
        if self._playlist_selector_updating:
            return
        name = combo.get_active_id()
        if name and name in self.named_playlists and name != self.active_playlist_name:
            self._sync_active_playlist()
            self._switch_playlist(name, f'Switched to playlist "{name}"')

    def _switch_playlist(self, name: str, message: str) -> None:
        self.player.stop()
        self.active_playlist_name = name
        self.playlist = self.named_playlists[name]
        self.current_index = -1
        self.current_path = ""
        self.shuffle_bag = []
        self.track_history = []
        self.playback_requested = False
        self.audio_actually_playing = False
        self.visualizer_view.clear()
        self.visualizer_status_label.set_text(self._visualizer_default_text())
        self._save_state()
        self._refresh_playlist_selector()
        self._update_all()
        self._set_state(message)
        self._schedule_background_precompute()

    def _toggle_playback(self) -> None:
        if not self.playlist:
            self._choose_files()
            return
        if self.playback_requested:
            self._pause_playback()
            return
        self._start_or_resume()

    def _start_or_resume(self) -> None:
        if self.player.is_paused() and self.current_path in {entry.path for entry in self.playlist}:
            self.playback_requested = True
            self.audio_actually_playing = True
            self.player.resume()
            self._set_state("Playing")
            self._update_transport()
            self._notify_media_state()
            return
        self.playback_requested = True
        self._play_random_track()

    def _pause_playback(self) -> None:
        self.playback_requested = False
        self.audio_actually_playing = False
        self.player.pause()
        self._set_state("Paused")
        self._update_transport()
        self._notify_media_state()
        self._schedule_background_precompute()

    def _skip_track(self) -> None:
        if not self.playlist:
            self._set_state("Add music first")
            return
        self.playback_requested = True
        self._play_random_track()

    def _previous_track(self) -> None:
        if not self.playlist:
            self._set_state("Add music first")
            return
        if not self.shuffle_enabled:
            target = 0 if self.current_index <= 0 else self.current_index - 1
            self.playback_requested = True
            self._play_index(target, record_history=False)
            return
        playlist_paths = {entry.path for entry in self.playlist}
        while self.track_history:
            previous_path = self.track_history.pop()
            if previous_path in playlist_paths:
                index = next(
                    index for index, entry in enumerate(self.playlist) if entry.path == previous_path
                )
                self.playback_requested = True
                self._play_index(index, record_history=False)
                return
        if self.current_index >= 0:
            self.playback_requested = True
            self._play_index(self.current_index, record_history=False)
            return
        self.playback_requested = True
        self._play_random_track()

    def _clear_playlist(self) -> None:
        self.player.stop()
        self.playlist = []
        self.current_index = -1
        self.current_path = ""
        self.shuffle_bag = []
        self.track_history = []
        self.playback_requested = False
        self.audio_actually_playing = False
        self._save_state()
        self._update_all()
        self._set_state("No songs")
        self.visualizer_view.clear()
        self.visualizer_status_label.set_text(self._visualizer_default_text())
        self._notify_media_state()
        self._schedule_background_precompute()

    def _shuffle_playlist(self) -> None:
        if len(self.playlist) < 2:
            self._set_state("Add at least two files first")
            return
        random.shuffle(self.playlist)
        if self.current_path:
            self.current_index = next(
                (index for index, entry in enumerate(self.playlist) if entry.path == self.current_path),
                -1,
            )
        self._save_state()
        self._update_playlist_lists()
        self._set_state("Playlist shuffled")

    def _choose_files(self) -> None:
        dialog = Gtk.FileChooserDialog(
            title="Add audio files",
            transient_for=self,
            action=Gtk.FileChooserAction.OPEN,
        )
        dialog.add_buttons(Gtk.STOCK_CANCEL, Gtk.ResponseType.CANCEL, "Add", Gtk.ResponseType.OK)
        dialog.set_select_multiple(True)
        file_filter = Gtk.FileFilter()
        file_filter.set_name("Audio files")
        for extension in sorted(AUDIO_EXTENSIONS):
            file_filter.add_pattern(f"*{extension}")
            file_filter.add_pattern(f"*{extension.upper()}")
        dialog.add_filter(file_filter)

        try:
            if dialog.run() == Gtk.ResponseType.OK:
                entries = [PlaylistEntry.for_file(path) for path in dialog.get_filenames() if is_audio_file(path)]
                self._merge_playlist_entries(entries, "Added audio files")
        finally:
            dialog.destroy()

    def _choose_folder(self) -> None:
        dialog = Gtk.FileChooserDialog(
            title="Add audio folder",
            transient_for=self,
            action=Gtk.FileChooserAction.SELECT_FOLDER,
        )
        dialog.add_buttons(Gtk.STOCK_CANCEL, Gtk.ResponseType.CANCEL, "Add", Gtk.ResponseType.OK)
        try:
            if dialog.run() == Gtk.ResponseType.OK:
                folder = dialog.get_filename()
                self._set_state("Scanning folder")
                threading.Thread(
                    target=self._scan_folder_worker,
                    args=(folder,),
                    name="FredPlayerFolderScan",
                    daemon=True,
                ).start()
        finally:
            dialog.destroy()

    def _scan_folder_worker(self, folder: str) -> None:
        entries = collect_audio_files(folder)
        GLib.idle_add(self._on_folder_scan_done, folder, entries)

    def _on_folder_scan_done(self, folder: str, entries: list[PlaylistEntry]) -> bool:
        if not entries:
            self._set_state("No supported audio found")
            return False
        self._merge_playlist_entries(entries, f"Added {len(entries)} files from {Path(folder).name}")
        return False

    def _merge_playlist_entries(self, entries: list[PlaylistEntry], message: str) -> None:
        previous = len(self.playlist)
        self.playlist = merge_entries(self.playlist, entries)
        added = len(self.playlist) - previous
        self._save_state()
        self._update_all()
        self._set_state(message if added else "Those files are already in the list")
        self._schedule_background_precompute()

    def _playlist_entry_from_track(self, track: dict, base_url: str) -> PlaylistEntry:
        path = str(track.get("path", ""))
        url = remote.build_stream_url(base_url, path)
        folder = path.rsplit("/", 1)[0] if "/" in path else ""
        return PlaylistEntry.for_remote(
            url,
            title=str(track.get("title", "")),
            artist=str(track.get("artist", "")),
            album=str(track.get("album", "")),
            source_folder=folder,
        )

    def _open_server_library_dialog(self) -> None:
        dialog = Gtk.Dialog(title="Add from server", transient_for=self, flags=Gtk.DialogFlags.MODAL)
        dialog.add_buttons(Gtk.STOCK_CANCEL, Gtk.ResponseType.CANCEL, "Fetch", Gtk.ResponseType.OK)
        content = dialog.get_content_area()
        content.set_spacing(6)
        content.set_margin_top(12)
        content.set_margin_bottom(12)
        content.set_margin_start(12)
        content.set_margin_end(12)

        url_entry = Gtk.Entry()
        url_entry.set_text(self.server_base_url)
        url_entry.set_placeholder_text("Server URL, e.g. https://host/fredplayer-media")
        url_entry.set_activates_default(True)
        content.add(url_entry)

        token_entry = Gtk.Entry()
        token_entry.set_text(self.server_token)
        token_entry.set_placeholder_text("Access token")
        token_entry.set_visibility(False)
        token_entry.set_activates_default(True)
        content.add(token_entry)

        dialog.set_default_response(Gtk.ResponseType.OK)
        dialog.show_all()
        try:
            if dialog.run() != Gtk.ResponseType.OK:
                return
            url = url_entry.get_text().strip()
            token = token_entry.get_text().strip()
        finally:
            dialog.destroy()

        if not url:
            self._set_state("Enter a server URL")
            return

        self._set_state("Fetching library…")
        threading.Thread(
            target=self._fetch_server_library_worker,
            args=(url, token),
            name="FredPlayerServerFetch",
            daemon=True,
        ).start()

    def _fetch_server_library_worker(self, url: str, token: str) -> None:
        try:
            tracks = remote.fetch_library(url, token)
        except Exception as error:
            GLib.idle_add(self._on_remote_error, str(error))
            return
        GLib.idle_add(self._on_server_library_fetched, url, token, tracks)

    def _on_remote_error(self, message: str) -> bool:
        self._dismiss_liam_wait_dialog()
        self._set_state(f"Could not reach server: {message}")
        return False

    def _on_server_library_fetched(self, url: str, token: str, tracks: list[dict]) -> bool:
        self.server_base_url = url
        self.server_token = token
        self._save_state()
        self.player.set_server_config(url, token)
        if not tracks:
            self._set_state("Server library is empty")
            return False
        self._show_server_folder_picker(tracks, url)
        return False

    def _show_server_folder_picker(self, tracks: list[dict], base_url: str) -> None:
        folders: "OrderedDict[str, list[int]]" = OrderedDict()
        for index, track in enumerate(tracks):
            path = str(track.get("path", ""))
            top = path.split("/", 1)[0] if path else "(other)"
            folders.setdefault(top, []).append(index)

        dialog = Gtk.Dialog(
            title=f"Choose folders ({len(tracks)} songs total)",
            transient_for=self,
            flags=Gtk.DialogFlags.MODAL,
        )
        dialog.add_buttons(
            Gtk.STOCK_CANCEL, Gtk.ResponseType.CANCEL,
            "Add Selected", Gtk.ResponseType.APPLY,
            "Add All", Gtk.ResponseType.OK,
        )
        dialog.set_default_size(420, 480)
        scroller = Gtk.ScrolledWindow()
        scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroller.set_vexpand(True)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        box.set_margin_top(8)
        box.set_margin_bottom(8)
        box.set_margin_start(8)
        box.set_margin_end(8)
        scroller.add(box)
        dialog.get_content_area().pack_start(scroller, True, True, 0)

        checks: dict[str, Gtk.CheckButton] = {}
        for name in sorted(folders, key=str.lower):
            check = Gtk.CheckButton(label=f"{name} ({len(folders[name])})")
            box.pack_start(check, False, False, 0)
            checks[name] = check

        dialog.show_all()
        try:
            response = dialog.run()
            if response == Gtk.ResponseType.OK:
                selected_indices = list(range(len(tracks)))
            elif response == Gtk.ResponseType.APPLY:
                selected_indices = [
                    index
                    for name, indices in folders.items()
                    if checks[name].get_active()
                    for index in indices
                ]
            else:
                return
        finally:
            dialog.destroy()

        if not selected_indices:
            self._set_state("No songs selected")
            return

        entries = [self._playlist_entry_from_track(tracks[index], base_url) for index in selected_indices]
        self._merge_playlist_entries(entries, f"Added {len(entries)} songs from server")

    def _open_shared_playlists(self) -> None:
        if not self.server_base_url:
            self._set_state('Set up a server URL first via "Add from server"')
            return
        self._set_state("Fetching shared playlists…")
        threading.Thread(
            target=self._fetch_shared_playlists_worker,
            name="FredPlayerSharedPlaylists",
            daemon=True,
        ).start()

    def _fetch_shared_playlists_worker(self) -> None:
        try:
            summaries = remote.fetch_playlists(self.server_base_url, self.server_token)
            library = remote.fetch_library(self.server_base_url, self.server_token)
        except Exception as error:
            GLib.idle_add(self._on_remote_error, str(error))
            return
        GLib.idle_add(self._show_shared_playlists, summaries, library)

    def _show_shared_playlists(self, summaries: list[dict], library: list[dict]) -> bool:
        dialog = Gtk.Dialog(
            title="Shared playlists",
            transient_for=self,
            flags=Gtk.DialogFlags.MODAL,
        )
        dialog.add_buttons(
            Gtk.STOCK_CANCEL, Gtk.ResponseType.CANCEL,
            "Share current", Gtk.ResponseType.APPLY,
            "Download", Gtk.ResponseType.OK,
        )
        content = dialog.get_content_area()
        content.set_spacing(8)
        content.set_margin_top(12)
        content.set_margin_bottom(12)
        content.set_margin_start(12)
        content.set_margin_end(12)

        if summaries:
            content.add(Gtk.Label(
                label="Choose a server playlist to download as a local copy.",
                xalign=0.0,
            ))
            selector = Gtk.ComboBoxText()
            for summary in summaries:
                name = str(summary.get("name", ""))
                count = int(summary.get("count", 0))
                selector.append(name, f"{name}  •  {count} {'song' if count == 1 else 'songs'}")
            selector.set_active(0)
            content.add(selector)
        else:
            selector = None
            content.add(Gtk.Label(
                label="No playlists have been shared yet. Share the current playlist to publish a server copy.",
                xalign=0.0,
                wrap=True,
            ))
            dialog.set_response_sensitive(Gtk.ResponseType.OK, False)

        note = Gtk.Label(
            label="Deleting a downloaded playlist only removes the device copy; the shared server copy remains.",
            xalign=0.0,
            wrap=True,
        )
        note.get_style_context().add_class("muted")
        content.add(note)
        dialog.show_all()
        try:
            response = dialog.run()
            selected_name = selector.get_active_id() if selector is not None else None
        finally:
            dialog.destroy()

        if response == Gtk.ResponseType.APPLY:
            self._confirm_share_current_playlist(summaries)
        elif response == Gtk.ResponseType.OK and selected_name:
            self._download_shared_playlist(selected_name, library)
        return False

    def _confirm_share_current_playlist(self, summaries: list[dict]) -> None:
        if not self.playlist:
            self._set_state("Add songs before sharing this playlist")
            return
        paths = [remote.server_path(self.server_base_url, entry.path) for entry in self.playlist]
        if any(path is None for path in paths):
            self._set_state(
                "Every song must come from this Fred Server; local files and songs from another server cannot be shared"
            )
            return

        replaces_existing = any(
            str(summary.get("name", "")).casefold() == self.active_playlist_name.casefold()
            for summary in summaries
        )
        if replaces_existing:
            dialog = Gtk.MessageDialog(
                transient_for=self,
                flags=Gtk.DialogFlags.MODAL,
                message_type=Gtk.MessageType.QUESTION,
                buttons=Gtk.ButtonsType.CANCEL,
                text=f'Update shared playlist "{self.active_playlist_name}"?',
            )
            dialog.format_secondary_text("This replaces the existing server copy with the current playlist.")
            dialog.add_button("Update shared copy", Gtk.ResponseType.OK)
            try:
                if dialog.run() != Gtk.ResponseType.OK:
                    return
            finally:
                dialog.destroy()
        self._share_current_playlist([path for path in paths if path is not None])

    def _share_current_playlist(self, paths: list[str]) -> None:
        name = self.active_playlist_name
        self._set_state(f'Sharing playlist "{name}"…')
        threading.Thread(
            target=self._share_playlist_worker,
            args=(name, paths),
            name="FredPlayerSharePlaylist",
            daemon=True,
        ).start()

    def _share_playlist_worker(self, name: str, paths: list[str]) -> None:
        try:
            remote.share_playlist(self.server_base_url, self.server_token, name, paths)
        except Exception as error:
            GLib.idle_add(self._on_remote_error, str(error))
            return
        GLib.idle_add(
            self._set_state,
            f'Shared "{name}". Other devices can download it; deleting this copy will not remove it from the server.',
        )

    def _download_shared_playlist(self, name: str, library: list[dict]) -> None:
        self._set_state(f'Downloading shared playlist "{name}"…')
        threading.Thread(
            target=self._download_shared_playlist_worker,
            args=(name, library),
            name="FredPlayerDownloadPlaylist",
            daemon=True,
        ).start()

    def _download_shared_playlist_worker(self, name: str, library: list[dict]) -> None:
        try:
            paths = remote.fetch_playlist_tracks(self.server_base_url, self.server_token, name)
            library_by_path = {str(track.get("path", "")): track for track in library}
            tracks = [library_by_path[path] for path in paths if path in library_by_path]
            if not tracks or len(tracks) != len(paths):
                raise IOError("the shared copy contains songs that are no longer in the server library")
        except Exception as error:
            GLib.idle_add(self._on_remote_error, str(error))
            return
        GLib.idle_add(self._install_shared_playlist, name, tracks)

    def _install_shared_playlist(self, name: str, tracks: list[dict]) -> bool:
        local_name = self._unique_playlist_name(name)
        entries = [self._playlist_entry_from_track(track, self.server_base_url) for track in tracks]
        self._sync_active_playlist()
        self.named_playlists[local_name] = entries
        self._switch_playlist(
            local_name,
            f'Saved "{local_name}" locally. Changes and deletion will not affect the shared server copy.',
        )
        return False

    def _open_ask_liam_dialog(self) -> None:
        if not self.server_base_url:
            self._set_state('Set up a server URL first via "Add from server"')
            return

        dialog = Gtk.Dialog(title="Ask Liam", transient_for=self, flags=Gtk.DialogFlags.MODAL)
        dialog.add_buttons(Gtk.STOCK_CANCEL, Gtk.ResponseType.CANCEL, "Ask", Gtk.ResponseType.OK)
        content = dialog.get_content_area()
        content.set_spacing(6)
        content.set_margin_top(12)
        content.set_margin_bottom(12)
        content.set_margin_start(12)
        content.set_margin_end(12)
        content.add(Gtk.Label(label="e.g. Make me a playlist of upbeat piano music", xalign=0.0))

        text_view = Gtk.TextView()
        text_view.set_wrap_mode(Gtk.WrapMode.WORD)

        def _on_message_key_press(_widget: Gtk.TextView, event: Gdk.EventKey) -> bool:
            is_enter = event.keyval in (Gdk.KEY_Return, Gdk.KEY_KP_Enter)
            if is_enter and not (event.state & Gdk.ModifierType.SHIFT_MASK):
                dialog.response(Gtk.ResponseType.OK)
                return True  # Swallow the keypress so it doesn't also insert a newline.
            return False

        text_view.connect("key-press-event", _on_message_key_press)

        scroller = Gtk.ScrolledWindow()
        scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroller.set_size_request(-1, 90)
        scroller.add(text_view)
        content.pack_start(scroller, True, True, 0)

        dialog.set_default_size(420, 220)
        dialog.show_all()
        try:
            if dialog.run() != Gtk.ResponseType.OK:
                return
            buffer = text_view.get_buffer()
            message = buffer.get_text(buffer.get_start_iter(), buffer.get_end_iter(), False).strip()
        finally:
            dialog.destroy()

        if not message:
            self._set_state("Type a question first")
            return

        self._set_state("Asking Liam…")
        self._show_liam_wait_dialog()
        threading.Thread(
            target=self._ask_liam_worker,
            args=(message,),
            name="FredPlayerAskLiam",
            daemon=True,
        ).start()

    def _show_liam_wait_dialog(self) -> None:
        self._dismiss_liam_wait_dialog()
        dialog = Gtk.Dialog(title="Ask Liam", transient_for=self, flags=Gtk.DialogFlags.MODAL)
        dialog.add_buttons("Hide", Gtk.ResponseType.CLOSE)
        content = dialog.get_content_area()
        content.set_margin_top(16)
        content.set_margin_bottom(16)
        content.set_margin_start(16)
        content.set_margin_end(16)
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        spinner = Gtk.Spinner()
        spinner.start()
        row.pack_start(spinner, False, False, 0)
        row.pack_start(Gtk.Label(label="Asking Liam… this can take a while."), False, False, 0)
        content.add(row)
        # Not run() — that would block this thread's main loop, and nothing
        # would ever dismiss it once the response arrives. "Hide" only closes
        # this indicator; it does not cancel the in-flight request.
        dialog.connect("response", lambda d, _response: d.destroy())
        dialog.show_all()
        self._liam_wait_dialog = dialog

    def _dismiss_liam_wait_dialog(self) -> None:
        dialog = getattr(self, "_liam_wait_dialog", None)
        if dialog is not None:
            dialog.destroy()
        self._liam_wait_dialog = None

    def _ask_liam_worker(self, message: str) -> None:
        try:
            response = remote.ask_liam(self.server_base_url, self.server_token, device_id(), message)
        except Exception as error:
            GLib.idle_add(self._on_remote_error, str(error))
            return
        library_by_path: dict = {}
        if response.get("playlist"):
            try:
                tracks = remote.fetch_library(self.server_base_url, self.server_token)
                library_by_path = {
                    track.get("path"): track for track in tracks if isinstance(track, dict)
                }
            except Exception:
                pass  # Best-effort — entries just fall back to filename-derived titles.
        GLib.idle_add(self._on_liam_response, response, library_by_path)

    def _on_liam_response(self, response: dict, library_by_path: dict) -> bool:
        self._dismiss_liam_wait_dialog()
        reply = str(response.get("reply", "") or "")
        playlist = response.get("playlist")
        if not playlist:
            dialog = Gtk.MessageDialog(
                transient_for=self,
                flags=Gtk.DialogFlags.MODAL,
                message_type=Gtk.MessageType.INFO,
                buttons=Gtk.ButtonsType.OK,
                text="Liam",
            )
            dialog.format_secondary_text(reply or "Liam didn't reply.")
            try:
                dialog.run()
            finally:
                dialog.destroy()
            return False

        name = str(playlist.get("name") or "New Playlist").strip() or "New Playlist"
        track_paths = playlist.get("tracks") or []
        entries = [
            self._playlist_entry_from_track(
                library_by_path.get(path, {"path": path}), self.server_base_url
            )
            for path in track_paths
            if isinstance(path, str) and path
        ]
        if not entries:
            self._set_state("Liam didn't include any tracks")
            return False

        local_name = self._unique_playlist_name(name)
        self._sync_active_playlist()
        self.named_playlists[local_name] = entries
        self._switch_playlist(
            local_name, f'Created "{local_name}" ({len(entries)} songs) — just on this device'
        )
        return False

    def _unique_playlist_name(self, base: str) -> str:
        if base not in self.named_playlists:
            return base
        for suffix in range(2, 1000):
            candidate = f"{base} ({suffix})"
            if candidate not in self.named_playlists:
                return candidate
        return f"{base} ({int(time.time())})"

    def _remove_folder(self, folder: str) -> None:
        before = len(self.playlist)
        self.playlist = [entry for entry in self.playlist if entry.source_folder != folder]
        self._after_playlist_removed(before - len(self.playlist), "Removed folder")

    def _remove_file(self, path: str) -> None:
        before = len(self.playlist)
        self.playlist = [entry for entry in self.playlist if entry.path != path]
        self._after_playlist_removed(before - len(self.playlist), "Removed file")

    def _after_playlist_removed(self, removed: int, message: str) -> None:
        if removed <= 0:
            return
        still_present = self.current_path and any(entry.path == self.current_path for entry in self.playlist)
        starts_next = False
        if not still_present:
            self.player.stop()
            self.current_index = -1
            self.current_path = ""
            if self.playback_requested and self.playlist:
                starts_next = True
                self._play_random_track()
            elif not self.playlist:
                self.playback_requested = False
                self.audio_actually_playing = False
        else:
            self.current_index = next(
                (index for index, entry in enumerate(self.playlist) if entry.path == self.current_path),
                -1,
            )
        playlist_paths = {entry.path for entry in self.playlist}
        self.track_history = [path for path in self.track_history if path in playlist_paths]
        self.shuffle_bag = [index for index in self.shuffle_bag if index < len(self.playlist)]
        self._save_state()
        self._update_all()
        self._schedule_background_precompute()
        if not starts_next:
            self._set_state(message)
            self._notify_media_state()

    def _play_random_track(self) -> None:
        if not self.playlist:
            self.playback_requested = False
            self.audio_actually_playing = False
            self._set_state("No songs")
            self._update_transport()
            self._notify_media_state()
            return
        next_index = self._choose_next_index()
        self._play_index(next_index, record_history=True)

    def _play_index(self, next_index: int, record_history: bool) -> None:
        if next_index < 0 or next_index >= len(self.playlist):
            return
        next_path = self.playlist[next_index].path
        if record_history and self.current_path and self.current_path != next_path:
            self.track_history.append(self.current_path)
            self.track_history = self.track_history[-50:]
        self.current_index = next_index
        self.current_path = next_path
        self.audio_actually_playing = False
        self._update_now_playing()
        self._set_state("Leveling")
        self._update_transport()
        self._schedule_background_precompute()
        self.player.play(self.current_path)
        self._notify_media_state()

    def _choose_next_index(self) -> int:
        if len(self.playlist) == 1:
            return 0
        if not self.shuffle_enabled:
            return 0 if self.current_index < 0 else (self.current_index + 1) % len(self.playlist)
        if not self.shuffle_bag:
            self._refill_shuffle_bag()
        while self.shuffle_bag:
            next_index = self.shuffle_bag.pop(0)
            if next_index != self.current_index or len(self.playlist) == 1:
                return next_index
        self._refill_shuffle_bag()
        if len(self.shuffle_bag) > 1 and self.shuffle_bag[0] == self.current_index:
            self.shuffle_bag[0], self.shuffle_bag[1] = self.shuffle_bag[1], self.shuffle_bag[0]
        return self.shuffle_bag.pop(0) if self.shuffle_bag else random.randrange(len(self.playlist))

    def _refill_shuffle_bag(self) -> None:
        self.shuffle_bag = list(range(len(self.playlist)))
        random.shuffle(self.shuffle_bag)
        if len(self.shuffle_bag) > 1 and self.shuffle_bag[0] == self.current_index:
            self.shuffle_bag[0], self.shuffle_bag[1] = self.shuffle_bag[1], self.shuffle_bag[0]

    def _on_track_started(self, path: str) -> bool:
        if path != self.current_path:
            return False
        paused = self.player.is_paused()
        self.audio_actually_playing = not paused
        self._set_state("Paused" if paused else "Playing")
        self._update_transport()
        self._notify_media_state()
        return False

    def _on_track_finished(self, path: str) -> bool:
        if path != self.current_path:
            return False
        self.audio_actually_playing = False
        if self.playback_requested and self.playlist:
            self._play_random_track()
        else:
            self._set_state("Paused")
            self._update_transport()
            self._notify_media_state()
        return False

    def _on_track_error(self, path: str, error: str) -> bool:
        if path != self.current_path:
            return False  # Stale callback for a track we've already moved on from.
        self.audio_actually_playing = False
        self._set_state(error)
        self._update_transport()
        if self.playback_requested and len(self.playlist) > 1:
            self._play_random_track()
        else:
            self.playback_requested = False
            self._update_transport()
        self._notify_media_state()
        return False

    def _on_track_status(self, path: str, status: str) -> bool:
        if path == self.current_path:
            self._set_state(status)
        return False

    def _on_precompute_status(self, path: str, status: str) -> bool:
        if self.playback_requested:
            return False
        entry = next((e for e in self.playlist if e.path == path), None)
        if entry is None:
            return False
        info = track_info_for_entry(entry)
        self._set_state(f"{status}: {info.display_title}")
        if status.endswith("100%"):
            self._update_cache_status()
        return False

    def _queue_visualization_frame(self, frame: VisualizationFrame) -> None:
        with self._visualization_lock:
            self._pending_visualization_frame = frame
            if self._visualization_idle_scheduled:
                return
            self._visualization_idle_scheduled = True
        GLib.idle_add(self._drain_visualization_frame)

    def _drain_visualization_frame(self) -> bool:
        with self._visualization_lock:
            frame = self._pending_visualization_frame
            self._pending_visualization_frame = None
            self._visualization_idle_scheduled = False
        if frame is not None:
            self._on_visualization_frame(frame)
        return False

    def _on_visualization_frame(self, frame: VisualizationFrame) -> bool:
        self._latest_visualization_frame = frame
        if self.player.cached_visualization_active():
            return False
        self._render_visualization_frame(frame)
        return False

    def _on_visual_clock_tick(self) -> bool:
        if self._destroyed:
            return False
        if not self.player.cached_visualization_active():
            return True
        now = time.monotonic()
        interval = 1.0 / max(1.0, self.visualization_settings.update_fps)
        if now - self._last_visual_clock_render_at < interval:
            return True
        frame = self.player.current_cached_visualization_frame(self._latest_visualization_frame)
        if frame is None:
            return True
        self._last_visual_clock_render_at = now
        self._render_visualization_frame(frame)
        return True

    def _render_visualization_frame(self, frame: VisualizationFrame) -> None:
        self.visualizer_view.set_frame(frame)
        self.visualizer_status_label.set_text(
            f"Peak {frame.peak * 100:.0f}%  RMS {frame.rms * 100:.0f}%  "
            f"{self.visualization_settings.update_fps:.0f} FPS  "
            f"{self.visualization_settings.fft_columns} bars  "
            f"{self._fft_resolution_text()}  "
            f"{self.visualization_settings.fft_smoothing:.0f}% smooth  "
            f"{self.visualization_settings.fft_scale} FFT"
        )

    def _on_output_level_changed(self, value: float) -> None:
        self.output_level = value / 100
        self.player.set_output_level(self.output_level)
        self._save_state_debounced()
        self._notify_media_state()

    def _on_leveling_strength_changed(self, value: float) -> None:
        self.leveling_strength = value / 100
        self.player.set_leveling_strength(self.leveling_strength)
        self._save_state_debounced()

    def _on_shuffle_toggled(self, button: Gtk.CheckButton) -> None:
        self.shuffle_enabled = button.get_active()
        self.shuffle_bag = []
        self._save_state()
        self._notify_media_state()

    def media_set_shuffle(self, enabled: bool) -> bool:
        self.shuffle_toggle.set_active(enabled)
        return False

    def _update_leveling_settings(self, **updates: float) -> None:
        data = self.leveling_settings.to_dict()
        data.update(updates)
        self.leveling_settings = LevelingSettings.from_dict(data)
        self.player.set_leveling_settings(self.leveling_settings)
        self._save_state_debounced(also_precompute=True)

    def _on_visual_update_rate_changed(self, value: float) -> None:
        self._update_visualization_settings(update_fps=value)

    def _on_visual_waveform_window_changed(self, value: float) -> None:
        self._update_visualization_settings(waveform_window_ms=value)

    def _on_visual_fft_columns_changed(self, value: float) -> None:
        self._update_visualization_settings(fft_columns=round(value))

    def _on_visual_fft_smoothing_changed(self, value: float) -> None:
        self._update_visualization_settings(fft_smoothing=value)

    def _on_fft_resolution_changed(self, combo: Gtk.ComboBoxText) -> None:
        active_id = combo.get_active_id() or str(self.visualization_settings.fft_size)
        self._update_visualization_settings(fft_size=int(active_id))

    def _on_fft_scale_changed(self, combo: Gtk.ComboBoxText) -> None:
        active_id = combo.get_active_id() or "log"
        self._update_visualization_settings(fft_scale=active_id)

    def _update_visualization_settings(self, **updates: float | int | str) -> None:
        data = self.visualization_settings.to_dict()
        data.update(updates)
        self.visualization_settings = VisualizationSettings.from_dict(data)
        self.player.set_visualization_settings(self.visualization_settings)
        self.visualizer_view.set_visualization_settings(self.visualization_settings)
        self.visualizer_view.clear()
        self._latest_visualization_frame = RealtimeAnalyzer(
            VISUAL_SAMPLE_RATE,
            settings=self.visualization_settings,
        ).silence()
        self._last_visual_clock_render_at = 0.0
        self.visualizer_status_label.set_text(self._visualizer_default_text())
        self._save_state_debounced(also_precompute=True)

    def _save_state_debounced(self, also_precompute: bool = False) -> None:
        # Several sliders (output level, leveling strength, leveling/visualization
        # advanced settings) fire this on every intermediate "value-changed" tick
        # while being dragged, not just on release. Saving state is a full
        # synchronous JSON write of every playlist (thousands of tracks for a
        # big one) — doing that dozens of times per second during a drag is
        # what was actually causing "FredPlayer is not responding": debounce it
        # so only the settled value after dragging stops gets persisted.
        if self._save_state_debounce_id is not None:
            GLib.source_remove(self._save_state_debounce_id)

        def flush() -> bool:
            self._save_state_debounce_id = None
            self._save_state()
            if also_precompute:
                self._schedule_background_precompute()
            return False

        self._save_state_debounce_id = GLib.timeout_add(300, flush)

    def _save_state(self) -> None:
        self._remember_window_state(write=False)
        self._sync_active_playlist()
        self.store.save(
            StoredState(
                playlist=self.playlist,
                named_playlists=self.named_playlists,
                active_playlist=self.active_playlist_name,
                output_level=self.output_level,
                leveling_strength=self.leveling_strength,
                leveling_settings=self.leveling_settings,
                visualization_settings=self.visualization_settings,
                server_base_url=self.server_base_url,
                server_token=self.server_token,
                shuffle_enabled=self.shuffle_enabled,
                speaker_latencies=self.speaker_latencies,
                selected_microphone=self.selected_microphone,
                window_state=self.window_state,
            )
        )

    def _update_all(self) -> None:
        self._update_transport()
        self._update_now_playing()
        self._update_playlist_lists()

    # Tracks beyond the current one to keep precomputed ahead of playback.
    PRECOMPUTE_LOOKAHEAD = 2

    def _lookahead_paths(self, count: int) -> list[str]:
        """Paths for the current track plus up to `count` upcoming ones —
        not the whole playlist. Precomputing an entire multi-thousand-track
        playlist the moment it's loaded is what caused real hangs (heavy
        background decode/analysis for tracks that may never even get
        played this session); only what's actually about to play benefits
        from being ready ahead of time — a known loudness profile avoids an
        audible ramp-up glitch at track start, and a precomputed waveform
        allows instant seek. Peeks at shuffle_bag without consuming it —
        actual advancement still happens through _choose_next_index()."""
        if not self.playlist:
            return []
        paths = []
        if 0 <= self.current_index < len(self.playlist):
            paths.append(self.playlist[self.current_index].path)
        if not self.shuffle_enabled:
            start = self.current_index + 1 if self.current_index >= 0 else 0
            for step in range(count):
                index = (start + step) % len(self.playlist)
                paths.append(self.playlist[index].path)
        else:
            for index in self.shuffle_bag[:count]:
                if 0 <= index < len(self.playlist):
                    paths.append(self.playlist[index].path)
        seen = set()
        unique_paths = []
        for path in paths:
            if path not in seen:
                seen.add(path)
                unique_paths.append(path)
        return unique_paths

    def _schedule_background_precompute(self) -> None:
        self.precomputer.update_playlist(
            self._lookahead_paths(self.PRECOMPUTE_LOOKAHEAD),
            self.leveling_settings,
            self.visualization_settings,
            self.server_base_url,
            self.server_token,
        )

    def _update_transport(self) -> None:
        if self.playback_requested:
            set_button_icon(self.play_button, "media-playback-pause-symbolic", "Pause")
        else:
            set_button_icon(self.play_button, "media-playback-start-symbolic", "Play")
        count = len(self.playlist)
        self.playlist_count_label.set_text(
            f"{self.active_playlist_name} · {count} {'song' if count == 1 else 'songs'}"
        )
        self._notify_media_state()

    def _update_now_playing(self) -> None:
        if self.current_path:
            entry = next((e for e in self.playlist if e.path == self.current_path), None)
            info = track_info_for_entry(entry) if entry is not None else track_info(self.current_path)
            fallback_meta = "" if (entry is not None and entry.remote) else friendly_path(Path(self.current_path).parent)
            self.now_meta_label.set_text(info.subtitle or fallback_meta)
            self.now_title_label.set_text(info.display_title)
        else:
            self.now_title_label.set_text("No song selected")
            self.now_meta_label.set_text("Add files or folders to start a shuffled sleep playlist")
        self._notify_media_state()

    def _update_playlist_lists(self) -> None:
        self._clear_list_box(self.folder_list)
        self._clear_list_box(self.file_list)
        self._track_list_generation += 1

        if not self.playlist:
            self._add_empty_row(self.folder_list, "No folders in playlist")
            self._add_empty_row(self.file_list, "No files in playlist")
            self.folder_list.show_all()
            self.file_list.show_all()
            return

        folders: OrderedDict[str, int] = OrderedDict()
        for entry in self.playlist:
            folders[entry.source_folder] = folders.get(entry.source_folder, 0) + 1
        for folder, count in folders.items():
            self._add_action_row(
                self.folder_list,
                f"{friendly_path(folder)} ({count})",
                "Remove",
                lambda _button, folder=folder: self._remove_folder(folder),
            )
        self.folder_list.show_all()

        # Building one real GTK widget subtree per track is expensive for
        # playlists in the thousands (a plain Gtk.ListBox realizes every row
        # up front, it doesn't recycle/virtualize like a TreeView). Adding
        # rows in small batches via GLib.idle_add keeps the main loop free
        # to paint/respond between batches instead of freezing the whole
        # window for one big synchronous rebuild. The generation check lets
        # a newer playlist switch cancel an in-flight batch cleanly.
        self._populate_track_rows_incrementally(list(self.playlist), self._track_list_generation)

    def _populate_track_rows_incrementally(self, remaining: list[PlaylistEntry], generation: int) -> None:
        chunk_size = 150

        def add_next_chunk() -> bool:
            if generation != self._track_list_generation:
                return False
            for entry in remaining[:chunk_size]:
                self._add_track_row(entry)
            del remaining[:chunk_size]
            return bool(remaining)

        GLib.idle_add(add_next_chunk)

    def _add_track_row(self, entry: PlaylistEntry) -> None:
        info = track_info_for_entry(entry)
        row = Gtk.ListBoxRow()
        box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        box.set_margin_top(7)
        box.set_margin_bottom(7)
        box.set_margin_start(8)
        box.set_margin_end(8)
        row.add(box)

        text_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        text_box.set_hexpand(True)
        box.pack_start(text_box, True, True, 0)

        title = Gtk.Label(label=info.display_title)
        title.get_style_context().add_class("track-title")
        title.set_halign(Gtk.Align.START)
        title.set_xalign(0.0)
        title.set_ellipsize(Pango.EllipsizeMode.END)
        title.set_tooltip_text(entry.path)
        text_box.pack_start(title, False, False, 0)

        subtitle_text = info.subtitle or friendly_path(Path(entry.path).parent)
        subtitle = Gtk.Label(label=subtitle_text)
        subtitle.get_style_context().add_class("track-subtitle")
        subtitle.set_halign(Gtk.Align.START)
        subtitle.set_xalign(0.0)
        subtitle.set_ellipsize(Pango.EllipsizeMode.END)
        subtitle.set_tooltip_text(subtitle_text)
        text_box.pack_start(subtitle, False, False, 0)

        button = make_text_button("Remove")
        button.connect("clicked", lambda _button, path=entry.path: self._remove_file(path))
        box.pack_start(button, False, False, 0)
        self.file_list.add(row)
        row.show_all()

    def _add_action_row(
        self,
        list_box: Gtk.ListBox,
        label_text: str,
        button_text: str,
        callback: Callable[[Gtk.Button], None],
    ) -> None:
        row = Gtk.ListBoxRow()
        box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        box.set_margin_top(4)
        box.set_margin_bottom(4)
        box.set_margin_start(6)
        box.set_margin_end(6)
        row.add(box)

        label = Gtk.Label(label=label_text)
        label.set_halign(Gtk.Align.START)
        label.set_hexpand(True)
        label.set_ellipsize(Pango.EllipsizeMode.MIDDLE)
        box.pack_start(label, True, True, 0)

        button = make_text_button(button_text)
        button.connect("clicked", callback)
        box.pack_start(button, False, False, 0)
        list_box.add(row)

    def _add_empty_row(self, list_box: Gtk.ListBox, text: str) -> None:
        row = Gtk.ListBoxRow()
        label = Gtk.Label(label=text)
        label.get_style_context().add_class("muted")
        label.set_margin_top(8)
        label.set_margin_bottom(8)
        label.set_margin_start(6)
        label.set_margin_end(6)
        label.set_halign(Gtk.Align.START)
        row.add(label)
        list_box.add(row)

    def _clear_list_box(self, list_box: Gtk.ListBox) -> None:
        for child in list(list_box.get_children()):
            list_box.remove(child)

    def _set_state(self, message: str) -> None:
        self.state_label.set_text(message)
        self.state_label.set_tooltip_text(message)

    def _on_destroy(self, _widget: Gtk.Widget) -> None:
        self._destroyed = True
        if self._save_state_debounce_id is not None:
            GLib.source_remove(self._save_state_debounce_id)
            self._save_state_debounce_id = None
        self._save_state()
        self.mpris.close()
        self.precomputer.close()
        self.player.release()

    def _notify_media_state(self) -> None:
        if hasattr(self, "mpris"):
            self.mpris.update()

    def media_raise(self) -> bool:
        self.present()
        return False

    def media_quit(self) -> bool:
        self.close()
        return False

    def media_play(self) -> bool:
        if not self.playlist:
            self._set_state("Add music first")
            return False
        self._start_or_resume()
        return False

    def media_pause(self) -> bool:
        if self.playback_requested:
            self._pause_playback()
        return False

    def media_play_pause(self) -> bool:
        if not self.playlist:
            self._set_state("Add music first")
            return False
        self._toggle_playback()
        return False

    def media_stop(self) -> bool:
        self.player.stop()
        self.playback_requested = False
        self.audio_actually_playing = False
        self.current_index = -1
        self.current_path = ""
        self._set_state("Stopped")
        self._update_now_playing()
        self._update_transport()
        self.visualizer_view.clear()
        self.visualizer_status_label.set_text(self._visualizer_default_text())
        self._on_progress_tick()
        self._notify_media_state()
        return False

    def media_progress(self) -> tuple[int, int]:
        snapshot = self.player.playback_snapshot()
        return snapshot.position_ms, snapshot.duration_ms

    def media_seek(self, position_ms: int) -> bool:
        if not self.current_path:
            return False
        if not self.player.seek(position_ms):
            self._set_state("Seeking is unavailable for this track")
            return False
        actual_position_ms, _duration_ms = self.media_progress()
        self.audio_actually_playing = self.playback_requested
        self._set_state("Seeking")
        self._on_progress_tick()
        if hasattr(self, "mpris"):
            self.mpris.seeked(actual_position_ms)
        self._notify_media_state()
        return False

    def media_seek_relative(self, offset_ms: int) -> bool:
        position_ms, duration_ms = self.media_progress()
        if duration_ms <= 0:
            return False
        return self.media_seek(max(0, min(duration_ms, position_ms + int(offset_ms))))

    def _on_cache_status_tick(self) -> bool:
        if self._destroyed:
            return False
        self._update_cache_status()
        return True

    def _update_cache_status(self) -> None:
        if not hasattr(self, "cache_status_label"):
            return
        text = self._cache_status_text()
        self.cache_status_label.set_text(text)
        self.cache_status_label.set_tooltip_text(text)

    def _cache_status_text(self) -> str:
        loudness = ProfileCache().stats()
        spectrum = SpectrumCache().stats()
        waveform = WaveformCache().stats()
        total_bytes = loudness.bytes_used + spectrum.bytes_used + waveform.bytes_used
        return (
            "Cache: "
            f"loudness {self._cache_fragment(loudness)}, "
            f"FFT {self._cache_fragment(spectrum)}, "
            f"waveform {self._cache_fragment(waveform)}; "
            f"{format_bytes(total_bytes)} on disk"
        )

    def _cache_fragment(self, stats) -> str:
        return f"{stats.count}/{stats.prune_after} -> {stats.keep}"

    def _visualizer_default_text(self) -> str:
        return (
            f"{self.visualization_settings.update_fps:.0f} FPS  "
            f"{self.visualization_settings.waveform_window_ms:.0f} ms waveform  "
            f"{self.visualization_settings.fft_columns} bars  "
            f"{self._fft_resolution_text()}  "
            f"{self.visualization_settings.fft_smoothing:.0f}% smooth  "
            f"{self.visualization_settings.fft_scale} FFT"
        )

    def _fft_resolution_text(self) -> str:
        bin_hz = VISUAL_SAMPLE_RATE / max(1, self.visualization_settings.fft_size)
        window_ms = self.visualization_settings.fft_size * 1000.0 / VISUAL_SAMPLE_RATE
        return f"{self.visualization_settings.fft_size} FFT ({bin_hz:.1f} Hz/bin, {window_ms:.0f} ms)"

    def media_next(self) -> bool:
        self._skip_track()
        return False

    def media_previous(self) -> bool:
        self._previous_track()
        return False

    def media_set_volume(self, value: float) -> bool:
        percent = max(10, min(100, round(value * 100)))
        self.output_scale.set_value(percent)
        return False


def install_css() -> None:
    global _CSS_PROVIDER, _GNOME_INTERFACE_SETTINGS
    if _CSS_PROVIDER is None:
        _CSS_PROVIDER = Gtk.CssProvider()
        screen = Gdk.Screen.get_default()
        if screen is not None:
            Gtk.StyleContext.add_provider_for_screen(
                screen,
                _CSS_PROVIDER,
                Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
            )

    settings = Gtk.Settings.get_default()
    if settings is not None and not _THEME_SIGNAL_IDS:
        _THEME_SIGNAL_IDS.append(settings.connect("notify::gtk-theme-name", lambda *_args: refresh_theme_css()))
        _THEME_SIGNAL_IDS.append(
            settings.connect("notify::gtk-application-prefer-dark-theme", lambda *_args: refresh_theme_css())
        )

    if _GNOME_INTERFACE_SETTINGS is None:
        try:
            _GNOME_INTERFACE_SETTINGS = Gio.Settings.new("org.gnome.desktop.interface")
            _GNOME_INTERFACE_SETTINGS.connect("changed::color-scheme", lambda *_args: refresh_theme_css())
            _GNOME_INTERFACE_SETTINGS.connect("changed::gtk-theme", lambda *_args: refresh_theme_css())
        except Exception:
            _GNOME_INTERFACE_SETTINGS = None

    refresh_theme_css()


def is_dark_theme() -> bool:
    try:
        gnome_settings = _GNOME_INTERFACE_SETTINGS or Gio.Settings.new("org.gnome.desktop.interface")
        color_scheme = gnome_settings.get_string("color-scheme")
        if color_scheme == "prefer-dark":
            return True
        if color_scheme == "prefer-light":
            return False
    except Exception:
        pass

    settings = Gtk.Settings.get_default()
    if settings is None:
        return True
    try:
        if bool(settings.get_property("gtk-application-prefer-dark-theme")):
            return True
    except Exception:
        pass
    try:
        theme_name = str(settings.get_property("gtk-theme-name") or "").lower()
        return "dark" in theme_name
    except Exception:
        return True


def refresh_theme_css() -> None:
    if _CSS_PROVIDER is None:
        return
    palette = theme_palette(is_dark_theme())
    css = Template("""
    window {
        background: $bg;
        color: $text;
    }
    .app-root {
        background: $bg;
    }
    .top-bar {
        background: $panel;
        border: 1px solid $border;
        border-radius: 8px;
        padding: 14px 16px;
    }
    .visualizer-panel {
        background: $panel;
        border: 1px solid $border;
        border-radius: 8px;
        padding: 12px 14px;
    }
    label {
        color: $text;
    }
    .app-title {
        color: $muted;
        font-size: 13px;
        font-weight: 700;
    }
    .now-title {
        font-size: 22px;
        font-weight: 700;
    }
    .now-meta {
        color: $soft;
        font-size: 14px;
    }
    .section-title {
        font-size: 17px;
        font-weight: 700;
    }
    .subtle-heading {
        color: $soft;
        font-weight: 700;
    }
    .muted {
        color: $muted;
    }
    button {
        min-height: 32px;
        padding: 6px 12px;
    }
    .icon-button {
        min-width: 40px;
        min-height: 40px;
        padding: 6px;
    }
    .primary-button {
        background: $primary;
        color: #ffffff;
        border-color: $primary_border;
    }
    list {
        background: $panel;
        border: 1px solid $border;
    }
    row {
        border-bottom: 1px solid $border;
    }
    row:hover {
        background: $hover;
    }
    .track-title {
        font-size: 14px;
        font-weight: 700;
    }
    .track-subtitle {
        color: $muted;
        font-size: 12px;
    }
    scale trough {
        min-height: 6px;
    }
    notebook {
        background: $bg;
    }
    .visualizer {
        background: $panel;
    }
    """).substitute(palette)
    _CSS_PROVIDER.load_from_data(css.encode("utf-8"))


def theme_palette(dark: bool) -> dict[str, str]:
    if dark:
        return {
            "bg": "#101214",
            "panel": "#15181b",
            "hover": "#1e2428",
            "border": "#2f363d",
            "text": "#f5f3ed",
            "soft": "#c9c7bd",
            "muted": "#a9aca6",
            "primary": "#2f7c74",
            "primary_border": "#39948b",
        }
    return {
        "bg": "#f5f7f8",
        "panel": "#ffffff",
        "hover": "#edf2f4",
        "border": "#d4d9dd",
        "text": "#202427",
        "soft": "#4f5d63",
        "muted": "#67747a",
        "primary": "#0f766e",
        "primary_border": "#0d9488",
    }


def main() -> int:
    app = FredPlayerApp()
    return app.run(sys.argv)
