#include "fredplayer/application.hpp"

#include "fredplayer/audio_engine.hpp"
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
      [this] { if (windowAlive.load()) next(); },
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
  }

  ~FredPlayerWindow() override {
    windowAlive.store(false);
    visualTimerConnection_.disconnect();progressConnection_.disconnect();
    routeConnection_.disconnect();cacheConnection_.disconnect();
    saveState();
  }

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
    auto* details = Gtk::make_managed<Gtk::Box>(Gtk::ORIENTATION_VERTICAL, 2); details->set_hexpand(true);
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
    previousButton_ = iconButton("media-skip-backward-symbolic", "Previous");
    playButton_ = iconButton("media-playback-start-symbolic", "Play");
    stopButton_ = iconButton("media-playback-stop-symbolic", "Stop");
    nextButton_ = iconButton("media-skip-forward-symbolic", "Next");
    previousButton_->signal_clicked().connect([this]{ previous(); });
    playButton_->signal_clicked().connect([this]{ togglePlay(); });
    stopButton_->signal_clicked().connect([this]{ stop(); });
    nextButton_->signal_clicked().connect([this]{ next(); });
    row->pack_start(*previousButton_, false, false); row->pack_start(*playButton_, false, false);
    row->pack_start(*stopButton_, false, false); row->pack_start(*nextButton_, false, false);
    settingsButton_.set_label("Settings"); keepNaturalWidth(settingsButton_);
    settingsButton_.signal_clicked().connect([this]{ stack_.set_visible_child("settings"); });
    controls->pack_start(*row, false, false); controls->pack_start(settingsButton_, false, false);
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
    shuffle_.set_active(state_.shuffleEnabled);shuffle_.signal_toggled().connect([this]{state_.shuffleEnabled=shuffle_.get_active();shuffleBag_.clear();saveState();});playlistBox->pack_start(shuffle_,false,false);
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
    share->signal_clicked().connect([this]{ shareCurrentPlaylist(); }); download->signal_clicked().connect([this]{ getSharedPlaylist(); });
    shareRow->pack_start(*share, false, false); shareRow->pack_start(*download, false, false); playlistBox->pack_start(*shareRow, false, false);
    trackStore_ = Gtk::ListStore::create(trackColumns_); trackView_.set_model(trackStore_);
    trackView_.append_column("Title", trackColumns_.title); trackView_.append_column("Artist", trackColumns_.artist);
    trackView_.append_column("Album", trackColumns_.album); trackView_.set_headers_visible(true);
    trackView_.get_selection()->set_mode(Gtk::SELECTION_MULTIPLE);
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
    auto* ask = textButton("Ask Liam"); ask->signal_clicked().connect([this]{ askLiam(); }); server->attach(*ask,1,2,1,1);
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
    for(std::size_t i=0;i<state_.playlist.size();++i){const auto& track=state_.playlist[i];auto row=*(trackStore_->append());
      row[trackColumns_.title]=track.displayTitle();row[trackColumns_.artist]=track.artist;row[trackColumns_.album]=track.album;row[trackColumns_.path]=track.path;row[trackColumns_.index]=i;}
    playlistStatus_.set_text(state_.activePlaylist+" · "+std::to_string(state_.playlist.size())+" songs");
  }

  void hydrateLocalMetadata(){std::set<std::string> paths;for(const auto&[name,entries]:state_.namedPlaylists)for(const auto&track:entries)if(!track.remote&&(track.title.empty()||track.artist.empty()||track.album.empty()))paths.insert(track.path);if(paths.empty())return;
    std::thread([this,paths=std::move(paths)]{std::unordered_map<std::string,TrackEntry> metadata;for(const auto&path:paths)metadata.emplace(path,readLocalMetadata(path));Glib::signal_idle().connect_once([this,metadata=std::move(metadata)]{if(!windowAlive.load())return;for(auto&[name,entries]:state_.namedPlaylists)for(auto&track:entries)if(auto found=metadata.find(track.path);found!=metadata.end()){track.title=found->second.title;track.artist=found->second.artist;track.album=found->second.album;}state_.playlist=state_.namedPlaylists[state_.activePlaylist];refreshPlaylist();updateNowPlaying();});}).detach();}

  void switchPlaylist(){if(changingPlaylist_)return;auto name=playlistCombo_.get_active_text();if(name.empty()||!state_.namedPlaylists.count(name))return;
    stop();state_.activePlaylist=name;state_.playlist=state_.namedPlaylists[name];currentIndex_=-1;refreshPlaylist();saveState();}

  std::string prompt(const std::string& title,const std::string& initial={}){Gtk::Dialog dialog(title,*this,true);auto* entry=Gtk::make_managed<Gtk::Entry>();entry->set_text(initial);entry->set_activates_default(true);
    dialog.get_content_area()->pack_start(*entry);dialog.add_button("Cancel",Gtk::RESPONSE_CANCEL);dialog.add_button("OK",Gtk::RESPONSE_OK);dialog.set_default_response(Gtk::RESPONSE_OK);dialog.show_all();return dialog.run()==Gtk::RESPONSE_OK?entry->get_text():"";}
  void createPlaylist(){auto name=prompt("New playlist name");if(name.empty()||state_.namedPlaylists.count(name))return;state_.namedPlaylists[name]={};state_.activePlaylist=name;state_.playlist={};refreshPlaylist();saveState();}
  void renamePlaylist(){auto name=prompt("Rename playlist",state_.activePlaylist);if(name.empty()||name==state_.activePlaylist||state_.namedPlaylists.count(name))return;
    auto values=state_.playlist;state_.namedPlaylists.erase(state_.activePlaylist);state_.namedPlaylists[name]=values;state_.activePlaylist=name;refreshPlaylist();saveState();}
  void deletePlaylist(){if(state_.namedPlaylists.size()<=1)return;state_.namedPlaylists.erase(state_.activePlaylist);state_.activePlaylist=state_.namedPlaylists.begin()->first;state_.playlist=state_.namedPlaylists.begin()->second;stop();refreshPlaylist();saveState();}

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

    std::sort(indexes.rbegin(), indexes.rend());
    for (const int index : indexes) {
      if (index >= 0 && index < static_cast<int>(state_.playlist.size()))
        state_.playlist.erase(state_.playlist.begin() + index);
    }
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
  void shareCurrentPlaylist() {
    const auto base=state_.serverBaseUrl,token=state_.serverToken,name=state_.activePlaylist;const auto tracks=state_.playlist;status_.set_text("Sharing playlist…");
    std::thread([this,base,token,name,tracks]{std::string error;try{ServerClient(base,token).sharePlaylist(name,tracks);}catch(const std::exception&e){error=e.what();}
      Glib::signal_idle().connect_once([this,name,error]{if(!windowAlive.load())return;if(error.empty())status_.set_text("Shared playlist: "+name);else showError(*this,error);});}).detach();
  }
  void getSharedPlaylist() {
    const auto base=state_.serverBaseUrl,token=state_.serverToken;status_.set_text("Loading shared playlists…");
    std::thread([this,base,token]{std::vector<std::string> names;std::string error;try{names=ServerClient(base,token).sharedPlaylists();}catch(const std::exception&e){error=e.what();}
      Glib::signal_idle().connect_once([this,base,token,names=std::move(names),error]()mutable{if(!windowAlive.load())return;status_.set_text("");if(!error.empty()){showError(*this,error);return;}Gtk::Dialog dialog("Get shared playlist",*this,true);dialog.set_default_size(560,-1);dialog.set_size_request(440,-1);auto* content=dialog.get_content_area();content->set_spacing(10);content->set_margin_left(12);content->set_margin_right(12);content->set_margin_top(12);content->set_margin_bottom(8);Gtk::ComboBoxText combo;combo.set_hexpand(true);for(const auto& name:names)combo.append(name);if(!names.empty())combo.set_active(0);content->pack_start(combo,true,true);dialog.add_button("Cancel",Gtk::RESPONSE_CANCEL);dialog.add_button("Get",Gtk::RESPONSE_OK);dialog.show_all();if(dialog.run()!=Gtk::RESPONSE_OK)return;const std::string name=combo.get_active_text();if(name.empty())return;status_.set_text("Downloading shared playlist…");
        std::thread([this,base,token,name]{std::vector<TrackEntry> tracks;std::string failure;try{ServerClient server(base,token);const auto paths=server.playlistTracks(name);const auto library=server.library();std::unordered_map<std::string,TrackEntry> byUrl;for(const auto&track:library)byUrl.emplace(track.path,track);for(const auto& path:paths){const auto url=server.streamUrl(path);if(auto found=byUrl.find(url);found!=byUrl.end())tracks.push_back(found->second);else{auto slash=path.find_last_of('/');tracks.push_back({url,slash==std::string::npos?"Server":path.substr(0,slash),true,std::filesystem::path(path).stem().string(),"",""});}}}catch(const std::exception&e){failure=e.what();}
          Glib::signal_idle().connect_once([this,name,tracks=std::move(tracks),failure]()mutable{if(!windowAlive.load())return;status_.set_text("");if(!failure.empty()){showError(*this,failure);return;}state_.namedPlaylists[name]=tracks;state_.activePlaylist=name;state_.playlist=tracks;refreshPlaylist();saveState();});}).detach();});}).detach();
  }

  void askLiam(){auto message=prompt("Ask Liam");if(message.empty())return;status_.set_text("Asking Liam…");const auto base=state_.serverBaseUrl,token=state_.serverToken,id=persistentDeviceId();
    std::thread([this,base,token,id,message]{std::string answer,error;try{answer=ServerClient(base,token).askLiam(id,message);}catch(const std::exception&e){error=e.what();}
      Glib::signal_idle().connect_once([this,answer,error]{if(!windowAlive.load())return;if(!error.empty())showError(*this,error);else{Gtk::MessageDialog dialog(*this,answer,false,Gtk::MESSAGE_INFO,Gtk::BUTTONS_OK,true);dialog.set_title("Liam");dialog.run();}status_.set_text("");});}).detach();}

  void play(){if(state_.playlist.empty())return;if(currentIndex_<0||currentIndex_>=(int)state_.playlist.size())chooseNext();if(currentIndex_>=0){audio_->play(state_.playlist[currentIndex_]);updateNowPlaying();}}
  void togglePlay(){if(!audio_->playing()){play();return;}if(audio_->paused())audio_->resume();else audio_->pause();updateTransport();}
  void stop(){audio_->stop();visualizer_.clear();lastVisualStatusUs_=0;lastProducedFrames_=0;measuredAnalysisFps_=-1;visualPerformanceStatus_.set_text("");updateTransport();}
  void chooseNext(){if(state_.playlist.empty()){currentIndex_=-1;return;}if(!state_.shuffleEnabled){currentIndex_=(currentIndex_+1)%state_.playlist.size();return;}if(shuffleBag_.empty()){shuffleBag_.resize(state_.playlist.size());std::iota(shuffleBag_.begin(),shuffleBag_.end(),0);std::shuffle(shuffleBag_.begin(),shuffleBag_.end(),random_);}currentIndex_=shuffleBag_.back();shuffleBag_.pop_back();}
  void next(){chooseNext();if(currentIndex_>=0)audio_->play(state_.playlist[currentIndex_]);updateNowPlaying();}
  void previous(){if(state_.playlist.empty())return;currentIndex_=currentIndex_<=0?state_.playlist.size()-1:currentIndex_-1;audio_->play(state_.playlist[currentIndex_]);updateNowPlaying();}
  void updateNowPlaying(){if(currentIndex_<0||currentIndex_>=(int)state_.playlist.size()){nowTitle_.set_text("No song selected");nowMeta_.set_text("Add files or folders to start a shuffled sleep playlist");return;}const auto&t=state_.playlist[currentIndex_];nowTitle_.set_text(t.displayTitle());nowMeta_.set_text(t.subtitle());}
  void updateTransport(){if(playButton_){playButton_->set_image_from_icon_name(audio_->playing()&&!audio_->paused()?"media-playback-pause-symbolic":"media-playback-start-symbolic",Gtk::ICON_SIZE_LARGE_TOOLBAR);playButton_->set_always_show_image(true);status_.set_text(audio_->playing()?(audio_->paused()?"Paused":"Playing"):"Stopped");}}
  bool progressTick(){if(!windowAlive.load())return false;const auto position=audio_->positionMs(),duration=audio_->durationMs();if(!seeking_)seek_.set_value(position);seek_.set_range(0,std::max<std::int64_t>(1,duration));seek_.set_sensitive(duration>0);elapsed_.set_text(formatTime(position));duration_.set_text(duration?formatTime(duration):"--:--");
    if(mpris_){const TrackEntry* track=currentIndex_>=0&&currentIndex_<(int)state_.playlist.size()?&state_.playlist[currentIndex_]:nullptr;mpris_->update(track,audio_->playing(),audio_->paused(),position,duration);}return true;}
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

  bool onDelete(GdkEventAny*){saveState();return false;}
  bool onConfigure(GdkEventConfigure* event){if(!is_maximized()){state_.window.width=event->width;state_.window.height=event->height;state_.window.x=event->x;state_.window.y=event->y;}return false;}
  bool onWindowState(GdkEventWindowState* event){state_.window.maximized=(event->new_window_state&GDK_WINDOW_STATE_MAXIMIZED)!=0;return false;}

  StateStore store_; AppState state_; std::unique_ptr<AudioEngine> audio_; std::unique_ptr<MprisServer> mpris_;
  Gtk::Stack stack_; Gtk::Box playerPage_{Gtk::ORIENTATION_VERTICAL},settingsPage_{Gtk::ORIENTATION_VERTICAL};Gtk::ScrolledWindow settingsScroll_;
  Gtk::Label nowTitle_,nowMeta_,elapsed_{"0:00"},duration_{"--:--"},status_,playlistStatus_,visualStatus_,visualPerformanceStatus_;Gtk::Scale seek_{Gtk::ORIENTATION_HORIZONTAL};VisualizerWidget visualizer_;
  Gtk::Button *previousButton_{},*playButton_{},*stopButton_{},*nextButton_{};Gtk::Button settingsButton_,backButton_;
  SettingsComboBoxText playlistCombo_;TrackColumns trackColumns_;Glib::RefPtr<Gtk::ListStore> trackStore_;Gtk::TreeView trackView_;Gtk::ScrolledWindow* trackScroll_{};
  Gtk::Scale *outputLevel_{},*levelingStrength_{},*analysisSeconds_{},*levelAttack_{},*levelRelease_{},*gainDown_{},*gainUp_{},*compressorThreshold_{},*outputCeiling_{},*fps_{},*waveformMs_{},*bars_{},*smoothing_{};SettingsComboBoxText fftSize_,scale_;Gtk::Entry serverUrl_,serverToken_;Gtk::CheckButton shuffle_{"Shuffle playback"};
  Gtk::Frame* latencyFrame_{};Gtk::Box latencyBox_{Gtk::ORIENTATION_VERTICAL};Gtk::Label outputLabel_,latencyLabel_,calibrationsLabel_,cacheLabel_;SettingsComboBoxText microphone_;Gtk::Button calibrate_;
  int currentIndex_{-1};std::vector<int> shuffleBag_;std::mt19937 random_;bool seeking_{false},changingPlaylist_{false};gint64 lastVisualStatusUs_{0};std::uint64_t lastProducedFrames_{0};double measuredAnalysisFps_{-1};
  sigc::connection visualTimerConnection_,progressConnection_,routeConnection_,cacheConnection_;double visualRequestedFps_{30},nextVisualFrameUs_{0};bool visualPaused_{false};std::atomic<bool> routeCheckRunning_{false},cacheCheckRunning_{false},calibrating_{false};std::string lastOutputKey_;int systemLatencyMs_{0};AudioOutput cachedOutput_;std::vector<Microphone> cachedMicrophones_;
};

void installCss() {
  auto provider=Gtk::CssProvider::create();
  provider->load_from_data(R"CSS(
    @define-color liam_blue #2f6f9e;
    @define-color liam_blue_hover #3a82b5;
    @define-color liam_blue_active #255a80;
    @define-color liam_blue_border #1c2c4a;

    window { background: @theme_bg_color; }
    button { min-height: 34px; }
    frame { border-radius: 8px; }
    frame.panel-frame > border {
      border: 1px solid alpha(@theme_fg_color, 0.22);
      border-radius: 8px;
    }
    frame.panel-frame > border > label {
      font-weight: bold;
      margin-left: 10px;
      margin-right: 10px;
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
    .muted { opacity: 0.70; }
    scale { min-width: 80px; }
    scale trough highlight,
    progressbar trough progress {
      background-color: @liam_blue;
      background-image: none;
      border-color: @liam_blue_border;
    }
    scale:hover trough highlight,
    progressbar:hover trough progress {
      background-color: @liam_blue_hover;
    }
    scale:active trough highlight {
      background-color: @liam_blue_active;
    }
    scale:focus slider,
    button:focus,
    entry:focus,
    combobox button.combo:focus {
      border-color: @liam_blue;
      box-shadow: inset 0 0 0 1px @liam_blue;
    }
    checkbutton check:checked,
    radiobutton radio:checked,
    switch:checked {
      background-color: @liam_blue;
      background-image: none;
      border-color: @liam_blue_border;
      color: #ffffff;
    }
    checkbutton check:checked:hover,
    radiobutton radio:checked:hover,
    switch:checked:hover {
      background-color: @liam_blue_hover;
    }
    treeview.view:selected,
    treeview.view:selected:focus,
    entry selection {
      background-color: @liam_blue;
      color: #ffffff;
    }
    button.suggested-action,
    button:checked {
      background-color: @liam_blue;
      background-image: none;
      border-color: @liam_blue_border;
      color: #ffffff;
    }
    button.suggested-action:hover,
    button:checked:hover {
      background-color: @liam_blue_hover;
    }
    button.suggested-action:active,
    button:checked:active {
      background-color: @liam_blue_active;
    }
    treeview { min-width: 0; }
  )CSS");
  Gtk::StyleContext::add_provider_for_screen(Gdk::Screen::get_default(),provider,GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
}

}  // namespace

int runApplication(int argc,char** argv){auto app=Gtk::Application::create(argc,argv,"com.fredplayer.nativepreview");installCss();FredPlayerWindow window;return app->run(window);}

}  // namespace fredplayer
