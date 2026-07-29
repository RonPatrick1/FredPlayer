#include "fredplayer/mpris.hpp"

#include <algorithm>
#include <cstring>

namespace fredplayer {
namespace {
constexpr const char* kObject = "/org/mpris/MediaPlayer2";
constexpr const char* kXml = R"XML(
<node>
 <interface name="org.mpris.MediaPlayer2">
  <method name="Raise"/><method name="Quit"/>
  <property name="CanQuit" type="b" access="read"/>
  <property name="CanRaise" type="b" access="read"/>
  <property name="HasTrackList" type="b" access="read"/>
  <property name="Identity" type="s" access="read"/>
  <property name="DesktopEntry" type="s" access="read"/>
  <property name="SupportedUriSchemes" type="as" access="read"/>
  <property name="SupportedMimeTypes" type="as" access="read"/>
 </interface>
 <interface name="org.mpris.MediaPlayer2.Player">
  <method name="Next"/><method name="Previous"/><method name="Pause"/>
  <method name="PlayPause"/><method name="Stop"/><method name="Play"/>
  <method name="Seek"><arg direction="in" type="x" name="Offset"/></method>
  <method name="SetPosition"><arg direction="in" type="o" name="TrackId"/><arg direction="in" type="x" name="Position"/></method>
  <signal name="Seeked"><arg type="x" name="Position"/></signal>
  <property name="PlaybackStatus" type="s" access="read"/>
  <property name="LoopStatus" type="s" access="readwrite"/>
  <property name="Rate" type="d" access="readwrite"/>
  <property name="Shuffle" type="b" access="readwrite"/>
  <property name="Metadata" type="a{sv}" access="read"/>
  <property name="Volume" type="d" access="readwrite"/>
  <property name="Position" type="x" access="read"/>
  <property name="MinimumRate" type="d" access="read"/>
  <property name="MaximumRate" type="d" access="read"/>
  <property name="CanGoNext" type="b" access="read"/>
  <property name="CanGoPrevious" type="b" access="read"/>
  <property name="CanPlay" type="b" access="read"/>
  <property name="CanPause" type="b" access="read"/>
  <property name="CanSeek" type="b" access="read"/>
  <property name="CanControl" type="b" access="read"/>
 </interface>
</node>)XML";

GDBusNodeInfo* nodeInfo() {
  static GDBusNodeInfo* value = [] {
    GError* error = nullptr; auto* info = g_dbus_node_info_new_for_xml(kXml, &error);
    if (error) { g_warning("MPRIS introspection failed: %s", error->message); g_clear_error(&error); }
    return info;
  }();
  return value;
}
}  // namespace

MprisServer::MprisServer(MprisCallbacks callbacks) : callbacks_(std::move(callbacks)) {
  ownerId_ = g_bus_own_name(G_BUS_TYPE_SESSION, "org.mpris.MediaPlayer2.fredplayer_native",
      G_BUS_NAME_OWNER_FLAGS_NONE, onBusAcquired, nullptr, nullptr, this, nullptr);
}

MprisServer::~MprisServer() {
  if (connection_) {
    if (rootRegistration_) g_dbus_connection_unregister_object(connection_, rootRegistration_);
    if (playerRegistration_) g_dbus_connection_unregister_object(connection_, playerRegistration_);
    g_object_unref(connection_);
  }
  if (ownerId_) g_bus_unown_name(ownerId_);
}

void MprisServer::onBusAcquired(GDBusConnection* connection, const gchar*, gpointer data) {
  auto* self = static_cast<MprisServer*>(data);
  self->connection_ = G_DBUS_CONNECTION(g_object_ref(connection));
  static const GDBusInterfaceVTable table{onMethodCall, onGetProperty, nullptr, {0}};
  GError* error = nullptr;
  self->rootRegistration_ = g_dbus_connection_register_object(connection, kObject,
      nodeInfo()->interfaces[0], &table, self, nullptr, &error);
  if (!error) self->playerRegistration_ = g_dbus_connection_register_object(connection, kObject,
      nodeInfo()->interfaces[1], &table, self, nullptr, &error);
  if (error) { g_warning("MPRIS registration failed: %s", error->message); g_clear_error(&error); }
}

void MprisServer::onMethodCall(GDBusConnection*, const gchar*, const gchar*,
                               const gchar*, const gchar* method,
                               GVariant* parameters, GDBusMethodInvocation* invocation,
                               gpointer data) {
  auto* self = static_cast<MprisServer*>(data);
  if (!std::strcmp(method, "PlayPause") && self->callbacks_.playPause) self->callbacks_.playPause();
  else if (!std::strcmp(method, "Play") && self->callbacks_.play) self->callbacks_.play();
  else if (!std::strcmp(method, "Pause") && self->callbacks_.pause) self->callbacks_.pause();
  else if (!std::strcmp(method, "Stop") && self->callbacks_.stop) self->callbacks_.stop();
  else if (!std::strcmp(method, "Next") && self->callbacks_.next) self->callbacks_.next();
  else if (!std::strcmp(method, "Previous") && self->callbacks_.previous) self->callbacks_.previous();
  else if (!std::strcmp(method, "Seek")) {
    gint64 offset = 0; g_variant_get(parameters, "(x)", &offset);
    if (self->callbacks_.seekRelativeUs) self->callbacks_.seekRelativeUs(offset);
  } else if (!std::strcmp(method, "SetPosition")) {
    const gchar* id = nullptr; gint64 position = 0; g_variant_get(parameters, "(&ox)", &id, &position);
    if (self->callbacks_.seekAbsoluteUs) self->callbacks_.seekAbsoluteUs(position);
  }
  g_dbus_method_invocation_return_value(invocation, nullptr);
}

GVariant* MprisServer::onGetProperty(GDBusConnection*, const gchar*, const gchar*,
                                     const gchar* interface, const gchar* property,
                                     GError**, gpointer data) {
  auto* self = static_cast<MprisServer*>(data);
  if (!std::strcmp(interface, "org.mpris.MediaPlayer2")) {
    if (!std::strcmp(property, "CanQuit") || !std::strcmp(property, "CanRaise") ||
        !std::strcmp(property, "HasTrackList")) return g_variant_new_boolean(FALSE);
    if (!std::strcmp(property, "Identity")) return g_variant_new_string("FredPlayer Native");
    if (!std::strcmp(property, "DesktopEntry")) return g_variant_new_string("fredplayer-native");
    if (!std::strcmp(property, "SupportedUriSchemes")) return g_variant_new_strv(nullptr, 0);
    if (!std::strcmp(property, "SupportedMimeTypes")) return g_variant_new_strv(nullptr, 0);
  }
  if (!std::strcmp(property, "PlaybackStatus"))
    return g_variant_new_string(!self->playing_ ? "Stopped" : self->paused_ ? "Paused" : "Playing");
  if (!std::strcmp(property, "LoopStatus")) return g_variant_new_string("Playlist");
  if (!std::strcmp(property, "Rate") || !std::strcmp(property, "MinimumRate") ||
      !std::strcmp(property, "MaximumRate") || !std::strcmp(property, "Volume")) return g_variant_new_double(1.0);
  if (!std::strcmp(property, "Shuffle")) return g_variant_new_boolean(TRUE);
  if (!std::strcmp(property, "Position")) return g_variant_new_int64(self->positionMs_ * 1000);
  if (!std::strcmp(property, "CanControl")) return g_variant_new_boolean(TRUE);
  if (!std::strcmp(property, "CanGoNext") || !std::strcmp(property, "CanGoPrevious") ||
      !std::strcmp(property, "CanPlay") || !std::strcmp(property, "CanPause") ||
      !std::strcmp(property, "CanSeek")) return g_variant_new_boolean(self->hasTrack_);
  if (!std::strcmp(property, "Metadata")) {
    GVariantBuilder builder; g_variant_builder_init(&builder, G_VARIANT_TYPE("a{sv}"));
    const auto id = self->hasTrack_ ? "/org/mpris/MediaPlayer2/track/current" : "/org/mpris/MediaPlayer2/TrackList/NoTrack";
    g_variant_builder_add(&builder, "{sv}", "mpris:trackid", g_variant_new_object_path(id));
    if (self->hasTrack_) {
      g_variant_builder_add(&builder, "{sv}", "xesam:title", g_variant_new_string(self->track_.displayTitle().c_str()));
      const gchar* artists[] = {self->track_.artist.c_str(), nullptr};
      g_variant_builder_add(&builder, "{sv}", "xesam:artist", g_variant_new_strv(artists, self->track_.artist.empty() ? 0 : 1));
      g_variant_builder_add(&builder, "{sv}", "xesam:album", g_variant_new_string(self->track_.album.c_str()));
      g_variant_builder_add(&builder, "{sv}", "mpris:length", g_variant_new_int64(self->durationMs_ * 1000));
    }
    return g_variant_builder_end(&builder);
  }
  return nullptr;
}

void MprisServer::update(const TrackEntry* track, bool playing, bool paused,
                         std::int64_t positionMs, std::int64_t durationMs) {
  hasTrack_ = track != nullptr;
  if (track) track_ = *track;
  playing_ = playing; paused_ = paused; positionMs_ = positionMs; durationMs_ = durationMs;
  emitChanged();
}

void MprisServer::emitChanged() {
  if (!connection_) return;
  GVariantBuilder changed; g_variant_builder_init(&changed, G_VARIANT_TYPE("a{sv}"));
  g_variant_builder_add(&changed, "{sv}", "PlaybackStatus",
      g_variant_new_string(!playing_ ? "Stopped" : paused_ ? "Paused" : "Playing"));
  GVariantBuilder invalidated; g_variant_builder_init(&invalidated, G_VARIANT_TYPE("as"));
  g_dbus_connection_emit_signal(connection_, nullptr, kObject, "org.freedesktop.DBus.Properties",
      "PropertiesChanged", g_variant_new("(sa{sv}as)", "org.mpris.MediaPlayer2.Player", &changed, &invalidated), nullptr);
}

}  // namespace fredplayer
