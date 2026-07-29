#pragma once

#include "fredplayer/types.hpp"

#include <gio/gio.h>

#include <functional>
#include <string>

namespace fredplayer {

struct MprisCallbacks {
  std::function<void()> playPause;
  std::function<void()> play;
  std::function<void()> pause;
  std::function<void()> stop;
  std::function<void()> next;
  std::function<void()> previous;
  std::function<void(std::int64_t)> seekRelativeUs;
  std::function<void(std::int64_t)> seekAbsoluteUs;
};

class MprisServer {
 public:
  explicit MprisServer(MprisCallbacks callbacks = {});
  ~MprisServer();
  void update(const TrackEntry* track, bool playing, bool paused,
              std::int64_t positionMs, std::int64_t durationMs);

 private:
  static void onBusAcquired(GDBusConnection* connection, const gchar* name,
                            gpointer data);
  static void onMethodCall(GDBusConnection* connection, const gchar* sender,
                           const gchar* objectPath, const gchar* interfaceName,
                           const gchar* methodName, GVariant* parameters,
                           GDBusMethodInvocation* invocation, gpointer data);
  static GVariant* onGetProperty(GDBusConnection* connection, const gchar* sender,
                                 const gchar* objectPath, const gchar* interfaceName,
                                 const gchar* propertyName, GError** error,
                                 gpointer data);
  void emitChanged();

  MprisCallbacks callbacks_;
  guint ownerId_{0};
  guint rootRegistration_{0};
  guint playerRegistration_{0};
  GDBusConnection* connection_{nullptr};
  TrackEntry track_;
  bool hasTrack_{false};
  bool playing_{false};
  bool paused_{false};
  std::int64_t positionMs_{0};
  std::int64_t durationMs_{0};
};

}  // namespace fredplayer
