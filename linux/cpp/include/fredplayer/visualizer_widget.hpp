#pragma once

#include "fredplayer/types.hpp"

#include <gtkmm/drawingarea.h>
#include <epoxy/glx.h>
#include <chrono>
#include <memory>
#include <vector>

namespace fredplayer {

class VisualizerWidget : public Gtk::DrawingArea {
 public:
  VisualizerWidget();
  ~VisualizerWidget() override;
  void setFrame(std::shared_ptr<const VisualizationFrame> frame);
  void setSettings(const VisualizationSettings& settings);
  void clear();
  [[nodiscard]] double measuredFps() const { return measuredFps_; }
  [[nodiscard]] double measuredSourceFps() const { return measuredSourceFps_; }

 protected:
  void on_realize() override;
  void on_unrealize() override;
  void on_map() override;
  void on_size_allocate(Gtk::Allocation& allocation) override;
  bool on_draw(const Cairo::RefPtr<Cairo::Context>& context) override;

 private:
  struct Vertex { float x, y, r, g, b, a; };
  void buildProgram();
  void drawFrame(const std::vector<Vertex>& rectangles,
                 const std::vector<Vertex>& waveformFill,
                 const std::vector<Vertex>& lines);
  void renderFrame();
  void destroyGlx();
  static void hsv(float hue, float saturation, float value,
                  float& red, float& green, float& blue);

  VisualizationSettings settings_;
  std::shared_ptr<const VisualizationFrame> frame_;
  std::vector<float> smoothed_;
  std::vector<Vertex> rectangleVertices_;
  std::vector<Vertex> waveformFillVertices_;
  std::vector<Vertex> lineVertices_;
  std::vector<Vertex> uploadVertices_;
  unsigned program_{0};
  unsigned vertexArray_{0};
  unsigned vertexBuffer_{0};
  Display* xDisplay_{nullptr};
  Window xWindow_{0};
  Colormap xColormap_{0};
  GLXContext glxContext_{nullptr};
  int surfaceWidth_{1};
  int surfaceHeight_{1};
  std::string glxError_;
  int positionLocation_{-1};
  int colorLocation_{-1};
  std::chrono::steady_clock::time_point fpsWindowStart_{};
  unsigned fpsWindowFrames_{0};
  double measuredFps_{0};
  std::chrono::steady_clock::time_point sourceFpsWindowStart_{};
  unsigned sourceFpsWindowFrames_{0};
  std::int64_t lastSourcePtsNs_{0};
  bool hasSourcePts_{false};
  double measuredSourceFps_{0};
};

}  // namespace fredplayer
