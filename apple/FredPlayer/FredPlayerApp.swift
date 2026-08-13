import SwiftUI

enum AppAppearance: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    var id: String { rawValue }
    var title: String { rawValue.capitalized }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

final class AppAppearanceStore: ObservableObject {
    @Published var selection: AppAppearance {
        didSet {
            UserDefaults.standard.set(selection.rawValue, forKey: "appearance")
        }
    }

    init(defaults: UserDefaults = .standard) {
        selection = AppAppearance(
            rawValue: defaults.string(forKey: "appearance") ?? ""
        ) ?? .system
    }
}

@main
struct FredPlayerApp: App {
    @StateObject private var player = PlayerController.shared
    @StateObject private var appearance = AppAppearanceStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(player)
                .environmentObject(appearance)
                .preferredColorScheme(appearance.selection.colorScheme)
        }
    }
}
