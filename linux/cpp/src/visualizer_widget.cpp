#include "fredplayer/visualizer_widget.hpp"

#include <gdk/gdkx.h>

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace fredplayer {
namespace {
const char* vertexShader = R"GLSL(
#version 150
in vec2 position;
in vec4 color;
out vec4 vertexColor;
void main() { gl_Position = vec4(position, 0.0, 1.0); vertexColor = color; }
)GLSL";

const char* fragmentShader = R"GLSL(
#version 150
in vec4 vertexColor;
out vec4 outputColor;
void main() { outputColor = vertexColor; }
)GLSL";

unsigned compile(unsigned type, const char* source) {
  const auto shader = glCreateShader(type);
  glShaderSource(shader, 1, &source, nullptr); glCompileShader(shader);
  int okay = 0; glGetShaderiv(shader, GL_COMPILE_STATUS, &okay);
  if (!okay) {
    int length = 0; glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
    std::string message(std::max(1, length), '\0');
    glGetShaderInfoLog(shader, length, nullptr, message.data());
    glDeleteShader(shader);
    throw std::runtime_error("OpenGL shader compilation failed: " + message);
  }
  return shader;
}

unsigned linkProgram(const char* vertexSource) {
  const auto vertex = compile(GL_VERTEX_SHADER, vertexSource);
  const auto fragment = compile(GL_FRAGMENT_SHADER, fragmentShader);
  const auto program = glCreateProgram();
  glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program);
  glDeleteShader(vertex); glDeleteShader(fragment);
  int okay = 0; glGetProgramiv(program, GL_LINK_STATUS, &okay);
  if (!okay) {
    int length = 0; glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
    std::string message(std::max(1, length), '\0');
    glGetProgramInfoLog(program, length, nullptr, message.data());
    glDeleteProgram(program);
    throw std::runtime_error("OpenGL shader link failed: " + message);
  }
  return program;
}
}  // namespace

VisualizerWidget::VisualizerWidget() {
  set_has_window(true);
  set_app_paintable(true);
  set_double_buffered(false);
  set_hexpand(true); set_vexpand(true);
  set_size_request(-1, 220);
  settings_.normalize(); clear();
}

VisualizerWidget::~VisualizerWidget() = default;

void VisualizerWidget::setFrame(std::shared_ptr<const VisualizationFrame> frame) {
  if (!frame) return;
  const auto receivedAt = std::chrono::steady_clock::now();
  if (sourceFpsWindowStart_ == std::chrono::steady_clock::time_point{})
    sourceFpsWindowStart_ = receivedAt;
  if (!hasSourcePts_ || frame->ptsNs != lastSourcePtsNs_) {
    ++sourceFpsWindowFrames_;
    lastSourcePtsNs_ = frame->ptsNs;
    hasSourcePts_ = true;
  }
  const auto sourceWindow = std::chrono::duration<double>(
      receivedAt - sourceFpsWindowStart_).count();
  if (sourceWindow >= 1.0) {
    measuredSourceFps_ = sourceFpsWindowFrames_ / sourceWindow;
    sourceFpsWindowFrames_ = 0;
    sourceFpsWindowStart_ = receivedAt;
  }
  frame_ = std::move(frame);
  if (smoothed_.size() != frame_->spectrum.size()) smoothed_.assign(frame_->spectrum.size(), 0);
  const auto smoothing = static_cast<float>(settings_.fftSmoothing / 100.0);
  const auto rise = 1.0F - smoothing * .55F;
  const auto decay = 1.0F - smoothing * .90F;
  for (std::size_t i = 0; i < smoothed_.size(); ++i) {
    const auto target = frame_->spectrum[i];
    smoothed_[i] += (target - smoothed_[i]) * (target > smoothed_[i] ? rise : decay);
  }
  renderFrame();
}

void VisualizerWidget::setSettings(const VisualizationSettings& settings) {
  settings_ = settings; settings_.normalize(); smoothed_.assign(settings_.fftColumns, 0); clear();
}

void VisualizerWidget::clear() {
  auto frame = std::make_shared<VisualizationFrame>();
  frame->waveform.assign(512, 0); frame->spectrum.assign(settings_.fftColumns, 0);
  frame_ = std::move(frame); smoothed_.assign(settings_.fftColumns, 0);
  fpsWindowStart_ = {}; fpsWindowFrames_ = 0; measuredFps_ = 0;
  sourceFpsWindowStart_ = {}; sourceFpsWindowFrames_ = 0;
  hasSourcePts_ = false; measuredSourceFps_ = 0;
  renderFrame();
}

void VisualizerWidget::on_realize() {
  Gtk::DrawingArea::on_realize();
  const auto gdkWindow = get_window();
  if (!gdkWindow || !GDK_IS_X11_WINDOW(gdkWindow->gobj())) {
    glxError_ = "Direct visualization requires an X11 display";
    queue_draw();
    return;
  }
  auto* gdkDisplay = gdk_window_get_display(gdkWindow->gobj());
  xDisplay_ = gdk_x11_display_get_xdisplay(gdkDisplay);
  const auto parentWindow = gdk_x11_window_get_xid(gdkWindow->gobj());
  const auto screen = DefaultScreen(xDisplay_);
  int attributes[] = {
      GLX_RGBA, GLX_DOUBLEBUFFER,
      GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8,
      GLX_DEPTH_SIZE, 0, None};
  XVisualInfo* visual = glXChooseVisual(xDisplay_, screen, attributes);
  if (!visual) {
    glxError_ = "No compatible double-buffered GLX visual";
    queue_draw();
    return;
  }
  gdk_x11_display_error_trap_push(gdkDisplay);
  xColormap_ = XCreateColormap(xDisplay_, RootWindow(xDisplay_, screen),
                               visual->visual, AllocNone);
  XSetWindowAttributes windowAttributes{};
  windowAttributes.colormap = xColormap_;
  windowAttributes.border_pixel = 0;
  windowAttributes.background_pixel = BlackPixel(xDisplay_, screen);
  windowAttributes.event_mask = ExposureMask | StructureNotifyMask;
  const auto allocation = get_allocation();
  const auto scale = std::max(1, get_scale_factor());
  surfaceWidth_ = std::max(1, allocation.get_width() * scale);
  surfaceHeight_ = std::max(1, allocation.get_height() * scale);
  xWindow_ = XCreateWindow(xDisplay_, parentWindow, 0, 0,
      static_cast<unsigned>(surfaceWidth_), static_cast<unsigned>(surfaceHeight_),
      0, visual->depth, InputOutput, visual->visual,
      CWBorderPixel | CWBackPixel | CWColormap | CWEventMask, &windowAttributes);
  glxContext_ = glXCreateContext(xDisplay_, visual, nullptr, True);
  XFree(visual);
  const auto madeCurrent = xWindow_ && glxContext_ &&
      glXMakeCurrent(xDisplay_, xWindow_, glxContext_);
  if (madeCurrent) XMapWindow(xDisplay_, xWindow_);
  const auto xError = gdk_x11_display_error_trap_pop(gdkDisplay);
  if (xError || !madeCurrent) {
    glxError_ = "Could not create the direct GLX visualization surface";
    destroyGlx();
    queue_draw();
    return;
  }
  try {
    buildProgram();
    glGenVertexArrays(1, &vertexArray_);
    glBindVertexArray(vertexArray_);
    glGenBuffers(1, &vertexBuffer_);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    if (epoxy_has_glx_extension(xDisplay_, screen, "GLX_EXT_swap_control"))
      glXSwapIntervalEXT(xDisplay_, xWindow_, 0);
    glxError_.clear();
    renderFrame();
  }
  catch (const std::exception& error) {
    glxError_ = error.what();
    g_warning("%s", error.what());
    destroyGlx();
    queue_draw();
  }
}

void VisualizerWidget::on_unrealize() {
  destroyGlx();
  Gtk::DrawingArea::on_unrealize();
}

void VisualizerWidget::on_map() {
  Gtk::DrawingArea::on_map();
  if (xDisplay_ && xWindow_) XMapWindow(xDisplay_, xWindow_);
  renderFrame();
}

void VisualizerWidget::destroyGlx() {
  auto* gdkDisplay = xDisplay_ ? gdk_x11_lookup_xdisplay(xDisplay_) : nullptr;
  if (gdkDisplay) gdk_x11_display_error_trap_push(gdkDisplay);
  if (xDisplay_ && glxContext_)
    glXMakeCurrent(xDisplay_, xWindow_, glxContext_);
  if (vertexBuffer_) glDeleteBuffers(1, &vertexBuffer_);
  if (vertexArray_) glDeleteVertexArrays(1, &vertexArray_);
  if (program_) glDeleteProgram(program_);
  vertexBuffer_ = vertexArray_ = program_ = 0;
  if (xDisplay_ && glxContext_) {
    glXMakeCurrent(xDisplay_, None, nullptr);
    glXDestroyContext(xDisplay_, glxContext_);
  }
  glxContext_ = nullptr;
  if (xDisplay_ && xWindow_) XDestroyWindow(xDisplay_, xWindow_);
  xWindow_ = 0;
  if (xDisplay_ && xColormap_) XFreeColormap(xDisplay_, xColormap_);
  xColormap_ = 0;
  if (gdkDisplay) {
    const auto ignoredXError = gdk_x11_display_error_trap_pop(gdkDisplay);
    (void)ignoredXError;
  }
  xDisplay_ = nullptr;
}

void VisualizerWidget::on_size_allocate(Gtk::Allocation& allocation) {
  Gtk::DrawingArea::on_size_allocate(allocation);
  const auto scale = std::max(1, get_scale_factor());
  surfaceWidth_ = std::max(1, allocation.get_width() * scale);
  surfaceHeight_ = std::max(1, allocation.get_height() * scale);
  if (xDisplay_ && xWindow_) {
    XMoveResizeWindow(xDisplay_, xWindow_, 0, 0,
        static_cast<unsigned>(surfaceWidth_),
        static_cast<unsigned>(surfaceHeight_));
    renderFrame();
  }
}

bool VisualizerWidget::on_draw(const Cairo::RefPtr<Cairo::Context>& context) {
  context->set_source_rgb(.04, .05, .06);
  context->paint();
  if (!glxError_.empty()) {
    context->set_source_rgb(.8, .82, .84);
    context->select_font_face("Sans", Cairo::FONT_SLANT_NORMAL,
                              Cairo::FONT_WEIGHT_NORMAL);
    context->set_font_size(15);
    context->move_to(20, 32);
    context->show_text(glxError_);
  }
  return true;
}

void VisualizerWidget::buildProgram() {
  program_ = linkProgram(vertexShader);
  positionLocation_ = glGetAttribLocation(program_, "position");
  colorLocation_ = glGetAttribLocation(program_, "color");
}

void VisualizerWidget::drawFrame(const std::vector<Vertex>& rectangles,
                                 const std::vector<Vertex>& waveformFill,
                                 const std::vector<Vertex>& lines) {
  auto& vertices = uploadVertices_;
  vertices.clear();
  vertices.reserve(rectangles.size() + waveformFill.size() + lines.size());
  vertices.insert(vertices.end(), rectangles.begin(), rectangles.end());
  vertices.insert(vertices.end(), waveformFill.begin(), waveformFill.end());
  vertices.insert(vertices.end(), lines.begin(), lines.end());
  if (vertices.empty()) return;
  glUseProgram(program_);
  glBindVertexArray(vertexArray_);
  glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer_);
  glBufferData(GL_ARRAY_BUFFER, vertices.size() * sizeof(Vertex), vertices.data(), GL_STREAM_DRAW);
  glEnableVertexAttribArray(positionLocation_); glEnableVertexAttribArray(colorLocation_);
  glVertexAttribPointer(positionLocation_, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), nullptr);
  glVertexAttribPointer(colorLocation_, 4, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(2 * sizeof(float)));
  auto offset = 0;
  glDrawArrays(GL_TRIANGLES, offset, static_cast<GLsizei>(rectangles.size()));
  offset += static_cast<GLint>(rectangles.size());
  glDrawArrays(GL_TRIANGLE_STRIP, offset, static_cast<GLsizei>(waveformFill.size()));
  offset += static_cast<GLint>(waveformFill.size());
  glDrawArrays(GL_LINES, offset, static_cast<GLsizei>(lines.size()));
}

void VisualizerWidget::renderFrame() {
  if (!program_ || !frame_ || !xDisplay_ || !xWindow_ || !glxContext_ ||
      !get_mapped()) return;
  if (glXGetCurrentContext() != glxContext_ &&
      !glXMakeCurrent(xDisplay_, xWindow_, glxContext_)) return;
  const auto renderedAt = std::chrono::steady_clock::now();
  if (fpsWindowStart_ == std::chrono::steady_clock::time_point{})
    fpsWindowStart_ = renderedAt;
  ++fpsWindowFrames_;
  const auto fpsWindow = std::chrono::duration<double>(renderedAt - fpsWindowStart_).count();
  if (fpsWindow >= 1.0) {
    measuredFps_ = fpsWindowFrames_ / fpsWindow;
    fpsWindowFrames_ = 0;
    fpsWindowStart_ = renderedAt;
  }
  const auto scale = std::max(1, get_scale_factor());
  const auto width = static_cast<float>(surfaceWidth_);
  const auto height = static_cast<float>(surfaceHeight_);
  glViewport(0, 0, static_cast<int>(width), static_cast<int>(height));
  auto context = get_style_context();
  const auto dark = context->get_color(Gtk::STATE_FLAG_NORMAL).get_red() > .5;
  struct Color { float r, g, b, a; };
  const Color background = dark ? Color{.07F,.08F,.09F,1} : Color{.98F,.99F,1,1};
  const Color panel = dark ? Color{.10F,.12F,.13F,1} : Color{.94F,.96F,.98F,1};
  const Color grid = dark ? Color{.25F,.29F,.32F,.58F} : Color{.72F,.76F,.80F,.62F};
  const Color wave = dark ? Color{.16F,.79F,.72F,1} : Color{0,.49F,.45F,1};
  glClearColor(background.r, background.g, background.b, background.a);
  glClear(GL_COLOR_BUFFER_BIT);
  glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

  const auto appendVertex = [width,height](std::vector<Vertex>& vertices, float x, float y, Color color) {
    vertices.push_back({x / width * 2.0F - 1.0F, 1.0F - y / height * 2.0F,
                        color.r, color.g, color.b, color.a});
  };
  const auto appendRect = [&appendVertex](std::vector<Vertex>& vertices, float x, float y,
                                          float w, float h, Color color) {
    const auto x2=x+w,y2=y+h;
    appendVertex(vertices,x,y,color); appendVertex(vertices,x2,y,color); appendVertex(vertices,x2,y2,color);
    appendVertex(vertices,x,y,color); appendVertex(vertices,x2,y2,color); appendVertex(vertices,x,y2,color);
  };
  const auto appendLine = [&appendVertex](std::vector<Vertex>& vertices, float x1, float y1,
                                          float x2, float y2, Color color) {
    appendVertex(vertices,x1,y1,color); appendVertex(vertices,x2,y2,color);
  };
  const auto appendPanelLines = [&appendLine](std::vector<Vertex>& vertices, float x, float y,
                                              float w, float h, Color color) {
    appendLine(vertices,x,y,x+w,y,color); appendLine(vertices,x+w,y,x+w,y+h,color);
    appendLine(vertices,x+w,y+h,x,y+h,color); appendLine(vertices,x,y+h,x,y,color);
    for (float fraction : {.25F,.5F,.75F}) appendLine(vertices,x,y+h*fraction,x+w,y+h*fraction,color);
  };

  const auto margin = 14.0F * scale;
  const auto innerX = margin;
  const auto innerWidth = std::max(1.0F, width - margin * 2.0F);
  const auto waveY = margin;
  const auto waveHeight = std::max(46.0F, height * .36F);
  const auto fftY = waveY + waveHeight + 18.0F * scale;
  const auto fftHeight = std::max(48.0F, height - fftY - margin);

  auto& rectangles=rectangleVertices_;rectangles.clear();
  rectangles.reserve(12 + smoothed_.size() * 6);
  appendRect(rectangles,innerX,waveY,innerWidth,waveHeight,panel);
  appendRect(rectangles,innerX,fftY,innerWidth,fftHeight,panel);
  const auto gap = clamp(innerWidth / std::max(1.0F, static_cast<float>(smoothed_.size()) * 40.0F), 1.0F, 3.0F);
  const auto barGaps = smoothed_.empty() ? 0 : smoothed_.size()-1;
  const auto barWidth = std::max(1.0F, (innerWidth-gap*barGaps) /
      std::max<std::size_t>(1,smoothed_.size()));
  for (std::size_t i = 0; i < smoothed_.size(); ++i) {
    const auto amount=clamp(smoothed_[i],0.0F,1.0F);
    const auto barHeight=std::max(1.0F,amount*fftHeight);
    const auto hue=.62F-.62F*static_cast<float>(i)/std::max<std::size_t>(1,smoothed_.size()-1);
    float r,g,b; hsv(hue,dark?.88F:.78F,dark?.95F:.78F,r,g,b);
    appendRect(rectangles,innerX+i*(barWidth+gap),fftY+fftHeight-barHeight,
               barWidth,barHeight,{r,g,b,1});
  }
  auto& waveformFill=waveformFillVertices_;waveformFill.clear();
  waveformFill.reserve(frame_->waveform.size()*2);
  const auto waveCenter=waveY+waveHeight*.5F;
  const auto waveScale=waveHeight*.46F;
  const Color fill{wave.r,wave.g,wave.b,.18F};
  for(std::size_t i=0;i<frame_->waveform.size();++i){
    const auto x=innerX+innerWidth*static_cast<float>(i)/std::max<std::size_t>(1,frame_->waveform.size()-1);
    const auto y=waveCenter-clamp(frame_->waveform[i],-1.0F,1.0F)*waveScale;
    appendVertex(waveformFill,x,waveCenter,fill);appendVertex(waveformFill,x,y,fill);
  }
  auto& lines=lineVertices_;lines.clear();
  lines.reserve(28+frame_->waveform.size()*2);
  appendPanelLines(lines,innerX,waveY,innerWidth,waveHeight,grid);
  appendPanelLines(lines,innerX,fftY,innerWidth,fftHeight,grid);
  for(std::size_t i=1;i<frame_->waveform.size();++i){
    const auto previousX=innerX+innerWidth*static_cast<float>(i-1)/std::max<std::size_t>(1,frame_->waveform.size()-1);
    const auto x=innerX+innerWidth*static_cast<float>(i)/std::max<std::size_t>(1,frame_->waveform.size()-1);
    const auto previousY=waveCenter-clamp(frame_->waveform[i-1],-1.0F,1.0F)*waveScale;
    const auto y=waveCenter-clamp(frame_->waveform[i],-1.0F,1.0F)*waveScale;
    appendLine(lines,previousX,previousY,x,y,wave);
  }
  glLineWidth(1.0F*scale);drawFrame(rectangles,waveformFill,lines);
  glXSwapBuffers(xDisplay_, xWindow_);
}

void VisualizerWidget::hsv(float h, float s, float v, float& r, float& g, float& b) {
  h = h - std::floor(h); const auto i = static_cast<int>(h * 6); const auto f = h * 6 - i;
  const auto p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
  switch (i % 6) { case 0:r=v;g=t;b=p;break; case 1:r=q;g=v;b=p;break; case 2:r=p;g=v;b=t;break;
    case 3:r=p;g=q;b=v;break; case 4:r=t;g=p;b=v;break; default:r=v;g=p;b=q; }
}

}  // namespace fredplayer
