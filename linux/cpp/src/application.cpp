#include "fredplayer/application.hpp"

#include "fredplayer/audio_engine.hpp"
#include "fredplayer/cache.hpp"
#include "fredplayer/latency.hpp"
#include "fredplayer/metadata.hpp"
#include "fredplayer/mpris.hpp"
#include "fredplayer/state.hpp"
#include "fredplayer/visualizer_widget.hpp"

#include <gdkmm/pixbuf.h>
#include <giomm/file.h>
#include <gtkmm.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <filesystem>
#include <functional>
#include <iomanip>
#include <iostream>
#include <map>
#include <random>
#include <set>
#include <sstream>
#include <thread>
#include <unordered_map>

namespace fredplayer {
namespace {

std::atomic<bool> windowAlive{false};

class SettingsScale final : public Gtk::Scale {
 public:
  explicit SettingsScale(std::function<void(GdkEventScroll*)> scrollPage)
      : Gtk::Scale(Gtk::ORIENTATION_HORIZONTAL),
        scrollPage_(std::move(scrollPage)) {}

 protected:
  bool on_scroll_event(GdkEventScroll* event) override {
    if (scrollPage_) scrollPage_(event);
    return true;
  }

 private:
  std::function<void(GdkEventScroll*)> scrollPage_;
};

class SettingsComboBoxText final : public Gtk::ComboBoxText {
 public:
  void setScrollPage(std::function<void(GdkEventScroll*)> scrollPage) {
    scrollPage_ = std::move(scrollPage);
  }

 protected:
  bool on_scroll_event(GdkEventScroll* event) override {
    if (scrollPage_) scrollPage_(event);
    return true;
  }

 private:
  std::function<void(GdkEventScroll*)> scrollPage_;
};

std::string formatTime(std::int64_t milliseconds) {
  const auto total = std::max<std::int64_t>(0, milliseconds) / 1000;
  const auto hours = total / 3600; const auto minutes = total % 3600 / 60; const auto seconds = total % 60;
  std::ostringstream out;
  if (hours) out << hours << ':' << std::setw(2) << std::setfill('0') << minutes;
  else out << minutes;
  out << ':' << std::setw(2) << std::setfill('0') << seconds;
  return out.str();
}

std::string formatBytes(std::uint64_t bytes){static const char*units[]={"B","KB","MB","GB","TB"};double value=bytes;int unit=0;while(value>=1024&&unit<4){value/=1024;++unit;}std::ostringstream out;out<<std::fixed<<std::setprecision(unit?1:0)<<value<<' '<<units[unit];return out.str();}

std::string visualizationSummary(const VisualizationSettings& settings,
                                 const VisualizationFrame* frame = nullptr) {
  std::ostringstream out;
  if (frame) out << "Peak " << std::llround(frame->peak*100) << "%  RMS "
                 << std::llround(frame->rms*100) << "%  ";
  out << std::llround(settings.updateFps) << " FPS  ";
  if (!frame) out << std::llround(settings.waveformWindowMs) << " ms waveform  ";
  out << settings.fftColumns << " bars  " << settings.fftSize << " FFT ("
      << std::fixed << std::setprecision(1) << 48000.0/std::max(1,settings.fftSize)
      << " Hz/bin, " << std::setprecision(0) << settings.fftSize*1000.0/48000.0
      << " ms)  " << settings.fftSmoothing << "% smooth  " << settings.fftScale << " FFT";
  return out.str();
}

std::string visualizationPerformance(double displayedFps, double sourceFps,
                                     double analyzedFps,
                                     const VisualizationEngine& engine) {
  std::ostringstream out;
  bool hasValue = false;
  const auto separator = [&out, &hasValue] {
    if (hasValue) out << "  ·  ";
    hasValue = true;
  };
  if (displayedFps > 0) {
    separator();
    out << std::fixed << std::setprecision(1) << displayedFps
        << " rendered FPS";
  }
  if (displayedFps > 0) {
    separator();
    out << std::fixed << std::setprecision(1) << sourceFps
        << " source FPS";
  }
  if (engine.cachedFramesActive()) {
    separator(); out << "cached; live FFT idle";
  } else if (engine.estimatedAnalysisCapacityFps() > 0) {
    if (analyzedFps >= 0) {
      separator(); out << std::fixed << std::setprecision(1) << analyzedFps
                       << " analyzed FPS";
    }
    separator(); out << '~' << std::fixed << std::setprecision(0)
        << engine.estimatedAnalysisCapacityFps() << " FFT FPS capacity";
  }
  if (engine.droppedBlockCount() > 0) {
    separator(); out << engine.droppedBlockCount() << " audio blocks dropped";
  }
  return out.str();
}

Gtk::Button* iconButton(const char* icon, const char* tooltip) {
  auto* button = Gtk::make_managed<Gtk::Button>();
  button->set_image_from_icon_name(icon, Gtk::ICON_SIZE_LARGE_TOOLBAR);
  button->set_always_show_image(true); button->set_tooltip_text(tooltip);
  button->set_size_request(52, 46); button->set_hexpand(false); button->set_halign(Gtk::ALIGN_CENTER); return button;
}

Gtk::ToggleButton* iconToggleButton(const char* icon, const char* tooltip) {
  auto* button = Gtk::make_managed<Gtk::ToggleButton>();
  button->set_image_from_icon_name(icon, Gtk::ICON_SIZE_LARGE_TOOLBAR);
  button->set_always_show_image(true); button->set_tooltip_text(tooltip);
  button->set_size_request(52, 46); button->set_hexpand(false); button->set_halign(Gtk::ALIGN_CENTER); return button;
}

void keepNaturalWidth(Gtk::Button& button) {
  button.set_hexpand(false);
  button.set_halign(Gtk::ALIGN_START);
}

Gtk::Button* textButton(const char* label) {
  auto* button = Gtk::make_managed<Gtk::Button>(label);
  keepNaturalWidth(*button);
  return button;
}

void showError(Gtk::Window& parent, const std::string& message) {
  Gtk::MessageDialog dialog(parent, message, false, Gtk::MESSAGE_ERROR, Gtk::BUTTONS_OK, true); dialog.run();
}

class TrackColumns : public Gtk::TreeModel::ColumnRecord {
 public:
  TrackColumns() { add(title); add(artist); add(album); add(path); add(index); }
  Gtk::TreeModelColumn<Glib::ustring> title, artist, album, path;
  Gtk::TreeModelColumn<int> index;
};

class FolderColumns : public Gtk::TreeModel::ColumnRecord {
 public:
  FolderColumns() { add(name); add(path); add(trackCount); }
  Gtk::TreeModelColumn<Glib::ustring> name, path;
  Gtk::TreeModelColumn<int> trackCount;
};

class ServerTrackColumns : public Gtk::TreeModel::ColumnRecord {
 public:
  ServerTrackColumns() {
    add(title); add(artist); add(album); add(folder); add(index);
  }
  Gtk::TreeModelColumn<Glib::ustring> title, artist, album, folder;
  Gtk::TreeModelColumn<int> index;
};

std::string toLowerAscii(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(),
      [](const unsigned char character) { return static_cast<char>(std::tolower(character)); });
  return value;
}

class FredPlayerWindow final : public Gtk::ApplicationWindow {
 public:
  FredPlayerWindow()
      : state_(store_.load()), random_(std::random_device{}()) {
    windowAlive.store(true);
    set_title("FredPlayer Native"); set_wmclass("fredplayer-native", "FredPlayerNative");
    set_default_size(state_.window.width, state_.window.height);
    set_size_request(480, 620);
    move(state_.window.x,state_.window.y);if(state_.window.maximized)maximize();
    if (std::filesystem::exists("linux/assets/fredplayer-icon.png")) {
      try { set_icon(Gdk::Pixbuf::create_from_file("linux/assets/fredplayer-icon.png")); } catch (...) {}
    }
    cachedOutput_ = currentOutput();
    cachedMicrophones_ = microphones();
    lastOutputKey_ = cachedOutput_.key;
    audio_ = std::make_unique<AudioEngine>(AudioCallbacks{
      [this] { if (windowAlive.load()) handleTrackFinished(); },
      [this](const std::string& error) { if (windowAlive.load()) status_.set_text(error); },
      [this] { if (windowAlive.load()) updateTransport(); }});
    audio_->configure(state_.outputLevel, state_.levelingStrength, state_.leveling,
                      state_.visualization, currentVisualDelay());
    audio_->configureServer(state_.serverBaseUrl, state_.serverToken);
    buildUi();
    mpris_ = std::make_unique<MprisServer>(MprisCallbacks{
      [this]{ togglePlay(); }, [this]{ play(); }, [this]{ audio_->pause(); }, [this]{ stop(); },
      [this]{ next(); }, [this]{ previous(); },
      [this](std::int64_t delta){ audio_->seek(audio_->positionMs() + delta / 1000); },
      [this](std::int64_t position){ audio_->seek(position / 1000); }});
    refreshPlaylist(); hydrateLocalMetadata(); updateNowPlaying(); updateTransport(); refreshLatencyUi();
    progressConnection_ = Glib::signal_timeout().connect(sigc::mem_fun(*this, &FredPlayerWindow::progressTick), 250);
    // Separate, much faster timer than progressTick's 250ms — that cadence
    // was fine for the seek bar but left the lyrics highlight up to 250ms
    // stale at any given moment, reading as "behind" the vocal even with
    // correct underlying timestamps. Matches Android's dedicated 100ms
    // lyrics tick.
    lyricsTickConnection_ = Glib::signal_timeout().connect(
        sigc::mem_fun(*this, &FredPlayerWindow::lyricsTick), 80);
    routeConnection_ = Glib::signal_timeout().connect(sigc::mem_fun(*this, &FredPlayerWindow::routeTick), 3000);
    cacheConnection_ = Glib::signal_timeout().connect(sigc::mem_fun(*this,&FredPlayerWindow::cacheTick),15000);
    cacheTick();
    visualRequestedFps_ = state_.visualization.updateFps;
    visualTimerConnection_ = Glib::signal_timeout().connect(
        sigc::mem_fun(*this, &FredPlayerWindow::visualTimerTick), 4,
        Glib::PRIORITY_HIGH_IDLE);
    signal_delete_event().connect(sigc::mem_fun(*this, &FredPlayerWindow::onDelete), false);
    signal_configure_event().connect(sigc::mem_fun(*this, &FredPlayerWindow::onConfigure), false);
    signal_window_state_event().connect(sigc::mem_fun(*this,&FredPlayerWindow::onWindowState),false);
    if(state_.lyricsWindowOpen){
      lyricsRestorePending_=true;
      signal_map_event().connect([this](GdkEventAny*){
        if(lyricsRestorePending_&&windowAlive.load()){
          lyricsRestorePending_=false;
          Glib::signal_idle().connect_once([this]{
            if(windowAlive.load())openLyricsWindow();
          });
        }
        return false;
      },false);
    }
    if(state_.queueWindowOpen){
      queueRestorePending_=true;
      signal_map_event().connect([this](GdkEventAny*){
        if(queueRestorePending_&&windowAlive.load()){
          queueRestorePending_=false;
          Glib::signal_idle().connect_once([this]{
            if(windowAlive.load())openQueueWindow();
          });
        }
        return false;
      },false);
    }
  }

  ~FredPlayerWindow() override {
    windowAlive.store(false);
    visualTimerConnection_.disconnect();progressConnection_.disconnect();
    routeConnection_.disconnect();cacheConnection_.disconnect();
    saveState();
  }

  // Entry point for the "remove-current-track" GAction (see runApplication()),
  // invoked over D-Bus so a desktop-wide keyboard shortcut can reach it
  // whether or not this window currently has focus. Skips the on-screen
  // trash button's confirmation dialog on purpose — the whole point of a
  // dedicated hotkey is a single fast press, same as a "skip track" key.
  void removeCurrentTrackFromShortcut(){ removeCurrentTrack(); }

 private:
  void buildUi() {
    auto* root = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_VERTICAL);
    add(*root); stack_.set_transition_type(Gtk::STACK_TRANSITION_TYPE_SLIDE_LEFT_RIGHT);
    stack_.set_homogeneous(false); root->pack_start(stack_, true, true);
    buildPlayerPage(); buildSettingsPage(); stack_.add(playerPage_, "player");
    settingsScroll_.set_policy(Gtk::POLICY_AUTOMATIC, Gtk::POLICY_AUTOMATIC);
    settingsScroll_.set_propagate_natural_width(false); settingsScroll_.add(settingsPage_);
    stack_.add(settingsScroll_, "settings"); stack_.set_visible_child("player");
    show_all_children();
  }

  void buildPlayerPage() {
    playerPage_.set_spacing(10); playerPage_.set_margin_left(12); playerPage_.set_margin_right(12);
    playerPage_.set_margin_top(10); playerPage_.set_margin_bottom(10);
    auto* headerFrame = Gtk::make_managed<Gtk::Frame>();
    headerFrame->get_style_context()->add_class("panel-frame");
    auto* header = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL, 12);
    header->set_margin_left(12); header->set_margin_right(12);
    header->set_margin_top(10); header->set_margin_bottom(10);
    nowArt_.set_size_request(72, 72);
    nowArt_.get_style_context()->add_class("now-art");
    header->pack_start(nowArt_, false, false);
    showPlaceholderArtwork();
    auto* details = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_VERTICAL, 2); details->set_hexpand(true);
    // The art stays square and grows to match however tall this details
    // column ends up (title/subtitle/seek/status stacked) — same border
    // and button positions, the art and the text just split the row's
    // existing height/width differently as a result.
    details->signal_size_allocate().connect([this](Gtk::Allocation& allocation) {
      const int size = allocation.get_height();
      if (size > 0 && size != artSizePx_) {
        artSizePx_ = size;
        nowArt_.set_size_request(artSizePx_, artSizePx_);
        if (!currentArtworkPath_.empty()) showArtworkFile(currentArtworkPath_);
        else showPlaceholderArtwork();
      }
    });
    auto* brand = Gtk::make_managed<Gtk::Label>("FredPlayer"); brand->set_halign(Gtk::ALIGN_START);
    brand->get_style_context()->add_class("app-title");
    nowTitle_.set_halign(Gtk::ALIGN_FILL); nowTitle_.set_xalign(0); nowTitle_.set_ellipsize(Pango::ELLIPSIZE_END);
    nowTitle_.set_max_width_chars(1); nowTitle_.set_hexpand(true);
    nowTitle_.get_style_context()->add_class("now-title");
    nowMeta_.set_halign(Gtk::ALIGN_FILL); nowMeta_.set_xalign(0); nowMeta_.set_ellipsize(Pango::ELLIPSIZE_END);
    nowMeta_.set_max_width_chars(1); nowMeta_.set_hexpand(true);
    nowMeta_.get_style_context()->add_class("now-meta");
    details->pack_start(*brand, false, false); details->pack_start(nowTitle_, false, false);
    details->pack_start(nowMeta_, false, false);
    seek_.set_draw_value(false); seek_.set_range(0, 1); seek_.set_sensitive(false); seek_.set_hexpand(true);
    seek_.signal_button_press_event().connect([this](GdkEventButton*) { seeking_ = true; return false; });
    seek_.signal_button_release_event().connect([this](GdkEventButton*) {
      audio_->seek(static_cast<std::int64_t>(seek_.get_value())); seeking_ = false; return false; });
    details->pack_start(seek_, false, false);
    auto* timeRow = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL);
    elapsed_.set_halign(Gtk::ALIGN_START); duration_.set_halign(Gtk::ALIGN_END); duration_.set_hexpand(true);
    timeRow->pack_start(elapsed_, false, false); timeRow->pack_end(duration_, false, false);
    details->pack_start(*timeRow, false, false);
    elapsed_.get_style_context()->add_class("muted"); duration_.get_style_context()->add_class("muted");
    auto* statusRow = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL,8);
    status_.set_halign(Gtk::ALIGN_FILL); status_.set_xalign(0); status_.set_hexpand(true);
    status_.set_ellipsize(Pango::ELLIPSIZE_END); status_.set_max_width_chars(1);
    status_.get_style_context()->add_class("muted");
    playlistStatus_.set_halign(Gtk::ALIGN_END); playlistStatus_.set_xalign(1);
    playlistStatus_.set_ellipsize(Pango::ELLIPSIZE_END); playlistStatus_.set_max_width_chars(28);
    playlistStatus_.get_style_context()->add_class("muted");
    statusRow->pack_start(status_,true,true);statusRow->pack_end(playlistStatus_,false,false);
    details->pack_start(*statusRow, false, false);
    header->pack_start(*details, true, true);
    auto* controls = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_VERTICAL, 8);
    auto* row = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL, 6);
    shuffleButton_ = iconToggleButton("media-playlist-shuffle-symbolic", "Shuffle");
    previousButton_ = iconButton("media-skip-backward-symbolic", "Previous");
    playButton_ = iconButton("media-playback-start-symbolic", "Play");
    stopButton_ = iconButton("media-playback-stop-symbolic", "Stop");
    nextButton_ = iconButton("media-skip-forward-symbolic", "Next");
    repeatButton_ = iconButton("media-playlist-repeat-symbolic", "Repeat");
    shuffleButton_->set_active(state_.shuffleEnabled);
    shuffleButton_->signal_toggled().connect([this]{
      state_.shuffleEnabled = shuffleButton_->get_active();
      // Re-shuffle immediately on toggle-on rather than leaving the bag
      // empty until the next Next press — otherwise the What's Next
      // window's UP NEXT section would show nothing until then.
      if(state_.shuffleEnabled) refillShuffleBag(); else shuffleBag_.clear();
      refreshQueueWindow(); saveState(); });
    previousButton_->signal_clicked().connect([this]{ previous(); });
    playButton_->signal_clicked().connect([this]{ togglePlay(); });
    stopButton_->signal_clicked().connect([this]{ stop(); });
    nextButton_->signal_clicked().connect([this]{ next(); });
    repeatButton_->signal_clicked().connect([this]{ cycleRepeatMode(); });
    updateRepeatButton();
    removeButton_ = iconButton("edit-delete-symbolic", "Remove from playlist");
    removeButton_->signal_clicked().connect([this]{ confirmRemoveCurrentTrack(); });
    row->pack_start(*shuffleButton_, false, false);
    row->pack_start(*previousButton_, false, false); row->pack_start(*playButton_, false, false);
    row->pack_start(*stopButton_, false, false); row->pack_start(*nextButton_, false, false);
    row->pack_start(*repeatButton_, false, false);
    row->pack_start(*removeButton_, false, false);
    settingsButton_.set_image_from_icon_name("emblem-system-symbolic", Gtk::ICON_SIZE_LARGE_TOOLBAR);
    settingsButton_.set_always_show_image(true);
    settingsButton_.set_tooltip_text("Settings");
    settingsButton_.set_size_request(52, 46);
    settingsButton_.signal_clicked().connect([this]{ stack_.set_visible_child("settings"); });
    lyricsButton_ = iconButton("media-view-subtitles-symbolic", "Lyrics");
    lyricsButton_->signal_clicked().connect([this]{ toggleLyricsWindow(); });
    queueButton_ = iconButton("view-list-symbolic", "What's Next");
    queueButton_->signal_clicked().connect([this]{ toggleQueueWindow(); });
    auto* headerButtonsRow = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL, 6);
    headerButtonsRow->pack_start(*lyricsButton_, false, false);
    headerButtonsRow->pack_start(*queueButton_, false, false);
    headerButtonsRow->pack_start(settingsButton_, false, false);
    controls->pack_start(*row, false, false); controls->pack_start(*headerButtonsRow, false, false);
    header->pack_end(*controls, false, false);
    headerFrame->add(*header); playerPage_.pack_start(*headerFrame, false, false);
    auto* visualFrame = Gtk::make_managed<Gtk::Frame>();
    visualFrame->get_style_context()->add_class("panel-frame"); visualFrame->set_vexpand(true);
    auto* visualBox=Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_VERTICAL,8);
    visualBox->set_margin_left(12);visualBox->set_margin_right(12);visualBox->set_margin_top(10);visualBox->set_margin_bottom(10);
    auto* visualHeader=Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL,10);
    auto* visualTitle=Gtk::make_managed<Gtk::Label>("Real-time analysis");visualTitle->set_halign(Gtk::ALIGN_START);
    visualTitle->get_style_context()->add_class("section-title");visualHeader->pack_start(*visualTitle,false,false);
    visualStatus_.set_halign(Gtk::ALIGN_FILL);visualStatus_.set_xalign(1);visualStatus_.set_hexpand(true);
    visualStatus_.set_ellipsize(Pango::ELLIPSIZE_START);visualStatus_.set_max_width_chars(1);
    visualStatus_.get_style_context()->add_class("muted");visualHeader->pack_start(visualStatus_,true,true);
    visualBox->pack_start(*visualHeader,false,false);
    visualPerformanceStatus_.set_halign(Gtk::ALIGN_FILL);
    visualPerformanceStatus_.set_xalign(1);visualPerformanceStatus_.set_hexpand(true);
    visualPerformanceStatus_.set_ellipsize(Pango::ELLIPSIZE_END);
    visualPerformanceStatus_.set_max_width_chars(1);
    visualPerformanceStatus_.get_style_context()->add_class("muted");
    visualBox->pack_start(visualPerformanceStatus_,false,false);
    visualStatus_.set_text(visualizationSummary(state_.visualization));
    visualizer_.setSettings(state_.visualization); visualBox->pack_start(visualizer_,true,true);
    visualFrame->add(*visualBox);
    playerPage_.pack_start(*visualFrame, true, true);
  }

  Gtk::Frame* section(const std::string& title) {
    auto* frame = Gtk::make_managed<Gtk::Frame>(title); frame->set_margin_bottom(10); frame->set_hexpand(true); return frame;
  }

  Gtk::Scale* slider(double low, double high, double step, double value) {
    auto* scale = Gtk::make_managed<SettingsScale>(
        [this](GdkEventScroll* event) { scrollSettingsInstead(event); });
    scale->set_range(low, high); scale->set_increments(step, step * 10); scale->set_value(value);
    scale->set_digits(step < 1 ? 2 : 0); scale->set_hexpand(true);
    return scale;
  }

  bool scrollSettingsInstead(GdkEventScroll* event) {
    const auto adjustment = settingsScroll_.get_vadjustment();
    if (!event || !adjustment) return true;
    double amount = 0;
    if (event->direction == GDK_SCROLL_SMOOTH) {
      double horizontal = 0;
      gdk_event_get_scroll_deltas(reinterpret_cast<GdkEvent*>(event),
                                  &horizontal, &amount);
    } else if (event->direction == GDK_SCROLL_UP ||
               event->direction == GDK_SCROLL_LEFT) {
      amount = -1;
    } else if (event->direction == GDK_SCROLL_DOWN ||
               event->direction == GDK_SCROLL_RIGHT) {
      amount = 1;
    }
    const auto distance = std::max(40.0,
        std::min(100.0, adjustment->get_page_size() * .10));
    const auto maximum = std::max(adjustment->get_lower(),
        adjustment->get_upper() - adjustment->get_page_size());
    adjustment->set_value(clamp(adjustment->get_value() + amount * distance,
                                adjustment->get_lower(), maximum));
    return true;
  }

  void buildSettingsPage() {
    settingsPage_.set_spacing(8); settingsPage_.set_margin_left(14); settingsPage_.set_margin_right(14);
    settingsPage_.set_margin_top(12); settingsPage_.set_margin_bottom(18);
    const auto redirectComboScroll = [this](SettingsComboBoxText& combo) {
      combo.setScrollPage(
          [this](GdkEventScroll* event) { scrollSettingsInstead(event); });
    };
    redirectComboScroll(playlistCombo_);
    redirectComboScroll(fftSize_);
    redirectComboScroll(scale_);
    redirectComboScroll(microphone_);
    backButton_.set_label("Back to player"); keepNaturalWidth(backButton_);
    backButton_.signal_clicked().connect([this]{ stack_.set_visible_child("player"); });
    settingsPage_.pack_start(backButton_, false, false);

    auto* playlistFrame = section("Playlists and music"); auto* playlistBox = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_VERTICAL, 7);
    playlistBox->set_margin_left(10); playlistBox->set_margin_right(10); playlistBox->set_margin_top(8); playlistBox->set_margin_bottom(10);
    auto* namedRow = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL, 6);
    playlistCombo_.set_hexpand(true); playlistCombo_.signal_changed().connect([this]{ switchPlaylist(); });
    auto* newList = textButton("New"); auto* rename = textButton("Rename");
    auto* deleteList = textButton("Delete");
    newList->signal_clicked().connect([this]{ createPlaylist(); }); rename->signal_clicked().connect([this]{ renamePlaylist(); });
    deleteList->signal_clicked().connect([this]{ deletePlaylist(); });
    namedRow->pack_start(playlistCombo_, true, true); namedRow->pack_start(*newList, false, false);
    namedRow->pack_start(*rename, false, false); namedRow->pack_start(*deleteList, false, false); playlistBox->pack_start(*namedRow, false, false);
    auto* addRow = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL, 6);
    auto* addFiles = textButton("Add files"); auto* addFolder = textButton("Add folder");
    auto* addServer = textButton("Add from server"); auto* remove = textButton("Remove selected");
    addFiles->signal_clicked().connect([this]{ addLocalFiles(); }); addFolder->signal_clicked().connect([this]{ addLocalFolder(); });
    addServer->signal_clicked().connect([this]{ addFromServer(); }); remove->signal_clicked().connect([this]{ removeSelected(); });
    addRow->pack_start(*addFiles, false, false); addRow->pack_start(*addFolder, false, false);
    addRow->pack_start(*addServer, false, false); addRow->pack_start(*remove, false, false);
    playlistBox->pack_start(*addRow, false, false);
    auto* shareRow = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL, 6);
    auto* share = textButton("Share this playlist"); auto* download = textButton("Get shared playlist");
    auto* ask = textButton("Ask Liam");
    share->signal_clicked().connect([this]{ shareCurrentPlaylist(); }); download->signal_clicked().connect([this]{ getSharedPlaylist(); });
    ask->signal_clicked().connect([this]{ askLiam(); });
    shareRow->pack_start(*share, false, false); shareRow->pack_start(*download, false, false);
    shareRow->pack_start(*ask, false, false); playlistBox->pack_start(*shareRow, false, false);
    auto* searchRow = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL, 6);
    playlistSearch_.set_placeholder_text("Search this playlist"); playlistSearch_.set_hexpand(true);
    playlistSearch_.signal_search_changed().connect([this]{ refreshPlaylist(); });
    searchRow->pack_start(playlistSearch_, true, true); playlistBox->pack_start(*searchRow, false, false);
    trackStore_ = Gtk::ListStore::create(trackColumns_); trackView_.set_model(trackStore_);
    trackView_.append_column("Title", trackColumns_.title); trackView_.append_column("Artist", trackColumns_.artist);
    trackView_.append_column("Album", trackColumns_.album); trackView_.set_headers_visible(true);
    trackView_.get_selection()->set_mode(Gtk::SELECTION_MULTIPLE);
    trackView_.signal_row_activated().connect([this](const Gtk::TreeModel::Path& path, Gtk::TreeViewColumn*){
      const auto iter=trackStore_->get_iter(path); if(!iter)return;
      playAtIndex((*iter)[trackColumns_.index]); stack_.set_visible_child("player");
    });
    trackScroll_ = Gtk::make_managed<Gtk::ScrolledWindow>(); trackScroll_->set_policy(Gtk::POLICY_AUTOMATIC, Gtk::POLICY_AUTOMATIC);
    trackScroll_->set_propagate_natural_width(false); trackScroll_->set_min_content_height(220); trackScroll_->add(trackView_);
    playlistBox->pack_start(*trackScroll_, true, true); playlistFrame->add(*playlistBox); settingsPage_.pack_start(*playlistFrame, false, false);

    auto* soundFrame = section("Sound and leveling"); auto* sound = Gtk::make_managed<Gtk::Grid>();
    sound->set_row_spacing(6); sound->set_column_spacing(10); sound->set_margin_left(10); sound->set_margin_right(10); sound->set_margin_top(8); sound->set_margin_bottom(10);
    outputLevel_ = slider(.1, 1, .01, state_.outputLevel); levelingStrength_ = slider(0, 1, .01, state_.levelingStrength);
    analysisSeconds_=slider(0,45,1,state_.leveling.analysisSeconds);levelAttack_=slider(1,250,1,state_.leveling.levelAttackMs);
    levelRelease_=slider(100,5000,10,state_.leveling.levelReleaseMs);gainDown_=slider(5,500,1,state_.leveling.gainDownMs);
    gainUp_=slider(500,10000,10,state_.leveling.gainUpMs);compressorThreshold_=slider(.3,.95,.01,state_.leveling.compressorThreshold);
    outputCeiling_=slider(.5,1,.01,state_.leveling.outputCeiling);
    sound->attach(*Gtk::make_managed<Gtk::Label>("Output level"),0,0,1,1); sound->attach(*outputLevel_,1,0,1,1);
    sound->attach(*Gtk::make_managed<Gtk::Label>("Leveling strength"),0,1,1,1); sound->attach(*levelingStrength_,1,1,1,1);
    int soundRow=2;for(auto pair:std::vector<std::pair<const char*,Gtk::Widget*>>{{"Loudness scan seconds",analysisSeconds_},{"Level attack (ms)",levelAttack_},{"Level release (ms)",levelRelease_},{"Gain down (ms)",gainDown_},{"Gain up (ms)",gainUp_},{"Compressor threshold",compressorThreshold_},{"Output ceiling",outputCeiling_}}){auto*label=Gtk::make_managed<Gtk::Label>(pair.first);label->set_halign(Gtk::ALIGN_START);sound->attach(*label,0,soundRow,1,1);sound->attach(*pair.second,1,soundRow++,1,1);}
    outputLevel_->signal_value_changed().connect([this]{ state_.outputLevel=outputLevel_->get_value(); applySettings(); });
    levelingStrength_->signal_value_changed().connect([this]{ state_.levelingStrength=levelingStrength_->get_value(); applySettings(); });
    auto levelingChanged=[this]{state_.leveling.analysisSeconds=analysisSeconds_->get_value();state_.leveling.levelAttackMs=levelAttack_->get_value();state_.leveling.levelReleaseMs=levelRelease_->get_value();state_.leveling.gainDownMs=gainDown_->get_value();state_.leveling.gainUpMs=gainUp_->get_value();state_.leveling.compressorThreshold=compressorThreshold_->get_value();state_.leveling.outputCeiling=outputCeiling_->get_value();state_.leveling.normalize();applySettings();};
    analysisSeconds_->signal_value_changed().connect(levelingChanged);levelAttack_->signal_value_changed().connect(levelingChanged);levelRelease_->signal_value_changed().connect(levelingChanged);gainDown_->signal_value_changed().connect(levelingChanged);gainUp_->signal_value_changed().connect(levelingChanged);compressorThreshold_->signal_value_changed().connect(levelingChanged);outputCeiling_->signal_value_changed().connect(levelingChanged);
    soundFrame->add(*sound); settingsPage_.pack_start(*soundFrame, false, false);

    auto* visualFrame = section("Visualization"); auto* visual = Gtk::make_managed<Gtk::Grid>();
    visual->set_row_spacing(6); visual->set_column_spacing(10); visual->set_margin_left(10); visual->set_margin_right(10); visual->set_margin_top(8); visual->set_margin_bottom(10);
    fps_ = slider(5,144,1,state_.visualization.updateFps); waveformMs_ = slider(10,500,1,state_.visualization.waveformWindowMs);
    bars_ = slider(24,256,1,state_.visualization.fftColumns); smoothing_ = slider(0,100,1,state_.visualization.fftSmoothing);
    for (int value : {512,1024,2048,4096,8192,16384,32768}) fftSize_.append(std::to_string(value));
    fftSize_.set_active_text(std::to_string(state_.visualization.fftSize));
    scale_.append("log"); scale_.append("linear"); scale_.set_active_text(state_.visualization.fftScale);
    int row=0; for (auto pair : std::vector<std::pair<const char*,Gtk::Widget*>>{{"Frames per second",fps_},{"Waveform window (ms)",waveformMs_},{"Spectrum bars",bars_},{"FFT size",&fftSize_},{"FFT scale",&scale_},{"Smoothing",smoothing_}}) {
      auto* label=Gtk::make_managed<Gtk::Label>(pair.first); label->set_halign(Gtk::ALIGN_START); visual->attach(*label,0,row,1,1); visual->attach(*pair.second,1,row++,1,1);
    }
    auto visualChanged=[this]{ state_.visualization.updateFps=fps_->get_value(); state_.visualization.waveformWindowMs=waveformMs_->get_value();
      state_.visualization.fftColumns=static_cast<int>(bars_->get_value()); state_.visualization.fftSmoothing=smoothing_->get_value();
      try { state_.visualization.fftSize=std::stoi(fftSize_.get_active_text()); } catch (...) {}
      state_.visualization.fftScale=scale_.get_active_text(); state_.visualization.normalize(); visualRequestedFps_=state_.visualization.updateFps;nextVisualFrameUs_=0;visualizer_.setSettings(state_.visualization);lastVisualStatusUs_=0;lastProducedFrames_=0;measuredAnalysisFps_=-1;visualStatus_.set_text(visualizationSummary(state_.visualization)); visualPerformanceStatus_.set_text(""); applySettings(); };
    fps_->signal_value_changed().connect(visualChanged); waveformMs_->signal_value_changed().connect(visualChanged);
    bars_->signal_value_changed().connect(visualChanged); smoothing_->signal_value_changed().connect(visualChanged);
    fftSize_.signal_changed().connect(visualChanged); scale_.signal_changed().connect(visualChanged);
    visualFrame->add(*visual); settingsPage_.pack_start(*visualFrame, false, false);

    auto* cacheFrame=section("Native cache");cacheLabel_.set_halign(Gtk::ALIGN_START);cacheLabel_.set_xalign(0);cacheLabel_.set_margin_left(10);cacheLabel_.set_margin_right(10);cacheLabel_.set_margin_top(8);cacheLabel_.set_margin_bottom(10);cacheFrame->add(cacheLabel_);settingsPage_.pack_start(*cacheFrame,false,false);

    auto* serverFrame = section("FredPlayer server"); auto* server = Gtk::make_managed<Gtk::Grid>();
    server->set_row_spacing(6); server->set_column_spacing(10); server->set_margin_left(10); server->set_margin_right(10); server->set_margin_top(8); server->set_margin_bottom(10);
    serverUrl_.set_text(state_.serverBaseUrl); serverToken_.set_text(state_.serverToken); serverToken_.set_visibility(false);
    server->attach(*Gtk::make_managed<Gtk::Label>("Server URL"),0,0,1,1); server->attach(serverUrl_,1,0,1,1);
    server->attach(*Gtk::make_managed<Gtk::Label>("Access token"),0,1,1,1); server->attach(serverToken_,1,1,1,1);
    auto saveServer=[this]{ state_.serverBaseUrl=serverUrl_.get_text(); while(!state_.serverBaseUrl.empty()&&state_.serverBaseUrl.back()=='/')state_.serverBaseUrl.pop_back();
      state_.serverToken=serverToken_.get_text(); audio_->configureServer(state_.serverBaseUrl,state_.serverToken); saveState(); };
    serverUrl_.signal_changed().connect(saveServer); serverToken_.signal_changed().connect(saveServer);
    auto* serverActions = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL, 6);
    auto* rescan = textButton("Rescan library"); rescan->signal_clicked().connect([this]{ rescanServerLibrary(); });
    serverActions->pack_start(*rescan, false, false);
    server->attach(*serverActions,1,2,1,1);
    serverFrame->add(*server); settingsPage_.pack_start(*serverFrame, false, false);

    latencyFrame_ = section("Speaker synchronization"); latencyBox_.set_spacing(6); latencyBox_.set_margin_left(10); latencyBox_.set_margin_right(10);
    latencyBox_.set_margin_top(8); latencyBox_.set_margin_bottom(10); latencyBox_.pack_start(outputLabel_, false, false); latencyBox_.pack_start(latencyLabel_, false, false);
    calibrationsLabel_.set_halign(Gtk::ALIGN_START);calibrationsLabel_.set_xalign(0);calibrationsLabel_.set_line_wrap(true);latencyBox_.pack_start(calibrationsLabel_,false,false);
    latencyBox_.pack_start(microphone_, false, false); calibrate_.set_label("Calibrate with microphone"); keepNaturalWidth(calibrate_);
    calibrate_.signal_clicked().connect([this]{ startCalibration(); }); latencyBox_.pack_start(calibrate_, false, false);
    auto* clearOne = textButton("Clear this speaker calibration"); auto* clearAll = textButton("Clear all speaker calibrations");
    clearOne->signal_clicked().connect([this]{ state_.speakerLatencies.erase(cachedOutput_.key); applySettings(); refreshLatencyUi(); });
    clearAll->signal_clicked().connect([this]{ state_.speakerLatencies.clear(); applySettings(); refreshLatencyUi(); });
    auto* clearRow=Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL,6); clearRow->pack_start(*clearOne,false,false); clearRow->pack_start(*clearAll,false,false); latencyBox_.pack_start(*clearRow,false,false);
    latencyFrame_->add(latencyBox_); settingsPage_.pack_start(*latencyFrame_, false, false);
  }

  void applySettings() {
    audio_->configure(state_.outputLevel,state_.levelingStrength,state_.leveling,state_.visualization,currentVisualDelay());
    saveState();
  }

  int currentVisualDelay() const {
    const auto& output=cachedOutput_; const auto found=state_.speakerLatencies.find(output.key);
    return found==state_.speakerLatencies.end()?systemLatencyMs_:found->second.delayMs;
  }

  void saveState() {
    state_.namedPlaylists[state_.activePlaylist]=state_.playlist;
    try { store_.save(state_); } catch (const std::exception& error) { g_warning("%s",error.what()); }
  }

  void refreshPlaylist() {
    changingPlaylist_=true; playlistCombo_.remove_all();
    for(const auto& [name,_]:state_.namedPlaylists)playlistCombo_.append(name);
    playlistCombo_.set_active_text(state_.activePlaylist); changingPlaylist_=false;
    trackStore_->clear();
    const auto query=toLowerAscii(playlistSearch_.get_text());
    int matched=0;
    for(std::size_t i=0;i<state_.playlist.size();++i){const auto& track=state_.playlist[i];
      if(!query.empty()){const auto searchable=toLowerAscii(track.displayTitle()+"\n"+track.artist+"\n"+track.album);
        if(searchable.find(query)==std::string::npos)continue;}
      ++matched;auto row=*(trackStore_->append());
      row[trackColumns_.title]=track.displayTitle();row[trackColumns_.artist]=track.artist;row[trackColumns_.album]=track.album;row[trackColumns_.path]=track.path;row[trackColumns_.index]=i;}
    playlistStatus_.set_text(query.empty()
        ?state_.activePlaylist+" · "+std::to_string(state_.playlist.size())+" songs"
        :state_.activePlaylist+" · "+std::to_string(matched)+" of "+std::to_string(state_.playlist.size())+" songs");
  }

  void hydrateLocalMetadata(){std::set<std::string> paths;for(const auto&[name,entries]:state_.namedPlaylists)for(const auto&track:entries)if(!track.remote&&(track.title.empty()||track.artist.empty()||track.album.empty()))paths.insert(track.path);if(paths.empty())return;
    std::thread([this,paths=std::move(paths)]{std::unordered_map<std::string,TrackEntry> metadata;for(const auto&path:paths)metadata.emplace(path,readLocalMetadata(path));Glib::signal_idle().connect_once([this,metadata=std::move(metadata)]{if(!windowAlive.load())return;for(auto&[name,entries]:state_.namedPlaylists)for(auto&track:entries)if(auto found=metadata.find(track.path);found!=metadata.end()){track.title=found->second.title;track.artist=found->second.artist;track.album=found->second.album;}state_.playlist=state_.namedPlaylists[state_.activePlaylist];refreshPlaylist();updateNowPlaying();});}).detach();}

  void switchPlaylist(){if(changingPlaylist_)return;auto name=playlistCombo_.get_active_text();if(name.empty()||!state_.namedPlaylists.count(name))return;
    stop();state_.activePlaylist=name;state_.playlist=state_.namedPlaylists[name];currentIndex_=-1;shuffleBag_.clear();playHistory_.clear();historyIndex_=-1;refreshPlaylist();refreshQueueWindow();saveState();}

  std::string prompt(const std::string& title,const std::string& initial={}){Gtk::Dialog dialog(title,*this,true);
    dialog.set_default_size(420,-1);
    auto* entry=Gtk::make_managed<Gtk::Entry>();entry->set_text(initial);entry->set_activates_default(true);
    entry->set_margin_left(16);entry->set_margin_right(16);entry->set_margin_top(14);entry->set_margin_bottom(10);
    dialog.get_content_area()->pack_start(*entry);dialog.add_button("Cancel",Gtk::RESPONSE_CANCEL);dialog.add_button("OK",Gtk::RESPONSE_OK);dialog.set_default_response(Gtk::RESPONSE_OK);dialog.show_all();return dialog.run()==Gtk::RESPONSE_OK?entry->get_text():"";}
  void createPlaylist(){auto name=prompt("New playlist name");if(name.empty()||state_.namedPlaylists.count(name))return;state_.namedPlaylists[name]={};state_.activePlaylist=name;state_.playlist={};shuffleBag_.clear();playHistory_.clear();historyIndex_=-1;refreshPlaylist();refreshQueueWindow();saveState();}
  void renamePlaylist(){auto name=prompt("Rename playlist",state_.activePlaylist);if(name.empty()||name==state_.activePlaylist||state_.namedPlaylists.count(name))return;
    auto values=state_.playlist;state_.namedPlaylists.erase(state_.activePlaylist);state_.namedPlaylists[name]=values;state_.activePlaylist=name;refreshPlaylist();saveState();}
  void deletePlaylist(){if(state_.namedPlaylists.size()<=1)return;state_.namedPlaylists.erase(state_.activePlaylist);state_.activePlaylist=state_.namedPlaylists.begin()->first;state_.playlist=state_.namedPlaylists.begin()->second;stop();shuffleBag_.clear();playHistory_.clear();historyIndex_=-1;refreshPlaylist();refreshQueueWindow();saveState();}

  void mergeTracks(std::vector<TrackEntry> tracks){std::set<std::string> seen;for(const auto& t:state_.playlist)seen.insert(t.path);for(auto& t:tracks)if(seen.insert(t.path).second)state_.playlist.push_back(std::move(t));refreshPlaylist();saveState();}
  void addLocalFiles(){Gtk::FileChooserDialog dialog(*this,"Add music files",Gtk::FILE_CHOOSER_ACTION_OPEN);dialog.set_select_multiple(true);dialog.add_button("Cancel",Gtk::RESPONSE_CANCEL);dialog.add_button("Add",Gtk::RESPONSE_OK);
    if(dialog.run()!=Gtk::RESPONSE_OK)return;
    std::vector<TrackEntry> values;for(const auto& path:dialog.get_filenames())if(isAudioFile(path))values.push_back(readLocalMetadata(path));mergeTracks(std::move(values));}
  void addLocalFolder(){Gtk::FileChooserDialog dialog(*this,"Add music folder",Gtk::FILE_CHOOSER_ACTION_SELECT_FOLDER);dialog.add_button("Cancel",Gtk::RESPONSE_CANCEL);dialog.add_button("Add",Gtk::RESPONSE_OK);
    if(dialog.run()!=Gtk::RESPONSE_OK)return;
    const auto root=dialog.get_filename();status_.set_text("Scanning folder…");std::thread([this,root]{std::vector<TrackEntry> values;std::error_code error;for(std::filesystem::recursive_directory_iterator it(root,error),end;it!=end&&!error;it.increment(error))if(it->is_regular_file()&&isAudioFile(it->path()))values.push_back(readLocalMetadata(it->path(),root));Glib::signal_idle().connect_once([this,values=std::move(values)]()mutable{if(windowAlive.load()){mergeTracks(std::move(values));status_.set_text("");}});}).detach();}
  void removeSelected() {
    std::vector<int> indexes;
    for (const auto& path : trackView_.get_selection()->get_selected_rows()) {
      const auto iter = trackStore_->get_iter(path);
      if (iter) indexes.push_back((*iter)[trackColumns_.index]);
    }
    if (indexes.empty()) return;

    const auto firstRemoved = *std::min_element(indexes.begin(), indexes.end());
    double scrollPosition = 0;
    if (trackScroll_) {
      const auto adjustment = trackScroll_->get_vadjustment();
      if (adjustment) scrollPosition = adjustment->get_value();
    }

    // Every stored index (currentIndex_, the shuffle bag, play history)
    // above a removed row silently points one slot too far right once
    // that row is gone — the same problem removeCurrentTrack() already
    // guards against for the current track alone. Removing an arbitrary
    // selection needs the same treatment, or "next"/"previous"/the What's
    // Next queue can end up pointing at a completely different song.
    const bool removingCurrent = currentIndex_ >= 0 &&
        std::find(indexes.begin(), indexes.end(), currentIndex_) != indexes.end();

    std::vector<int> removedAscending = indexes;
    std::sort(removedAscending.begin(), removedAscending.end());

    std::sort(indexes.rbegin(), indexes.rend());
    for (const int index : indexes) {
      if (index >= 0 && index < static_cast<int>(state_.playlist.size()))
        state_.playlist.erase(state_.playlist.begin() + index);
    }

    // Surgical, not a wipe: only the deleted tracks drop out of the
    // shuffle bag/history, everything else keeps its place — so deleting
    // an unrelated song doesn't also reset your shuffle order or history.
    remapStoredIndicesAfterRemoval(removedAscending);

    if (state_.playlist.empty()) {
      currentIndex_ = -1;
      stop();
    } else if (removingCurrent) {
      if (state_.shuffleEnabled) chooseNext();
      else currentIndex_ = std::min(firstRemoved, static_cast<int>(state_.playlist.size()) - 1);
      recordHistory(currentIndex_);
      if (audio_->playing()) audio_->play(state_.playlist[currentIndex_]);
    } else if (currentIndex_ >= 0) {
      // Just shifted to keep pointing at the same track — its identity
      // didn't change, only its position did, so no new history entry.
      int shift = 0;
      for (const int removed : removedAscending) if (removed < currentIndex_) ++shift;
      currentIndex_ -= shift;
      currentIndex_ = clamp(currentIndex_, 0, static_cast<int>(state_.playlist.size()) - 1);
    }
    updateNowPlaying();
    refreshQueueWindow();
    refreshPlaylist();
    saveState();

    const auto playlistName = state_.activePlaylist;
    const auto nextIndex = state_.playlist.empty()
        ? -1
        : std::min(firstRemoved, static_cast<int>(state_.playlist.size()) - 1);
    Glib::signal_idle().connect_once(
        [this, playlistName, nextIndex, scrollPosition] {
          if (!windowAlive.load() || state_.activePlaylist != playlistName) return;
          if (nextIndex >= 0) {
            const Gtk::TreeModel::Path path(std::to_string(nextIndex));
            trackView_.set_cursor(path);
          }
          if (!trackScroll_) return;
          const auto adjustment = trackScroll_->get_vadjustment();
          if (!adjustment) return;
          const auto maximum = std::max(adjustment->get_lower(),
              adjustment->get_upper() - adjustment->get_page_size());
          adjustment->set_value(clamp(scrollPosition,
              adjustment->get_lower(), maximum));
        });
  }

  std::vector<int> browseServerTracks(const std::vector<TrackEntry>& tracks) {
    constexpr int addSelectedResponse = 1;
    constexpr int addFolderResponse = 2;
    Gtk::Dialog dialog("Add from server", *this, true);
    dialog.set_default_size(1080, 680);
    dialog.set_size_request(760, 480);
    dialog.add_button("Cancel", Gtk::RESPONSE_CANCEL);
    auto* addSelected = dialog.add_button(
        "Add selected tracks", addSelectedResponse);
    auto* addFolder = dialog.add_button("Add all music", addFolderResponse);
    dialog.set_response_sensitive(addSelectedResponse, false);
    keepNaturalWidth(*addSelected);
    keepNaturalWidth(*addFolder);

    auto* content = dialog.get_content_area();
    content->set_spacing(10);
    content->set_margin_left(12);
    content->set_margin_right(12);
    content->set_margin_top(12);
    content->set_margin_bottom(8);

    auto* searchRow = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL, 10);
    auto* searchLabel = Gtk::make_managed<Gtk::Label>("Search");
    Gtk::SearchEntry search;
    search.set_placeholder_text("Title, artist, album, or folder");
    search.set_hexpand(true);
    Gtk::Label summary;
    summary.set_halign(Gtk::ALIGN_END);
    summary.get_style_context()->add_class("muted");
    searchRow->pack_start(*searchLabel, false, false);
    searchRow->pack_start(search, true, true);
    searchRow->pack_start(summary, false, false);
    content->pack_start(*searchRow, false, false);

    FolderColumns folderColumns;
    auto folderStore = Gtk::TreeStore::create(folderColumns);
    Gtk::TreeView folderView(folderStore);
    folderView.append_column("Folder", folderColumns.name);
    folderView.append_column("Tracks", folderColumns.trackCount);
    folderView.set_headers_visible(true);
    folderView.get_selection()->set_mode(Gtk::SELECTION_SINGLE);

    std::map<std::string, int> folderCounts;
    folderCounts[""] = static_cast<int>(tracks.size());
    for (const auto& track : tracks) {
      auto folder = track.sourceFolder;
      while (!folder.empty()) {
        ++folderCounts[folder];
        const auto slash = folder.find_last_of('/');
        if (slash == std::string::npos) break;
        folder = folder.substr(0, slash);
      }
    }

    std::map<std::string, Gtk::TreeModel::iterator> folderRows;
    const auto root = folderStore->append();
    (*root)[folderColumns.name] = "All music";
    (*root)[folderColumns.path] = "";
    (*root)[folderColumns.trackCount] = static_cast<int>(tracks.size());
    folderRows.emplace("", root);
    for (const auto& [folder, count] : folderCounts) {
      if (folder.empty()) continue;
      const auto slash = folder.find_last_of('/');
      const auto parent = slash == std::string::npos
          ? std::string{} : folder.substr(0, slash);
      const auto name = slash == std::string::npos
          ? folder : folder.substr(slash + 1);
      const auto parentRow = folderRows.find(parent);
      if (parentRow == folderRows.end()) continue;
      const auto iter = folderStore->append((*parentRow->second).children());
      (*iter)[folderColumns.name] = name;
      (*iter)[folderColumns.path] = folder;
      (*iter)[folderColumns.trackCount] = count;
      folderRows.emplace(folder, iter);
    }

    ServerTrackColumns serverColumns;
    auto trackStore = Gtk::ListStore::create(serverColumns);
    Gtk::TreeView trackView(trackStore);
    trackView.append_column("Title", serverColumns.title);
    trackView.append_column("Artist", serverColumns.artist);
    trackView.append_column("Album", serverColumns.album);
    trackView.append_column("Folder", serverColumns.folder);
    trackView.set_headers_visible(true);
    trackView.get_selection()->set_mode(Gtk::SELECTION_MULTIPLE);

    Gtk::ScrolledWindow folderScroll;
    folderScroll.set_policy(Gtk::POLICY_AUTOMATIC, Gtk::POLICY_AUTOMATIC);
    folderScroll.set_size_request(300, -1);
    folderScroll.add(folderView);
    Gtk::ScrolledWindow trackScroll;
    trackScroll.set_policy(Gtk::POLICY_AUTOMATIC, Gtk::POLICY_AUTOMATIC);
    trackScroll.add(trackView);
    Gtk::Paned panes(Gtk::ORIENTATION_HORIZONTAL);
    panes.pack1(folderScroll, false, false);
    panes.pack2(trackScroll, true, false);
    panes.set_position(300);
    content->pack_start(panes, true, true);

    std::string selectedFolder;
    const auto isInFolder = [](const TrackEntry& track,
                               const std::string& folder) {
      if (folder.empty()) return true;
      return track.sourceFolder == folder ||
          track.sourceFolder.rfind(folder + '/', 0) == 0;
    };
    const auto lowercase = [](std::string value) {
      std::transform(value.begin(), value.end(), value.begin(),
          [](const unsigned char character) {
            return static_cast<char>(std::tolower(character));
          });
      return value;
    };

    std::function<void()> refreshTracks;
    refreshTracks = [&] {
      trackStore->clear();
      const auto query = lowercase(search.get_text());
      int folderTrackCount = 0;
      int matchingTrackCount = 0;
      for (std::size_t index = 0; index < tracks.size(); ++index) {
        const auto& track = tracks[index];
        if (!isInFolder(track, selectedFolder)) continue;
        ++folderTrackCount;
        const auto searchable = lowercase(track.displayTitle() + "\n" +
            track.artist + "\n" + track.album + "\n" + track.sourceFolder);
        if (!query.empty() && searchable.find(query) == std::string::npos)
          continue;
        auto row = *(trackStore->append());
        row[serverColumns.title] = track.displayTitle();
        row[serverColumns.artist] = track.artist;
        row[serverColumns.album] = track.album;
        row[serverColumns.folder] = track.sourceFolder;
        row[serverColumns.index] = static_cast<int>(index);
        ++matchingTrackCount;
      }
      if (query.empty()) {
        summary.set_text(std::to_string(folderTrackCount) + " tracks");
      } else {
        summary.set_text(std::to_string(matchingTrackCount) + " of " +
            std::to_string(folderTrackCount) + " tracks");
      }
      if (selectedFolder.empty()) {
        addFolder->set_label("Add all music (" +
            std::to_string(folderTrackCount) + ")");
      } else {
        addFolder->set_label("Add entire folder (" +
            std::to_string(folderTrackCount) + ")");
      }
      dialog.set_response_sensitive(addFolderResponse, folderTrackCount > 0);
      dialog.set_response_sensitive(addSelectedResponse, false);
      addSelected->set_label("Add selected tracks");
    };

    folderView.get_selection()->signal_changed().connect([&] {
      const auto selected = folderView.get_selection()->get_selected();
      if (!selected) return;
      const Glib::ustring folder = (*selected)[folderColumns.path];
      selectedFolder = folder.raw();
      refreshTracks();
    });
    search.signal_search_changed().connect(refreshTracks);
    trackView.get_selection()->signal_changed().connect([&] {
      const auto count = trackView.get_selection()->count_selected_rows();
      dialog.set_response_sensitive(addSelectedResponse, count > 0);
      addSelected->set_label(count > 0
          ? "Add selected tracks (" + std::to_string(count) + ")"
          : "Add selected tracks");
    });
    trackView.signal_row_activated().connect(
        [&](const Gtk::TreeModel::Path&, Gtk::TreeViewColumn*) {
          if (trackView.get_selection()->count_selected_rows() > 0)
            dialog.response(addSelectedResponse);
        });

    dialog.show_all();
    folderView.get_selection()->select(Gtk::TreeModel::Path("0"));
    folderView.expand_row(Gtk::TreeModel::Path("0"), false);
    refreshTracks();
    search.grab_focus();
    const auto response = dialog.run();

    std::vector<int> result;
    if (response == addSelectedResponse) {
      for (const auto& path : trackView.get_selection()->get_selected_rows()) {
        const auto iter = trackStore->get_iter(path);
        if (iter) result.push_back((*iter)[serverColumns.index]);
      }
    } else if (response == addFolderResponse) {
      for (std::size_t index = 0; index < tracks.size(); ++index) {
        if (isInFolder(tracks[index], selectedFolder))
          result.push_back(static_cast<int>(index));
      }
    }
    return result;
  }

  void addFromServer() {
    const auto base=state_.serverBaseUrl,token=state_.serverToken;status_.set_text("Loading server library…");
    std::thread([this,base,token]{std::vector<TrackEntry> tracks;std::string error;try{tracks=ServerClient(base,token).library();}catch(const std::exception&e){error=e.what();}
      Glib::signal_idle().connect_once([this,tracks=std::move(tracks),error]()mutable{if(!windowAlive.load())return;status_.set_text("");if(!error.empty()){showError(*this,error);return;}auto selected=browseServerTracks(tracks);std::vector<TrackEntry> values;for(int i:selected)if(i>=0&&i<(int)tracks.size())values.push_back(tracks[i]);mergeTracks(std::move(values));});}).detach();
  }
  void rescanServerLibrary() {
    const auto base=state_.serverBaseUrl,token=state_.serverToken;
    if(base.empty()){showError(*this,"Configure the FredPlayer server first");return;}
    status_.set_text("Rescanning server library…");
    std::thread([this,base,token]{int count=-1;std::string error;try{count=ServerClient(base,token).rescanLibrary();}catch(const std::exception&e){error=e.what();}
      Glib::signal_idle().connect_once([this,count,error]{if(!windowAlive.load())return;if(!error.empty()){status_.set_text("");showError(*this,error);return;}status_.set_text("Rescanned "+std::to_string(count)+" tracks; missing playback data is queued");});}).detach();
  }
  void shareCurrentPlaylist() {
    if(state_.playlist.empty()){showError(*this,"Add songs before sharing this playlist");return;}
    // ServerClient::sharePlaylist() throws immediately on the first track
    // that isn't from this server, aborting the whole upload with just a
    // terse error dialog and no indication anything besides "an error"
    // happened — easy to dismiss without realizing the playlist was never
    // created. Checking up front, with the same explanation Android shows,
    // means the playlist actually explains itself instead of just failing.
    for(const auto& track:state_.playlist){
      if(track.serverPath(state_.serverBaseUrl).empty()){
        showError(*this,"Every song must come from this Fred Server. Local files and songs "
            "from another server cannot be played by the other devices.");
        return;
      }
    }
    const auto base=state_.serverBaseUrl,token=state_.serverToken,name=state_.activePlaylist;const auto tracks=state_.playlist;status_.set_text("Sharing playlist…");
    std::thread([this,base,token,name,tracks]{std::string error;try{ServerClient(base,token).sharePlaylist(name,tracks);}catch(const std::exception&e){error=e.what();}
      Glib::signal_idle().connect_once([this,name,error]{if(!windowAlive.load())return;if(error.empty())status_.set_text("Shared playlist: "+name);else showError(*this,error);});}).detach();
  }
  void getSharedPlaylist() {
    const auto base=state_.serverBaseUrl,token=state_.serverToken;status_.set_text("Loading shared playlists…");
    std::thread([this,base,token]{std::vector<std::string> names;std::string error;try{names=ServerClient(base,token).sharedPlaylists();}catch(const std::exception&e){error=e.what();}
      Glib::signal_idle().connect_once([this,base,token,names=std::move(names),error]()mutable{
        if(!windowAlive.load())return;status_.set_text("");if(!error.empty()){showError(*this,error);return;}
        showSharedPlaylistsPopup(base,token,names);
      });}).detach();
  }

  // A themed full list + circular back button (matching the Android app's
  // own "Shared playlists" screen) instead of a small combo-box dialog,
  // which read as a generic system prompt rather than part of this app.
  void showSharedPlaylistsPopup(const std::string& base,const std::string& token,
      const std::vector<std::string>& names){
    Gtk::Dialog dialog("Shared playlists",*this,true);
    dialog.set_default_size(480,560);

    auto* content=dialog.get_content_area();
    content->set_spacing(10);
    content->set_margin_left(14);content->set_margin_right(14);
    content->set_margin_top(12);content->set_margin_bottom(12);

    auto* header=Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_HORIZONTAL,8);
    auto* close=iconButton("go-previous-symbolic","Close");
    close->get_style_context()->add_class("icon-round");
    close->signal_clicked().connect([&dialog]{ dialog.response(Gtk::RESPONSE_CANCEL); });
    header->pack_start(*close,false,false);
    auto* title=Gtk::make_managed<Gtk::Label>("Shared playlists");
    title->get_style_context()->add_class("now-title");
    title->set_halign(Gtk::ALIGN_START);
    header->pack_start(*title,true,true);
    content->pack_start(*header,false,false);

    std::string selectedName;
    if(names.empty()){
      auto* empty=Gtk::make_managed<Gtk::Label>("No playlists have been shared yet.");
      empty->get_style_context()->add_class("muted");
      empty->set_margin_top(20);
      content->pack_start(*empty,false,false);
    } else {
      auto* scroll=Gtk::make_managed<Gtk::ScrolledWindow>();
      scroll->set_policy(Gtk::POLICY_NEVER,Gtk::POLICY_AUTOMATIC);
      scroll->set_vexpand(true);
      auto* list=Gtk::make_managed<Gtk::ListBox>();
      list->set_activate_on_single_click(true);
      list->set_selection_mode(Gtk::SELECTION_NONE);
      for(const auto& name:names){
        auto* row=Gtk::make_managed<Gtk::Label>(name);
        row->set_halign(Gtk::ALIGN_START);
        row->set_margin_top(10);row->set_margin_bottom(10);
        row->set_margin_left(6);row->set_margin_right(6);
        list->append(*row);
      }
      list->signal_row_activated().connect([&dialog,&selectedName,&names](Gtk::ListBoxRow* row){
        if(!row)return;
        const auto index=row->get_index();
        if(index<0||index>=(int)names.size())return;
        selectedName=names[index];
        dialog.response(Gtk::RESPONSE_OK);
      });
      scroll->add(*list);
      content->pack_start(*scroll,true,true);
    }

    dialog.show_all();
    const auto response=dialog.run();
    if(response!=Gtk::RESPONSE_OK||selectedName.empty())return;
    downloadSharedPlaylist(base,token,selectedName);
  }

  void downloadSharedPlaylist(const std::string& base,const std::string& token,const std::string& name){
    status_.set_text("Downloading shared playlist…");
    std::thread([this,base,token,name]{std::vector<TrackEntry> tracks;std::string failure;try{ServerClient server(base,token);const auto paths=server.playlistTracks(name);const auto library=server.library();std::unordered_map<std::string,TrackEntry> byUrl;for(const auto&track:library)byUrl.emplace(track.path,track);for(const auto& path:paths){const auto url=server.streamUrl(path);if(auto found=byUrl.find(url);found!=byUrl.end())tracks.push_back(found->second);else{auto slash=path.find_last_of('/');tracks.push_back({url,slash==std::string::npos?"Server":path.substr(0,slash),true,std::filesystem::path(path).stem().string(),"",""});}}}catch(const std::exception&e){failure=e.what();}
      Glib::signal_idle().connect_once([this,name,tracks=std::move(tracks),failure]()mutable{if(!windowAlive.load())return;status_.set_text("");if(!failure.empty()){showError(*this,failure);return;}
        // Matches switchPlaylist()'s reset — without it, currentIndex_
        // (and a leftover shuffle bag) from whatever was active before
        // can point past the end of this new list, or at a different
        // song entirely, showing "No song selected" with a stale error
        // status left over from the last real playback attempt.
        stop();currentIndex_=-1;shuffleBag_.clear();playHistory_.clear();historyIndex_=-1;
        const auto wanted=toLowerAscii(name);
        for(auto it=state_.namedPlaylists.begin();it!=state_.namedPlaylists.end();){
          if(toLowerAscii(it->first)==wanted)it=state_.namedPlaylists.erase(it);
          else ++it;
        }
        state_.namedPlaylists[name]=tracks;state_.activePlaylist=name;state_.playlist=tracks;refreshPlaylist();updateNowPlaying();refreshQueueWindow();saveState();});}).detach();
  }

  std::string uniquePlaylistName(const std::string& base){
    if(!state_.namedPlaylists.count(base))return base;
    for(int n=2;;++n){
      auto candidate=base+" ("+std::to_string(n)+")";
      if(!state_.namedPlaylists.count(candidate))return candidate;
    }
  }

  void showLiamReplyDialog(const std::string& text){
    // A plain Gtk::MessageDialog sizes itself off the message text with no
    // wrap-width control, which for a real multi-sentence answer (not the
    // short one-liners MessageDialog is meant for) rendered oddly — either
    // far too wide or too cramped. A regular Dialog with an explicit size
    // and a word-wrapped, selectable, scrollable label gives a normal,
    // readable window regardless of answer length.
    Gtk::Dialog dialog("Liam",*this,true);
    dialog.set_default_size(480,320);
    auto* content=dialog.get_content_area();
    content->set_margin_left(16);content->set_margin_right(16);
    content->set_margin_top(14);content->set_margin_bottom(10);
    Gtk::ScrolledWindow scroll;scroll.set_policy(Gtk::POLICY_NEVER,Gtk::POLICY_AUTOMATIC);
    scroll.set_vexpand(true);
    Gtk::Label label(text);label.set_line_wrap(true);label.set_xalign(0.0f);
    label.set_selectable(true);
    scroll.add(label);
    content->pack_start(scroll,true,true);
    dialog.add_button("OK",Gtk::RESPONSE_OK);
    dialog.set_default_response(Gtk::RESPONSE_OK);
    dialog.show_all();
    dialog.run();
  }

  void askLiam(){auto message=prompt("Ask Liam");if(message.empty())return;status_.set_text("Asking Liam…");const auto base=state_.serverBaseUrl,token=state_.serverToken,id=persistentDeviceId();
    std::thread([this,base,token,id,message]{
      AskLiamResult result;std::string error;std::vector<TrackEntry> resolved;
      try{
        ServerClient server(base,token);
        result=server.askLiam(id,message);
        if(result.hasPlaylist&&!result.trackPaths.empty()){
          // Same byUrl-against-the-live-library resolution the shared
          // playlist download uses, so proposed tracks get real
          // title/artist/album metadata instead of a bare filename.
          const auto library=server.library();
          std::unordered_map<std::string,TrackEntry> byUrl;
          for(const auto& track:library)byUrl.emplace(track.path,track);
          for(const auto& path:result.trackPaths){
            const auto url=server.streamUrl(path);
            if(auto found=byUrl.find(url);found!=byUrl.end())resolved.push_back(found->second);
            else{
              auto slash=path.find_last_of('/');
              resolved.push_back({url,slash==std::string::npos?"Server":path.substr(0,slash),
                  true,std::filesystem::path(path).stem().string(),"",""});
            }
          }
        }
      }catch(const std::exception& e){error=e.what();}
      Glib::signal_idle().connect_once([this,result,error,resolved]{
        if(!windowAlive.load())return;
        status_.set_text("");
        if(!error.empty()){showError(*this,error);return;}
        if(!result.hasPlaylist||resolved.empty()){
          showLiamReplyDialog(result.reply.empty()?"Liam didn't reply.":result.reply);
          return;
        }
        const auto localName=uniquePlaylistName(
            result.playlistName.empty()?"New Playlist":result.playlistName);
        state_.namedPlaylists[localName]=resolved;
        state_.activePlaylist=localName;
        state_.playlist=resolved;
        currentIndex_=-1;shuffleBag_.clear();playHistory_.clear();historyIndex_=-1;
        refreshPlaylist();updateNowPlaying();refreshQueueWindow();saveState();
        showLiamReplyDialog("Created \""+localName+"\" ("+std::to_string(resolved.size())
            +" songs) — just on this device."+(result.reply.empty()?"":"\n\n"+result.reply));
      });
    }).detach();}

  void play(){
    if(state_.playlist.empty())return;
    if(currentIndex_<0||currentIndex_>=(int)state_.playlist.size()){
      chooseNext();
      if(currentIndex_>=0)recordHistory(currentIndex_);
    }
    if(currentIndex_>=0){audio_->play(state_.playlist[currentIndex_]);updateNowPlaying();}
    refreshQueueWindow();
  }
  // A manual pick — from the playlist or the What's Next queue — always
  // starts a fresh branch: it drops whatever "redo" future recordHistory()
  // was holding onto and pulls the track out of the shuffle bag so it
  // doesn't also come up again on its own soon after.
  void playAtIndex(int index){
    if(index<0||index>=(int)state_.playlist.size())return;
    currentIndex_=index;
    if(state_.shuffleEnabled){
      auto it=std::find(shuffleBag_.begin(),shuffleBag_.end(),index);
      if(it!=shuffleBag_.end())shuffleBag_.erase(it);
    }
    recordHistory(currentIndex_);
    audio_->play(state_.playlist[currentIndex_]);
    updateNowPlaying();
    refreshQueueWindow();
  }
  void togglePlay(){if(!audio_->playing()){play();return;}if(audio_->paused())audio_->resume();else audio_->pause();updateTransport();}
  void stop(){audio_->stop();visualizer_.clear();lastVisualStatusUs_=0;lastProducedFrames_=0;measuredAnalysisFps_=-1;visualPerformanceStatus_.set_text("");updateTransport();}
  void refillShuffleBag(){
    shuffleBag_.resize(state_.playlist.size());
    std::iota(shuffleBag_.begin(),shuffleBag_.end(),0);
    std::shuffle(shuffleBag_.begin(),shuffleBag_.end(),random_);
  }
  void chooseNext(){if(state_.playlist.empty()){currentIndex_=-1;return;}if(!state_.shuffleEnabled){currentIndex_=(currentIndex_+1)%state_.playlist.size();return;}if(shuffleBag_.empty())refillShuffleBag();currentIndex_=shuffleBag_.back();shuffleBag_.pop_back();}
  // If Previous was pressed earlier and hasn't been followed by a new
  // manual pick, historyIndex_ sits behind the end of playHistory_ — in
  // that case Next just replays forward through the same recorded path
  // instead of drawing a fresh pick, so it actually undoes Previous
  // rather than landing on an unrelated track. Only once we're back at
  // the end of history does Next fall through to a new shuffle/sequential
  // pick.
  void next(){
    if(state_.playlist.empty())return;
    if(historyIndex_>=0&&historyIndex_<(int)playHistory_.size()-1){
      ++historyIndex_;
      currentIndex_=playHistory_[historyIndex_];
    }else{
      chooseNext();
      if(currentIndex_<0)return;
      recordHistory(currentIndex_);
    }
    audio_->play(state_.playlist[currentIndex_]);
    updateNowPlaying();
    refreshQueueWindow();
  }
  // Walks back through the actual play history (the same list the queue
  // window's HISTORY section shows) by moving historyIndex_ rather than
  // recomputing an index — in shuffle mode currentIndex_-1 has no
  // relation to what really played before this track, which is why
  // "Previous" used to land on an effectively random file.
  void previous(){
    if(state_.playlist.empty()||historyIndex_<=0)return;
    --historyIndex_;
    currentIndex_=playHistory_[historyIndex_];
    if(state_.shuffleEnabled){
      auto it=std::find(shuffleBag_.begin(),shuffleBag_.end(),currentIndex_);
      if(it!=shuffleBag_.end())shuffleBag_.erase(it);
    }
    audio_->play(state_.playlist[currentIndex_]);
    updateNowPlaying();
    refreshQueueWindow();
  }
  void cycleRepeatMode(){
    state_.repeatMode = state_.repeatMode==RepeatMode::Off ? RepeatMode::All
                       : state_.repeatMode==RepeatMode::All ? RepeatMode::One : RepeatMode::Off;
    updateRepeatButton(); saveState();
  }
  void updateRepeatButton(){
    if(!repeatButton_)return;
    auto style = repeatButton_->get_style_context();
    if(state_.repeatMode!=RepeatMode::Off) style->add_class("active-toggle");
    else style->remove_class("active-toggle");
    repeatButton_->set_image_from_icon_name(
        state_.repeatMode==RepeatMode::One ? "media-playlist-repeat-song-symbolic" : "media-playlist-repeat-symbolic",
        Gtk::ICON_SIZE_LARGE_TOOLBAR);
    repeatButton_->set_tooltip_text(
        state_.repeatMode==RepeatMode::Off ? "Repeat: off" :
        state_.repeatMode==RepeatMode::One ? "Repeat: one track" : "Repeat: all");
  }
  // Called only when a track finishes playing on its own (GStreamer EOS) —
  // manual next()/previous() clicks and MPRIS "next" always advance/wrap
  // regardless of repeat mode; only the natural end-of-track path should
  // honor "repeat one" (replay) or "repeat off" (stop instead of wrapping).
  void handleTrackFinished(){
    if(state_.repeatMode==RepeatMode::One){
      if(currentIndex_>=0&&currentIndex_<(int)state_.playlist.size()){
        audio_->play(state_.playlist[currentIndex_]); updateNowPlaying();
      }
      return;
    }
    if(state_.repeatMode==RepeatMode::Off){
      const bool atEnd = state_.shuffleEnabled ? shuffleBag_.empty()
                                                : currentIndex_>=(int)state_.playlist.size()-1;
      if(atEnd){ stop(); return; }
    }
    next();
  }
  void confirmRemoveCurrentTrack(){
    if(currentIndex_<0||currentIndex_>=(int)state_.playlist.size())return;
    Gtk::MessageDialog dialog(*this,"Remove the current track from this playlist?",false,
        Gtk::MESSAGE_QUESTION,Gtk::BUTTONS_YES_NO,true);
    dialog.set_title("Remove track");
    if(dialog.run()==Gtk::RESPONSE_YES)removeCurrentTrack();
  }
  // Called right after tracks are erased from state_.playlist.
  // `removedAscending` holds their ORIGINAL indices, sorted ascending.
  // Every other stored index (the shuffle bag, the play history log)
  // needs the same treatment state_.playlist just got: drop anything
  // that pointed at a removed track, and shift everything above it down
  // to match — that way a deletion only removes the deleted track from
  // the What's Next order, instead of resetting the whole thing.
  void remapStoredIndicesAfterRemoval(const std::vector<int>& removedAscending){
    auto remap=[&](int index)->int{
      if(index<0)return -1;
      auto it=std::lower_bound(removedAscending.begin(),removedAscending.end(),index);
      if(it!=removedAscending.end()&&*it==index)return -1;
      return index-static_cast<int>(it-removedAscending.begin());
    };

    std::vector<int> newBag;
    newBag.reserve(shuffleBag_.size());
    for(int idx:shuffleBag_){
      const int mapped=remap(idx);
      if(mapped>=0)newBag.push_back(mapped);
    }
    shuffleBag_=std::move(newBag);

    // If the cursor's own entry (the currently-playing track) is one of
    // the ones being removed, only the history *before* it can still be
    // considered valid — any recorded "forward" entries past it get
    // dropped along with it, same as a fresh manual pick would do. Stop
    // remapping at that point rather than continuing past it, otherwise
    // the loop below has nothing left to point the cursor at and falls
    // back to -1, which then makes the next recordHistory() call think
    // there's no history at all and erase the part we just preserved.
    const bool currentEntryRemoved=historyIndex_>=0&&historyIndex_<(int)playHistory_.size()&&
        remap(playHistory_[historyIndex_])<0;
    const int scanLimit=currentEntryRemoved?historyIndex_:(int)playHistory_.size();

    std::vector<int> newHistory;
    newHistory.reserve(playHistory_.size());
    int newHistoryIndex=-1;
    for(int i=0;i<scanLimit;++i){
      const int mapped=remap(playHistory_[i]);
      if(mapped<0)continue;
      newHistory.push_back(mapped);
      if(i==historyIndex_)newHistoryIndex=(int)newHistory.size()-1;
    }
    if(currentEntryRemoved)newHistoryIndex=(int)newHistory.size()-1;
    playHistory_=std::move(newHistory);
    historyIndex_=newHistoryIndex;
  }
  void removeCurrentTrack(){
    if(currentIndex_<0||currentIndex_>=(int)state_.playlist.size())return;
    const int removedIndex=currentIndex_;
    state_.playlist.erase(state_.playlist.begin()+removedIndex);
    remapStoredIndicesAfterRemoval({removedIndex});
    if(state_.playlist.empty()){
      currentIndex_=-1;
      stop();
    }else{
      // With shuffle on, the next track after a deletion should be a new
      // random pick, not just whatever slid into the deleted slot — that
      // was always "the next sequential track" regardless of the shuffle
      // setting, which is only correct when shuffle is off.
      if(state_.shuffleEnabled) chooseNext();
      else currentIndex_=std::min(removedIndex,(int)state_.playlist.size()-1);
      recordHistory(currentIndex_);
      if(audio_->playing())audio_->play(state_.playlist[currentIndex_]);
    }
    updateNowPlaying();
    refreshPlaylist();
    refreshQueueWindow();
    saveState();
  }
  void updateLyricsHeader(const TrackEntry* track){
    if(!lyricsTitleLabel_||!lyricsSubtitleLabel_)return;
    if(!track){
      lyricsTitleLabel_->set_markup("<span size='x-large' weight='bold'>Nothing playing</span>");
      lyricsSubtitleLabel_->set_text("");
      lyricsSubtitleLabel_->set_visible(false);
      return;
    }
    lyricsTitleLabel_->set_markup(
        "<span size='x-large' weight='bold'>"+
        Glib::Markup::escape_text(track->displayTitle())+"</span>");
    const auto subtitle=track->subtitle();
    lyricsSubtitleLabel_->set_text(subtitle);
    lyricsSubtitleLabel_->set_visible(!subtitle.empty());
  }

  void updateNowPlaying(){
    if(currentIndex_<0||currentIndex_>=(int)state_.playlist.size()){
      nowTitle_.set_text("No song selected");
      nowMeta_.set_text("Add files or folders to start a shuffled sleep playlist");
      hideArtwork();
      if(lyricsWindow_&&lyricsWindow_->get_visible())updateLyricsHeader(nullptr);
      return;
    }
    const auto& track=state_.playlist[currentIndex_];
    nowTitle_.set_text(track.displayTitle());
    nowMeta_.set_text(track.subtitle());
    refreshArtwork(track);
    if(lyricsWindow_&&lyricsWindow_->get_visible()){
      updateLyricsHeader(&track);
      if(track.path!=lyricsLoadedForPath_)refreshLyrics(track);
    }
  }

  // Cache is keyed by (artist, album), so a local hit here covers "already
  // fetched for this exact track" and "another track off the same album
  // already fetched it" alike — checked before deciding whether a network
  // fetch is even needed.
  void refreshArtwork(const TrackEntry& track){
    const auto key=albumArtworkCacheKey(track.artist,track.album);
    artworkRequestKey_=key;
    if(auto cached=cachedArtworkPath(track.artist,track.album)){showArtworkFile(*cached);return;}
    hideArtwork();
    if(!track.remote||track.artist.empty()||track.album.empty())return;
    const auto base=state_.serverBaseUrl,token=state_.serverToken;
    std::thread([this,base,token,track,key]{
      std::vector<std::uint8_t> data;
      try{auto result=ServerClient(base,token).artwork(track);if(result.status==200)data=std::move(result.body);}catch(...){}
      if(data.empty())return;
      auto stored=storeArtwork(track.artist,track.album,data);
      if(!stored)return;
      Glib::signal_idle().connect_once([this,key,path=*stored]{
        // Staleness guard — the user may have already skipped to another
        // track by the time this (server-fetched, so not instant) comes back.
        if(windowAlive.load()&&artworkRequestKey_==key)showArtworkFile(path);
      });
    }).detach();
  }
  void showArtworkFile(const std::filesystem::path& path){
    try{
      auto pixbuf=Gdk::Pixbuf::create_from_file(path.string(),artSizePx_,artSizePx_,true);
      nowArt_.set(pixbuf);nowArt_.set_visible(true);currentArtworkPath_=path.string();
    }catch(...){hideArtwork();}
  }

  void captureLyricsWindowState(){
    if(!lyricsWindow_||!lyricsWindow_->get_visible())return;

    state_.lyricsWindow.maximized=lyricsWindow_->is_maximized();

    if(!state_.lyricsWindow.maximized){
      int x=state_.lyricsWindow.x;
      int y=state_.lyricsWindow.y;
      int width=state_.lyricsWindow.width;
      int height=state_.lyricsWindow.height;

      lyricsWindow_->get_position(x,y);
      lyricsWindow_->get_size(width,height);

      state_.lyricsWindow.x=x;
      state_.lyricsWindow.y=y;
      state_.lyricsWindow.width=std::max(320,width);
      state_.lyricsWindow.height=std::max(360,height);
    }

    GdkWindow* gdkWindow=
        gtk_widget_get_window(GTK_WIDGET(lyricsWindow_->gobj()));
    if(!gdkWindow)return;

    GdkDisplay* display=gdk_window_get_display(gdkWindow);
    GdkMonitor* monitor=gdk_display_get_monitor_at_window(display,gdkWindow);
    if(!monitor)return;

    GdkRectangle geometry{};
    gdk_monitor_get_geometry(monitor,&geometry);
    state_.lyricsWindow.monitorX=geometry.x;
    state_.lyricsWindow.monitorY=geometry.y;
    state_.lyricsWindow.monitorWidth=geometry.width;
    state_.lyricsWindow.monitorHeight=geometry.height;
  }

  void toggleLyricsWindow(){
    if(lyricsWindow_&&lyricsWindow_->get_visible()){
      captureLyricsWindowState();
      state_.lyricsWindowOpen=false;
      lyricsWindow_->hide();
      saveState();
      return;
    }
    openLyricsWindow();
  }

  // A separate top-level window (not a stack_ page) per the request that
  // Ubuntu's lyrics view be a detached window rather than a phone-style
  // full-screen replacement. Hidden (not destroyed) on close so its state
  // and scroll position survive being reopened; picks up the app's global
  // dark theme automatically since installCss()'s `window { background:
  // @theme_bg_color; }` rule applies screen-wide, not just to the main window.
  void openLyricsWindow(){
    if(!lyricsWindow_){
      lyricsWindow_=new Gtk::Window(Gtk::WINDOW_TOPLEVEL);
      lyricsWindow_->set_title("Lyrics");
      lyricsWindow_->set_transient_for(*this);
      lyricsWindow_->set_default_size(state_.lyricsWindow.width,state_.lyricsWindow.height);

      // WindowState for Lyrics follows the same persisted placement model as
      // the main FredPlayer window. Older state files did not contain monitor
      // geometry for Lyrics, so migrate those once onto the main window's
      // known monitor instead of restoring the old default (80,80), which can
      // land in a gap in a multi-monitor desktop.
      if(state_.lyricsWindow.monitorWidth<=0||state_.lyricsWindow.monitorHeight<=0){
        state_.lyricsWindow.monitorX=state_.window.monitorX;
        state_.lyricsWindow.monitorY=state_.window.monitorY;
        state_.lyricsWindow.monitorWidth=state_.window.monitorWidth;
        state_.lyricsWindow.monitorHeight=state_.window.monitorHeight;
        state_.lyricsWindow.x=state_.window.x+80;
        state_.lyricsWindow.y=state_.window.y+80;

        if(state_.lyricsWindow.monitorWidth>0&&state_.lyricsWindow.monitorHeight>0){
          const int maxX=state_.lyricsWindow.monitorX+
              std::max(0,state_.lyricsWindow.monitorWidth-state_.lyricsWindow.width);
          const int maxY=state_.lyricsWindow.monitorY+
              std::max(0,state_.lyricsWindow.monitorHeight-state_.lyricsWindow.height);
          state_.lyricsWindow.x=std::max(
              state_.lyricsWindow.monitorX,
              std::min(state_.lyricsWindow.x,maxX));
          state_.lyricsWindow.y=std::max(
              state_.lyricsWindow.monitorY,
              std::min(state_.lyricsWindow.y,maxY));
        }
      }

      lyricsWindow_->move(state_.lyricsWindow.x,state_.lyricsWindow.y);
      if(state_.lyricsWindow.maximized)lyricsWindow_->maximize();

      lyricsWindow_->signal_configure_event().connect([this](GdkEventConfigure* event){
        if(!lyricsPlacementPending_&&!lyricsWindow_->is_maximized()){
          state_.lyricsWindow.width=event->width;
          state_.lyricsWindow.height=event->height;
          state_.lyricsWindow.x=event->x;
          state_.lyricsWindow.y=event->y;

          GdkWindow* gdkWindow=gtk_widget_get_window(GTK_WIDGET(lyricsWindow_->gobj()));
          if(gdkWindow){
            GdkDisplay* display=gdk_window_get_display(gdkWindow);
            GdkMonitor* monitor=gdk_display_get_monitor_at_window(display,gdkWindow);
            if(monitor){
              GdkRectangle geometry{};
              gdk_monitor_get_geometry(monitor,&geometry);
              state_.lyricsWindow.monitorX=geometry.x;
              state_.lyricsWindow.monitorY=geometry.y;
              state_.lyricsWindow.monitorWidth=geometry.width;
              state_.lyricsWindow.monitorHeight=geometry.height;
            }
          }
        }
        return false;
      });
      lyricsWindow_->signal_window_state_event().connect([this](GdkEventWindowState* event){
        state_.lyricsWindow.maximized=(event->new_window_state&GDK_WINDOW_STATE_MAXIMIZED)!=0;
        return false;
      });
      lyricsWindow_->signal_map_event().connect([this](GdkEventAny*){
        if(lyricsPlacementPending_){
          Glib::signal_idle().connect_once([this]{
            if(!windowAlive.load()||!lyricsWindow_||!lyricsWindow_->get_visible())return;
            lyricsWindow_->move(lyricsPlacementRestoreX_,lyricsPlacementRestoreY_);
            lyricsPlacementPending_=false;
          });
        }
        return false;
      },false);
      lyricsWindow_->signal_delete_event().connect([this](GdkEventAny*){
        toggleLyricsWindow();
        return true;
      });

      lyricsRoot_=Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_VERTICAL,6);
      lyricsRoot_->set_margin_left(28);
      lyricsRoot_->set_margin_right(28);
      lyricsRoot_->set_margin_top(20);
      lyricsRoot_->set_margin_bottom(12);
      lyricsWindow_->add(*lyricsRoot_);

      lyricsTitleLabel_=Gtk::make_managed<Gtk::Label>();
      lyricsTitleLabel_->set_xalign(0.0f);
      lyricsTitleLabel_->set_line_wrap(true);
      lyricsRoot_->pack_start(*lyricsTitleLabel_,false,false);

      lyricsSubtitleLabel_=Gtk::make_managed<Gtk::Label>();
      lyricsSubtitleLabel_->set_xalign(0.0f);
      lyricsSubtitleLabel_->get_style_context()->add_class("muted");
      lyricsRoot_->pack_start(*lyricsSubtitleLabel_,false,false);

      lyricsScroll_=Gtk::make_managed<Gtk::ScrolledWindow>();
      lyricsScroll_->set_policy(Gtk::POLICY_NEVER,Gtk::POLICY_AUTOMATIC);
      lyricsRoot_->pack_start(*lyricsScroll_,true,true);

      lyricsPhrasesBox_=Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_VERTICAL,10);
      lyricsPhrasesBox_->set_margin_top(100);
      lyricsPhrasesBox_->set_margin_bottom(190);
      lyricsScroll_->add(*lyricsPhrasesBox_);

      lyricsStatusLabel_=Gtk::make_managed<Gtk::Label>("Nothing playing");
      lyricsStatusLabel_->get_style_context()->add_class("muted");
      lyricsPhrasesBox_->pack_start(*lyricsStatusLabel_,false,false);
    }

    lyricsPlacementRestoreX_=state_.lyricsWindow.x;
    lyricsPlacementRestoreY_=state_.lyricsWindow.y;
    lyricsPlacementPending_=true;
    lyricsWindow_->move(lyricsPlacementRestoreX_,lyricsPlacementRestoreY_);
    lyricsWindow_->show_all();
    if(currentIndex_>=0&&currentIndex_<(int)state_.playlist.size()){
      updateLyricsHeader(&state_.playlist[currentIndex_]);
      refreshLyrics(state_.playlist[currentIndex_]);
    }else{
      updateLyricsHeader(nullptr);
    }
    lyricsWindow_->present();
    state_.lyricsWindowOpen=true;
    saveState();
  }

  void captureQueueWindowState(){
    if(!queueWindow_||!queueWindow_->get_visible())return;
    state_.queueWindow.maximized=queueWindow_->is_maximized();
    if(!state_.queueWindow.maximized){
      int x=state_.queueWindow.x;int y=state_.queueWindow.y;
      int width=state_.queueWindow.width;int height=state_.queueWindow.height;
      queueWindow_->get_position(x,y);
      queueWindow_->get_size(width,height);
      state_.queueWindow.x=x;state_.queueWindow.y=y;
      state_.queueWindow.width=std::max(320,width);
      state_.queueWindow.height=std::max(360,height);
    }
    GdkWindow* gdkWindow=gtk_widget_get_window(GTK_WIDGET(queueWindow_->gobj()));
    if(!gdkWindow)return;
    GdkDisplay* display=gdk_window_get_display(gdkWindow);
    GdkMonitor* monitor=gdk_display_get_monitor_at_window(display,gdkWindow);
    if(!monitor)return;
    GdkRectangle geometry{};
    gdk_monitor_get_geometry(monitor,&geometry);
    state_.queueWindow.monitorX=geometry.x;
    state_.queueWindow.monitorY=geometry.y;
    state_.queueWindow.monitorWidth=geometry.width;
    state_.queueWindow.monitorHeight=geometry.height;
  }

  void toggleQueueWindow(){
    if(queueWindow_&&queueWindow_->get_visible()){
      captureQueueWindowState();
      state_.queueWindowOpen=false;
      queueWindow_->hide();
      saveState();
      return;
    }
    openQueueWindow();
  }

  // A separate top-level window, built the same way as the Lyrics window
  // (see openLyricsWindow()) — same deferred move-after-map placement
  // technique, same monitor tracking, same hide-not-destroy on close — so
  // its position/size are remembered exactly the same way.
  void openQueueWindow(){
    if(!queueWindow_){
      queueWindow_=new Gtk::Window(Gtk::WINDOW_TOPLEVEL);
      queueWindow_->set_title("What's Next");
      queueWindow_->set_transient_for(*this);
      queueWindow_->set_default_size(state_.queueWindow.width,state_.queueWindow.height);

      if(state_.queueWindow.monitorWidth<=0||state_.queueWindow.monitorHeight<=0){
        state_.queueWindow.monitorX=state_.window.monitorX;
        state_.queueWindow.monitorY=state_.window.monitorY;
        state_.queueWindow.monitorWidth=state_.window.monitorWidth;
        state_.queueWindow.monitorHeight=state_.window.monitorHeight;
        state_.queueWindow.x=state_.window.x+120;
        state_.queueWindow.y=state_.window.y+80;
        if(state_.queueWindow.monitorWidth>0&&state_.queueWindow.monitorHeight>0){
          const int maxX=state_.queueWindow.monitorX+
              std::max(0,state_.queueWindow.monitorWidth-state_.queueWindow.width);
          const int maxY=state_.queueWindow.monitorY+
              std::max(0,state_.queueWindow.monitorHeight-state_.queueWindow.height);
          state_.queueWindow.x=std::max(
              state_.queueWindow.monitorX,std::min(state_.queueWindow.x,maxX));
          state_.queueWindow.y=std::max(
              state_.queueWindow.monitorY,std::min(state_.queueWindow.y,maxY));
        }
      }

      queueWindow_->move(state_.queueWindow.x,state_.queueWindow.y);
      if(state_.queueWindow.maximized)queueWindow_->maximize();

      queueWindow_->signal_configure_event().connect([this](GdkEventConfigure* event){
        if(!queuePlacementPending_&&!queueWindow_->is_maximized()){
          state_.queueWindow.width=event->width;
          state_.queueWindow.height=event->height;
          state_.queueWindow.x=event->x;
          state_.queueWindow.y=event->y;
          GdkWindow* gdkWindow=gtk_widget_get_window(GTK_WIDGET(queueWindow_->gobj()));
          if(gdkWindow){
            GdkDisplay* display=gdk_window_get_display(gdkWindow);
            GdkMonitor* monitor=gdk_display_get_monitor_at_window(display,gdkWindow);
            if(monitor){
              GdkRectangle geometry{};
              gdk_monitor_get_geometry(monitor,&geometry);
              state_.queueWindow.monitorX=geometry.x;
              state_.queueWindow.monitorY=geometry.y;
              state_.queueWindow.monitorWidth=geometry.width;
              state_.queueWindow.monitorHeight=geometry.height;
            }
          }
        }
        return false;
      });
      queueWindow_->signal_window_state_event().connect([this](GdkEventWindowState* event){
        state_.queueWindow.maximized=(event->new_window_state&GDK_WINDOW_STATE_MAXIMIZED)!=0;
        return false;
      });
      queueWindow_->signal_map_event().connect([this](GdkEventAny*){
        if(queuePlacementPending_){
          Glib::signal_idle().connect_once([this]{
            if(!windowAlive.load()||!queueWindow_||!queueWindow_->get_visible())return;
            queueWindow_->move(queuePlacementRestoreX_,queuePlacementRestoreY_);
            queuePlacementPending_=false;
          });
        }
        return false;
      },false);
      queueWindow_->signal_delete_event().connect([this](GdkEventAny*){
        toggleQueueWindow();
        return true;
      });

      queueRoot_=Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_VERTICAL,6);
      queueRoot_->set_margin_left(20);queueRoot_->set_margin_right(20);
      queueRoot_->set_margin_top(16);queueRoot_->set_margin_bottom(12);
      queueWindow_->add(*queueRoot_);

      auto* title=Gtk::make_managed<Gtk::Label>("What's Next");
      title->set_xalign(0.0f);
      title->get_style_context()->add_class("now-title");
      queueRoot_->pack_start(*title,false,false);

      queueSearch_=Gtk::make_managed<Gtk::SearchEntry>();
      queueSearch_->set_placeholder_text("Search history and up next");
      queueSearch_->signal_search_changed().connect([this]{ refreshQueueWindow(); });
      queueRoot_->pack_start(*queueSearch_,false,false);

      queueScroll_=Gtk::make_managed<Gtk::ScrolledWindow>();
      queueScroll_->set_policy(Gtk::POLICY_NEVER,Gtk::POLICY_AUTOMATIC);
      queueRoot_->pack_start(*queueScroll_,true,true);

      queueListBox_=Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_VERTICAL,4);
      queueListBox_->set_margin_top(10);
      queueScroll_->add(*queueListBox_);

      queueScroll_->get_vadjustment()->signal_value_changed().connect([this]{ maybeGrowQueueUpcoming(); });
    }

    queuePlacementRestoreX_=state_.queueWindow.x;
    queuePlacementRestoreY_=state_.queueWindow.y;
    queuePlacementPending_=true;
    queueWindow_->move(queuePlacementRestoreX_,queuePlacementRestoreY_);
    queueWindow_->show_all();
    refreshQueueWindow();
    queueWindow_->present();
    state_.queueWindowOpen=true;
    saveState();
  }

  // Rebuilds the queue window's contents: recently-played history, the
  // currently-playing track, and what's coming up next — in shuffle mode
  // the upcoming section reads directly off the same shuffle bag next()
  // consumes from, so what you see here is exactly what will play, not a
  // separate prediction of it.
  // Only materializes this many "Up Next" rows as real GTK widgets at a
  // time — building one per remaining track (thousands, for a large
  // playlist) is what caused the second-long hang. Scrolling near the
  // bottom grows the limit and rebuilds, so the extra work only happens
  // once the user actually asks to see further ahead.
  void refreshQueueWindow(bool resetUpcomingLimit=true){
    if(!queueWindow_||!queueListBox_||!queueWindow_->get_visible())return;
    if(resetUpcomingLimit)queueUpcomingLimit_=50;

    const double savedScrollValue=queueScroll_?queueScroll_->get_vadjustment()->get_value():0.0;
    for(auto* child:queueListBox_->get_children())queueListBox_->remove(*child);
    queueNowPlayingRow_=nullptr;

    if(state_.playlist.empty()){
      auto* empty=Gtk::make_managed<Gtk::Label>("Nothing queued.");
      empty->get_style_context()->add_class("muted");
      empty->set_xalign(0.0f);
      queueListBox_->pack_start(*empty,false,false);
      queueWindow_->show_all();
      return;
    }

    // playHistory_[0..historyIndex_) is everything played before the
    // current track, oldest-first already (it's an append-only log) — cap
    // to the most recent 20 for display.
    std::vector<int> history;
    for(int i=std::max(0,historyIndex_-20);i<historyIndex_;++i)history.push_back(playHistory_[i]);

    // Cheap to compute in full (it's just copying/walking indices) — only
    // turning entries into widgets below is expensive, so that's the only
    // part we cap.
    std::vector<int> upcoming;
    if(state_.shuffleEnabled){
      // If Previous was pressed earlier, playHistory_ already knows
      // exactly what played after this point — show that first (it's
      // what Next will actually replay), then keep going with the rest
      // of the predicted shuffle order. Anything already in that forward
      // slice was removed from the bag when it was first picked, so this
      // can't show the same track twice.
      for(int i=historyIndex_+1;i<(int)playHistory_.size();++i)upcoming.push_back(playHistory_[i]);
      for(auto it=shuffleBag_.rbegin();it!=shuffleBag_.rend();++it)upcoming.push_back(*it);
    }else{
      // Sequential order is fully determined by currentIndex_ alone, so
      // there's no separate "known forward history" case to special-case
      // here the way shuffle mode needs — recomputing it fresh already
      // gives the complete continuation instead of just whatever short
      // stretch happens to be recorded in playHistory_.
      const int n=(int)state_.playlist.size();
      for(int i=1;i<n;++i){
        const int idx=currentIndex_+i;
        if(idx>=n){
          if(state_.repeatMode!=RepeatMode::All)break;
          upcoming.push_back(idx-n);
        }else upcoming.push_back(idx);
      }
    }
    // Filters HISTORY/UP NEXT by title+artist. NOW PLAYING always stays
    // visible regardless of the query — it's a single status row, not
    // part of the searchable list. While searching, the "Up Next" widget
    // cap is bypassed too, since a filtered match set is already small.
    const std::string query=toLowerAscii(queueSearch_?queueSearch_->get_text():"");
    if(!query.empty()){
      auto matchesQuery=[this,&query](int trackIndex){
        if(trackIndex<0||trackIndex>=(int)state_.playlist.size())return false;
        const auto& track=state_.playlist[trackIndex];
        return toLowerAscii(track.displayTitle()+"\n"+track.artist).find(query)!=std::string::npos;
      };
      history.erase(std::remove_if(history.begin(),history.end(),
          [&](int idx){return !matchesQuery(idx);}),history.end());
      upcoming.erase(std::remove_if(upcoming.begin(),upcoming.end(),
          [&](int idx){return !matchesQuery(idx);}),upcoming.end());
    }
    queueUpcomingTotal_=upcoming.size();

    auto addSection=[this](const char* label){
      auto* heading=Gtk::make_managed<Gtk::Label>(label);
      heading->get_style_context()->add_class("muted");
      heading->set_xalign(0.0f);
      heading->set_margin_top(10);
      queueListBox_->pack_start(*heading,false,false);
    };
    auto addRow=[this](int trackIndex,bool current){
      if(trackIndex<0||trackIndex>=(int)state_.playlist.size())return;
      const auto& track=state_.playlist[trackIndex];
      auto* button=Gtk::make_managed<Gtk::Button>();
      button->set_relief(Gtk::RELIEF_NONE);
      auto* label=Gtk::make_managed<Gtk::Label>(track.displayTitle()+
          (track.artist.empty()?"":(" — "+track.artist)));
      label->set_xalign(0.0f);label->set_line_wrap(false);label->set_ellipsize(Pango::ELLIPSIZE_END);
      if(current)label->get_style_context()->add_class("section-title");
      button->add(*label);
      button->set_sensitive(!current);
      if(!current)button->signal_clicked().connect([this,trackIndex]{ jumpToQueueIndex(trackIndex); });
      queueListBox_->pack_start(*button,false,false);
      if(current)queueNowPlayingRow_=button;
    };

    if(!history.empty()){
      addSection("HISTORY");
      for(int idx:history)addRow(idx,false);
    }
    addSection("NOW PLAYING");
    addRow(currentIndex_,true);
    addSection("UP NEXT");
    if(upcoming.empty()){
      auto* none=Gtk::make_managed<Gtk::Label>(query.empty()?"End of playlist.":"No matches.");
      none->get_style_context()->add_class("muted");
      none->set_xalign(0.0f);
      queueListBox_->pack_start(*none,false,false);
    }else{
      const std::size_t shown=query.empty()?std::min(queueUpcomingLimit_,upcoming.size()):upcoming.size();
      for(std::size_t i=0;i<shown;++i)addRow(upcoming[i],false);
    }
    queueWindow_->show_all();

    if(resetUpcomingLimit){
      // A real track/queue change — keep NOW PLAYING vertically centered
      // so it can't scroll out of view as HISTORY grows above it or UP
      // NEXT shrinks below it.
      scrollToQueueNowPlayingRow();
    }else if(queueScroll_){
      // Just materialized more UP NEXT rows because the user scrolled
      // near the bottom — pin the view exactly where it was instead of
      // recentering, or that scroll gesture would immediately get undone.
      auto adjustment=queueScroll_->get_vadjustment();
      Glib::signal_idle().connect_once([adjustment,savedScrollValue]{
        adjustment->set_value(std::min(savedScrollValue,adjustment->get_upper()));
      });
    }
  }

  // Smoothly (eased, animated) scrolls the queue window so the NOW
  // PLAYING row sits centered in the visible area — same odometer-style
  // animated-scroll approach as the lyrics window's active-line centering
  // (scrollToActiveLyricsLabel()), just anchored at the middle instead of
  // near the top.
  void scrollToQueueNowPlayingRow(){
    if(!queueScroll_||!queueNowPlayingRow_)return;
    auto* row=queueNowPlayingRow_;

    Glib::signal_idle().connect_once([this,row]{
      if(!windowAlive.load()||!queueScroll_||row!=queueNowPlayingRow_)return;
      Gtk::Allocation allocation=row->get_allocation();
      auto adjustment=queueScroll_->get_vadjustment();
      const double maximum=std::max(
          0.0,adjustment->get_upper()-adjustment->get_page_size());
      const double target=std::clamp(
          allocation.get_y()+allocation.get_height()*0.5-adjustment->get_page_size()*0.5,
          0.0,
          maximum);
      const double start=adjustment->get_value();

      if(queueScrollAnimationConnection_.connected())
        queueScrollAnimationConnection_.disconnect();

      constexpr int frames=14;
      queueScrollAnimationConnection_=Glib::signal_timeout().connect(
          [adjustment,start,target,frame=0]() mutable {
            ++frame;
            const double t=std::min(1.0,frame/static_cast<double>(frames));
            const double eased=1.0-std::pow(1.0-t,3.0);
            adjustment->set_value(start+(target-start)*eased);
            return frame<frames;
          },25);
    });
  }

  // Called whenever the queue window is scrolled; grows how many "Up
  // Next" rows are materialized once the user scrolls near the bottom,
  // instead of ever building the full remaining playlist up front.
  void maybeGrowQueueUpcoming(){
    if(!queueScroll_||queueUpcomingLimit_>=queueUpcomingTotal_)return;
    auto adjustment=queueScroll_->get_vadjustment();
    const double remaining=adjustment->get_upper()-(adjustment->get_value()+adjustment->get_page_size());
    if(remaining>48.0)return;
    queueUpcomingLimit_+=50;
    refreshQueueWindow(false);
  }

  // Jumping to any track in the queue (history or upcoming) plays it
  // immediately. If shuffle is on, only the jumped-to track is pulled out
  // of the remaining bag — anything skipped over stays in it and still
  // gets its turn later this pass, rather than being silently dropped.
  void jumpToQueueIndex(int trackIndex){
    if(trackIndex<0||trackIndex>=(int)state_.playlist.size())return;
    currentIndex_=trackIndex;
    if(state_.shuffleEnabled){
      auto it=std::find(shuffleBag_.begin(),shuffleBag_.end(),trackIndex);
      if(it!=shuffleBag_.end())shuffleBag_.erase(it);
    }
    recordHistory(currentIndex_);
    audio_->play(state_.playlist[currentIndex_]);
    updateNowPlaying();
    refreshQueueWindow();
  }

  // Appends a newly-started track to the play-history log, first
  // discarding any stale "forward" entries beyond the current position —
  // the same browser-back/forward-style branching used by manual jumps,
  // so picking an out-of-order track (or advancing past a Previous) keeps
  // the log consistent with what actually played instead of leaving a
  // dangling, no-longer-true future behind.
  void recordHistory(int trackIndex){
    if(trackIndex<0||trackIndex>=(int)state_.playlist.size())return;
    if(historyIndex_<(int)playHistory_.size()-1)
      playHistory_.erase(playHistory_.begin()+historyIndex_+1,playHistory_.end());
    playHistory_.push_back(trackIndex);
    historyIndex_=(int)playHistory_.size()-1;
    if(playHistory_.size()>200){
      playHistory_.erase(playHistory_.begin());
      --historyIndex_;
    }
  }

  void refreshLyrics(const TrackEntry& track){
    if(!lyricsWindow_)return;
    updateLyricsHeader(&track);
    const auto path=track.path;
    lyricsLoadedForPath_=path;
    lyricsPhrases_.clear();
    lyricsPhraseLabels_.clear();
    lyricsActiveIndex_=-1;
    for(auto* child:lyricsPhrasesBox_->get_children())
      if(child!=lyricsStatusLabel_)lyricsPhrasesBox_->remove(*child);
    lyricsStatusLabel_->set_text(track.remote?"Loading lyrics…":"No lyrics available for this track");
    lyricsStatusLabel_->set_visible(true);
    if(!track.remote)return;
    const auto base=state_.serverBaseUrl,token=state_.serverToken;
    std::thread([this,base,token,track,path]{
      auto result=ServerClient(base,token).lyrics(track);
      Glib::signal_idle().connect_once([this,path,result]{
        if(!windowAlive.load()||!lyricsWindow_||path!=lyricsLoadedForPath_)return;
        if(!result||result->empty()){
          lyricsStatusLabel_->set_text("No lyrics available for this track");
          lyricsStatusLabel_->set_visible(true);
          return;
        }
        lyricsStatusLabel_->set_visible(false);
        lyricsPhrases_=*result;
        lyricsPhraseLabels_.clear();
        for(const auto& phrase:lyricsPhrases_){
          auto* label=Gtk::make_managed<Gtk::Label>(phrase.text);
          label->set_line_wrap(true);
          label->set_max_width_chars(42);
          label->set_justify(Gtk::JUSTIFY_CENTER);
          label->set_xalign(0.5f);
          label->set_halign(Gtk::ALIGN_CENTER);
          lyricsPhrasesBox_->pack_start(*label,false,false);
          lyricsPhraseLabels_.push_back(label);
        }
        lyricsPhrasesBox_->show_all();
        updateLyricsHighlight(audio_->positionMs()/1000.0);
      });
    }).detach();
  }

  std::string buildLyricsWordMarkup(
      const LyricsPhrase& phrase,double positionSeconds,int size=23000){
    std::ostringstream markup;
    markup<<"<span size='"<<size<<"' weight='bold'>";
    for(size_t i=0;i<phrase.words.size();++i){
      const auto& word=phrase.words[i];
      if(i>0)markup<<" ";
      const double sungBoundary=(i+1<phrase.words.size())
          ?phrase.words[i+1].timeSeconds:phrase.endSeconds;
      const bool sung=sungBoundary<=positionSeconds;
      markup<<"<span foreground='"<<(sung?"#f5f3ed":"#787878")<<"'>"
            <<Glib::Markup::escape_text(word.text)<<"</span>";
    }
    markup<<"</span>";
    return markup.str();
  }

  void renderLyricsLine(
      int index,int activeIndex,double positionSeconds,int size,
      double opacity,int marginTop,int marginBottom){
    if(index<0||index>=static_cast<int>(lyricsPhraseLabels_.size()))return;
    auto* label=lyricsPhraseLabels_[index];
    label->set_opacity(opacity);
    label->set_margin_top(marginTop);
    label->set_margin_bottom(marginBottom);
    if(index==activeIndex){
      label->set_markup(buildLyricsWordMarkup(
          lyricsPhrases_[index],positionSeconds,size));
    }else{
      const bool past=index<activeIndex;
      std::ostringstream markup;
      markup<<"<span size='"<<size<<"' foreground='"
            <<(past?"#6e6e6e":"#969696")<<"'>"
            <<Glib::Markup::escape_text(lyricsPhrases_[index].text)
            <<"</span>";
      label->set_markup(markup.str());
    }
  }

  void animateLyricsLineTransition(
      int oldIndex,int newIndex,double positionSeconds){
    if(lyricsLineAnimationConnection_.connected())
      lyricsLineAnimationConnection_.disconnect();

    constexpr int frames=14;
    lyricsLineAnimationConnection_=Glib::signal_timeout().connect(
        [this,oldIndex,newIndex,positionSeconds,frame=0]() mutable {
          ++frame;
          const double t=std::min(1.0,frame/static_cast<double>(frames));
          const double eased=1.0-std::pow(1.0-t,3.0);

          if(oldIndex>=0&&oldIndex<static_cast<int>(lyricsPhraseLabels_.size())){
            const int size=static_cast<int>(23000+(15500-23000)*eased);
            const double finalOpacity=oldIndex<newIndex?0.42:0.68;
            const double opacity=1.0+(finalOpacity-1.0)*eased;
            const int margin=static_cast<int>(18+(6-18)*eased);
            renderLyricsLine(
                oldIndex,newIndex,positionSeconds,size,opacity,margin,margin);
          }

          if(newIndex>=0&&newIndex<static_cast<int>(lyricsPhraseLabels_.size())){
            const int size=static_cast<int>(15500+(23000-15500)*eased);
            const double opacity=0.25+(1.0-0.25)*eased;
            const int margin=static_cast<int>(6+(18-6)*eased);
            renderLyricsLine(
                newIndex,newIndex,positionSeconds,size,opacity,margin,margin);
          }

          return frame<frames;
        },25);
  }

  void updateLyricsHighlight(double positionSeconds){
    if(lyricsPhrases_.empty()||lyricsPhraseLabels_.size()!=lyricsPhrases_.size())return;

    int activeIndex=-1;
    for(size_t i=0;i<lyricsPhrases_.size();++i){
      if(lyricsPhrases_[i].startSeconds<=positionSeconds)
        activeIndex=static_cast<int>(i);
      else
        break;
    }

    for(size_t i=0;i<lyricsPhraseLabels_.size();++i){
      const bool active=static_cast<int>(i)==activeIndex;
      const bool past=static_cast<int>(i)<activeIndex;
      renderLyricsLine(
          static_cast<int>(i),
          activeIndex,
          positionSeconds,
          active?23000:15500,
          active?1.0:(past?0.42:0.68),
          active?18:6,
          active?18:6);
    }

    if(activeIndex!=lyricsActiveIndex_){
      const int oldIndex=lyricsActiveIndex_;
      lyricsActiveIndex_=activeIndex;
      animateLyricsLineTransition(oldIndex,activeIndex,positionSeconds);
      scrollToActiveLyricsLabel();
    }
  }

  void scrollToActiveLyricsLabel(){
    if(!lyricsScroll_||lyricsActiveIndex_<0
        ||lyricsActiveIndex_>=static_cast<int>(lyricsPhraseLabels_.size()))return;
    auto* label=lyricsPhraseLabels_[lyricsActiveIndex_];

    Glib::signal_idle().connect_once([this,label]{
      if(!windowAlive.load()||!lyricsScroll_)return;
      Gtk::Allocation allocation=label->get_allocation();
      auto adjustment=lyricsScroll_->get_vadjustment();
      const double maximum=std::max(
          0.0,adjustment->get_upper()-adjustment->get_page_size());
      const double target=std::clamp(
          allocation.get_y()-adjustment->get_page_size()*0.35,
          0.0,
          maximum);
      const double start=adjustment->get_value();

      if(lyricsScrollAnimationConnection_.connected())
        lyricsScrollAnimationConnection_.disconnect();

      constexpr int frames=14;
      lyricsScrollAnimationConnection_=Glib::signal_timeout().connect(
          [adjustment,start,target,frame=0]() mutable {
            ++frame;
            const double t=std::min(1.0,frame/static_cast<double>(frames));
            const double eased=1.0-std::pow(1.0-t,3.0);
            adjustment->set_value(start+(target-start)*eased);
            return frame<frames;
          },25);
    });
  }
  void hideArtwork(){showPlaceholderArtwork();}
  void showPlaceholderArtwork(){
    currentArtworkPath_.clear();
    static constexpr const char* kPlaceholder = "linux/assets/no_album_art.png";
    if (!std::filesystem::exists(kPlaceholder)) { nowArt_.set_visible(false); return; }
    try {
      auto pixbuf = Gdk::Pixbuf::create_from_file(kPlaceholder, artSizePx_, artSizePx_, true);
      nowArt_.set(pixbuf); nowArt_.set_visible(true);
    } catch (...) { nowArt_.set_visible(false); }
  }
  void updateTransport(){if(playButton_){playButton_->set_image_from_icon_name(audio_->playing()&&!audio_->paused()?"media-playback-pause-symbolic":"media-playback-start-symbolic",Gtk::ICON_SIZE_LARGE_TOOLBAR);playButton_->set_always_show_image(true);status_.set_text(audio_->playing()?(audio_->paused()?"Paused":"Playing"):"Stopped");}}
  bool progressTick(){if(!windowAlive.load())return false;const auto position=audio_->positionMs(),duration=audio_->durationMs();if(!seeking_)seek_.set_value(position);seek_.set_range(0,std::max<std::int64_t>(1,duration));seek_.set_sensitive(duration>0);elapsed_.set_text(formatTime(position));duration_.set_text(duration?formatTime(duration):"--:--");
    if(mpris_){const TrackEntry* track=currentIndex_>=0&&currentIndex_<(int)state_.playlist.size()?&state_.playlist[currentIndex_]:nullptr;mpris_->update(track,audio_->playing(),audio_->paused(),position,duration,currentArtworkPath_,state_.shuffleEnabled,state_.repeatMode);}
    return true;}
  // Deliberately NOT also subtracting currentVisualDelay() here like the
  // visualizer does — that's a GStreamer-probed pipeline latency that can
  // run well over 100ms on this desktop's audio stack, and stacked with
  // the word-boundary correction it over-corrected, leaving the highlight
  // visibly behind the vocal instead of on it.
  bool lyricsTick(){if(!windowAlive.load())return false;
    if(lyricsWindow_&&lyricsWindow_->get_visible())
      updateLyricsHighlight(audio_->positionMs()/1000.0);
    return true;}
  bool visualTimerTick(){if(!windowAlive.load())return false;if(!audio_->playing())return true;if(audio_->paused()){visualPaused_=true;return true;}if(visualPaused_){visualPaused_=false;nextVisualFrameUs_=0;lastVisualStatusUs_=0;lastProducedFrames_=audio_->visualizer().producedFrameCount();measuredAnalysisFps_=-1;}const auto nowUs=g_get_monotonic_time();const auto intervalUs=1'000'000.0/clamp(visualRequestedFps_,5.0,144.0);if(nextVisualFrameUs_<=0)nextVisualFrameUs_=nowUs;if(nowUs+1'000.0<nextVisualFrameUs_)return true;do{nextVisualFrameUs_+=intervalUs;}while(nextVisualFrameUs_<=nowUs);visualTick();return true;}
  void visualTick(){const auto nowUs=g_get_monotonic_time();if(auto frame=audio_->currentVisualization()){visualizer_.setFrame(frame);if(nowUs-lastVisualStatusUs_>=500'000){auto& engine=audio_->visualizer();const auto produced=engine.producedFrameCount();if(lastVisualStatusUs_>0&&produced>=lastProducedFrames_)measuredAnalysisFps_=(produced-lastProducedFrames_)*1'000'000.0/(nowUs-lastVisualStatusUs_);lastProducedFrames_=produced;visualStatus_.set_text(visualizationSummary(state_.visualization,frame.get()));visualPerformanceStatus_.set_text(visualizationPerformance(visualizer_.measuredFps(),visualizer_.measuredSourceFps(),measuredAnalysisFps_,engine));lastVisualStatusUs_=nowUs;}}}

  void refreshLatencyUi(){const auto& output=cachedOutput_;outputLabel_.set_text("Output: "+output.label);const auto found=state_.speakerLatencies.find(output.key);latencyLabel_.set_text(found==state_.speakerLatencies.end()?"System-reported visual delay: "+std::to_string(systemLatencyMs_)+" ms":"Saved speaker calibration: "+std::to_string(found->second.delayMs)+" ms");
    std::string saved="Saved speakers:";if(state_.speakerLatencies.empty())saved+=" none";for(const auto&[key,value]:state_.speakerLatencies)saved+="\n• "+value.label+": "+std::to_string(value.delayMs)+" ms";calibrationsLabel_.set_text(saved);
    microphone_.remove_all();for(const auto& mic:cachedMicrophones_){microphone_.append(mic.key,mic.label);if(mic.key==state_.selectedMicrophone)microphone_.set_active_id(mic.key);}if(microphone_.get_active_row_number()<0)microphone_.set_active(0);calibrate_.set_visible(output.bluetooth);microphone_.set_visible(output.bluetooth);}
  bool routeTick(){if(!windowAlive.load())return false;if(routeCheckRunning_.exchange(true))return true;
    const auto previous=lastOutputKey_;const auto previousDelay=systemLatencyMs_;std::thread([this,previous,previousDelay]{auto output=currentOutput();auto inputs=microphones();int delay=previousDelay;if(output.key!=previous){try{delay=probeSystemLatency(output);}catch(...){delay=0;}}
      Glib::signal_idle().connect_once([this,output=std::move(output),inputs=std::move(inputs),delay]{if(windowAlive.load()){const bool changed=output.key!=lastOutputKey_||delay!=systemLatencyMs_;cachedOutput_=output;cachedMicrophones_=inputs;lastOutputKey_=output.key;systemLatencyMs_=delay;if(changed)applySettings();refreshLatencyUi();routeCheckRunning_.store(false);}});}).detach();return true;}
  bool cacheTick(){if(!windowAlive.load())return false;if(cacheCheckRunning_.exchange(true))return true;std::thread([this]{const auto summary=cacheSummary();Glib::signal_idle().connect_once([this,summary]{if(windowAlive.load()){cacheLabel_.set_text("Loudness profiles: "+std::to_string(summary.profileCount)+" / 5000\nSpectrum files: "+std::to_string(summary.spectrumCount)+" / 5000\nWaveform files: "+std::to_string(summary.waveformCount)+" / 5000\nDisk used: "+formatBytes(summary.bytes));cacheCheckRunning_.store(false);}});}).detach();return true;}
  void startCalibration(){const auto id=microphone_.get_active_id();if(id.empty()||calibrating_.exchange(true))return;state_.selectedMicrophone=id;calibrate_.set_sensitive(false);status_.set_text("Calibrating speaker delay…");
    std::thread([this,id]{std::optional<CalibrationResult> result;std::string error;try{result=calibrateWithMicrophone(id);}catch(const std::exception&e){error=e.what();}Glib::signal_idle().connect_once([this,result,error]{if(windowAlive.load()){if(result){state_.speakerLatencies[result->output.key]={result->output.key,result->output.label,result->delayMs};applySettings();}else showError(*this,error);refreshLatencyUi();status_.set_text("");calibrate_.set_sensitive(true);calibrating_.store(false);}});}).detach();}

  // Hide the lyrics window first, before saving — it's a separate top-level
  // window the GtkApplication never tracked (only the main window was ever
  // passed to app->run()), so closing the main window can tear the whole
  // process down immediately without the lyrics window ever getting a
  // clean final configure-event. Hiding it here freezes state_.lyricsWindow
  // at its last good value instead of racing a shutdown-triggered reflow.
  bool onDelete(GdkEventAny*){
    const bool lyricsWasOpen=lyricsWindow_&&lyricsWindow_->get_visible();
    if(lyricsWasOpen)captureLyricsWindowState();
    state_.lyricsWindowOpen=lyricsWasOpen;
    if(lyricsWindow_)lyricsWindow_->hide();
    const bool queueWasOpen=queueWindow_&&queueWindow_->get_visible();
    if(queueWasOpen)captureQueueWindowState();
    state_.queueWindowOpen=queueWasOpen;
    if(queueWindow_)queueWindow_->hide();
    saveState();
    return false;
  }
  bool onConfigure(GdkEventConfigure* event){if(!is_maximized()){state_.window.width=event->width;state_.window.height=event->height;state_.window.x=event->x;state_.window.y=event->y;}return false;}
  bool onWindowState(GdkEventWindowState* event){state_.window.maximized=(event->new_window_state&GDK_WINDOW_STATE_MAXIMIZED)!=0;return false;}

  StateStore store_; AppState state_; std::unique_ptr<AudioEngine> audio_; std::unique_ptr<MprisServer> mpris_;
  Gtk::Stack stack_; Gtk::Box playerPage_{Gtk::ORIENTATION_VERTICAL},settingsPage_{Gtk::ORIENTATION_VERTICAL};Gtk::ScrolledWindow settingsScroll_;
  Gtk::Label nowTitle_,nowMeta_,elapsed_{"0:00"},duration_{"--:--"},status_,playlistStatus_,visualStatus_,visualPerformanceStatus_;Gtk::Scale seek_{Gtk::ORIENTATION_HORIZONTAL};VisualizerWidget visualizer_;
  Gtk::Image nowArt_;std::string currentArtworkPath_,artworkRequestKey_;int artSizePx_{72};
  Gtk::Button *previousButton_{},*playButton_{},*stopButton_{},*nextButton_{},*removeButton_{},*repeatButton_{},*lyricsButton_{},*queueButton_{};
  Gtk::ToggleButton *shuffleButton_{};
  Gtk::Button settingsButton_,backButton_;
  Gtk::Window* lyricsWindow_{};
  Gtk::Box* lyricsRoot_{};
  Gtk::Box* lyricsPhrasesBox_{};
  Gtk::ScrolledWindow* lyricsScroll_{};
  Gtk::Label* lyricsTitleLabel_{};
  Gtk::Label* lyricsSubtitleLabel_{};
  Gtk::Label* lyricsStatusLabel_{};
  std::vector<LyricsPhrase> lyricsPhrases_;
  std::vector<Gtk::Label*> lyricsPhraseLabels_;
  int lyricsActiveIndex_{-1};
  std::string lyricsLoadedForPath_;
  Gtk::Window* queueWindow_{};
  Gtk::Box* queueRoot_{};
  Gtk::Box* queueListBox_{};
  Gtk::ScrolledWindow* queueScroll_{};
  Gtk::SearchEntry* queueSearch_{};
  std::vector<int> playHistory_;
  int historyIndex_{-1};
  std::size_t queueUpcomingLimit_{50};
  std::size_t queueUpcomingTotal_{0};
  Gtk::Widget* queueNowPlayingRow_{};
  sigc::connection queueScrollAnimationConnection_;
  bool queueRestorePending_{false},queuePlacementPending_{false};
  int queuePlacementRestoreX_{80},queuePlacementRestoreY_{80};
  SettingsComboBoxText playlistCombo_;TrackColumns trackColumns_;Glib::RefPtr<Gtk::ListStore> trackStore_;Gtk::TreeView trackView_;Gtk::ScrolledWindow* trackScroll_{};Gtk::SearchEntry playlistSearch_;
  Gtk::Scale *outputLevel_{},*levelingStrength_{},*analysisSeconds_{},*levelAttack_{},*levelRelease_{},*gainDown_{},*gainUp_{},*compressorThreshold_{},*outputCeiling_{},*fps_{},*waveformMs_{},*bars_{},*smoothing_{};SettingsComboBoxText fftSize_,scale_;Gtk::Entry serverUrl_,serverToken_;
  Gtk::Frame* latencyFrame_{};Gtk::Box latencyBox_{Gtk::ORIENTATION_VERTICAL};Gtk::Label outputLabel_,latencyLabel_,calibrationsLabel_,cacheLabel_;SettingsComboBoxText microphone_;Gtk::Button calibrate_;
  int currentIndex_{-1};std::vector<int> shuffleBag_;std::mt19937 random_;bool seeking_{false},changingPlaylist_{false};gint64 lastVisualStatusUs_{0};std::uint64_t lastProducedFrames_{0};double measuredAnalysisFps_{-1};
  sigc::connection visualTimerConnection_,progressConnection_,routeConnection_,cacheConnection_,lyricsTickConnection_;
  sigc::connection lyricsLineAnimationConnection_,lyricsScrollAnimationConnection_;
  double visualRequestedFps_{30},nextVisualFrameUs_{0};bool visualPaused_{false},lyricsRestorePending_{false},lyricsPlacementPending_{false};int lyricsPlacementRestoreX_{80},lyricsPlacementRestoreY_{80};std::atomic<bool> routeCheckRunning_{false},cacheCheckRunning_{false},calibrating_{false};std::string lastOutputKey_;int systemLatencyMs_{0};AudioOutput cachedOutput_;std::vector<Microphone> cachedMicrophones_;
};

void installCss() {
  // Matches the Android app's own dark/teal theme (see MainActivity's
  // settingsCard()/settingsButton() palette) instead of following whatever
  // GTK theme the system happens to be running, so the two apps read as
  // the same product. Deliberately CSS-only — no widget packing/position
  // changed anywhere the class names below are applied.
  auto provider=Gtk::CssProvider::create();
  provider->load_from_data(R"CSS(
    @define-color fred_bg #111315;
    @define-color fred_card #181b1e;
    @define-color fred_card_border #292e33;
    @define-color fred_secondary #23292e;
    @define-color fred_secondary_border #525b63;
    @define-color fred_primary #2d705b;
    @define-color fred_primary_hover #357f68;
    @define-color fred_primary_active #245c4a;
    @define-color fred_primary_border #76debe;
    @define-color fred_destructive #6b2d2a;
    @define-color fred_destructive_border #d67c6e;
    @define-color fred_text #f5f3ed;
    @define-color fred_muted #b7b6ad;

    window { background: @fred_bg; color: @fred_text; }
    label { color: @fred_text; }

    button {
      min-height: 34px;
      background: @fred_secondary;
      background-image: none;
      border: 1px solid @fred_secondary_border;
      border-radius: 14px;
      color: @fred_text;
      box-shadow: none;
    }
    button:hover { background: shade(@fred_secondary, 1.18); }
    button:active { background: shade(@fred_secondary, 0.85); }
    button.icon-round { border-radius: 9999px; padding: 4px; }
    button.destructive-action {
      background-color: @fred_destructive;
      border-color: @fred_destructive_border;
      color: #ffffff;
    }
    button.destructive-action:hover { background: shade(@fred_destructive, 1.18); }

    frame { border-radius: 14px; }
    frame.panel-frame,
    frame.panel-frame > border {
      background: @fred_card;
      border: 1px solid @fred_card_border;
      border-radius: 14px;
    }
    frame.panel-frame > border > label {
      font-weight: bold;
      margin-left: 10px;
      margin-right: 10px;
      color: @fred_text;
    }
    .app-title {
      opacity: 0.72;
      font-size: 13px;
      font-weight: bold;
    }
    .now-title {
      font-size: 22px;
      font-weight: bold;
    }
    .now-meta { opacity: 0.82; font-size: 14px; }
    .section-title { font-size: 17px; font-weight: bold; }
    .muted { color: @fred_muted; opacity: 1; }

    entry {
      background: @fred_card;
      color: @fred_text;
      border: 1px solid @fred_secondary_border;
      border-radius: 10px;
    }
    combobox button.combo {
      background: @fred_secondary;
      border: 1px solid @fred_secondary_border;
      border-radius: 14px;
      color: @fred_text;
    }

    scale { min-width: 80px; }
    scale trough { background: @fred_secondary; border-radius: 8px; }
    scale slider { background: @fred_primary_border; border-radius: 9999px; }
    scale trough highlight,
    progressbar trough progress {
      background-color: @fred_primary;
      background-image: none;
      border-color: @fred_primary_border;
    }
    scale:hover trough highlight,
    progressbar:hover trough progress {
      background-color: @fred_primary_hover;
    }
    scale:active trough highlight {
      background-color: @fred_primary_active;
    }
    scale:focus slider,
    button:focus,
    entry:focus,
    combobox button.combo:focus {
      border-color: @fred_primary;
      box-shadow: inset 0 0 0 1px @fred_primary;
    }
    checkbutton check,
    radiobutton radio {
      background: @fred_secondary;
      border: 1px solid @fred_secondary_border;
    }
    checkbutton check:checked,
    radiobutton radio:checked,
    switch:checked {
      background-color: @fred_primary;
      background-image: none;
      border-color: @fred_primary_border;
      color: #ffffff;
    }
    checkbutton check:checked:hover,
    radiobutton radio:checked:hover,
    switch:checked:hover {
      background-color: @fred_primary_hover;
    }
    switch { background: @fred_secondary; border: 1px solid @fred_secondary_border; }
    treeview.view {
      background: @fred_bg;
      color: @fred_text;
    }
    treeview.view:selected,
    treeview.view:selected:focus,
    entry selection {
      background-color: @fred_primary;
      color: #ffffff;
    }
    treeview header button {
      background: @fred_card;
      color: @fred_muted;
      border-radius: 0;
      border: none;
      border-bottom: 1px solid @fred_card_border;
    }
    button.suggested-action,
    button:checked,
    button.active-toggle {
      background-color: @fred_primary;
      background-image: none;
      border-color: @fred_primary_border;
      color: #ffffff;
    }
    button.suggested-action:hover,
    button:checked:hover,
    button.active-toggle:hover {
      background-color: @fred_primary_hover;
    }
    button.suggested-action:active,
    button:checked:active,
    button.active-toggle:active {
      background-color: @fred_primary_active;
    }
    treeview { min-width: 0; }
  )CSS");
  Gtk::StyleContext::add_provider_for_screen(Gdk::Screen::get_default(),provider,GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
}

}  // namespace

int runApplication(int argc,char** argv){
  auto app=Gtk::Application::create(argc,argv,"com.fredplayer.nativepreview");
  installCss();
  FredPlayerWindow window;
  // A GAction (not a plain in-window GTK accelerator) so it's invokable over
  // D-Bus and works as a real desktop-wide shortcut whether or not this
  // window has focus. Bind a custom keyboard shortcut in the desktop
  // environment's settings to run:
  //   gapplication action com.fredplayer.nativepreview remove-current-track
  app->add_action("remove-current-track",[&window]{ window.removeCurrentTrackFromShortcut(); });
  return app->run(window);
}

}  // namespace fredplayer
