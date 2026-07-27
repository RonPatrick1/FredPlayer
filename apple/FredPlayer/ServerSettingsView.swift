import SwiftUI

struct ServerSettingsView: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @State private var isTesting = false
    @State private var result: String?
    @State private var isRescanning = false
    @State private var rescanResult: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Fred Server") {
                    TextField("https://music.example.com", text: $player.serverBaseURL)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                    SecureField("Token", text: $player.serverToken)
                        .textInputAutocapitalization(.never)
                }
                Section("Device ID") {
                    Text(player.deviceID)
                        .font(.footnote.monospaced())
                        .textSelection(.enabled)
                }
                Section {
                    Button {
                        testConnection()
                    } label: {
                        if isTesting { ProgressView() } else { Text("Test Connection") }
                    }
                    .disabled(isTesting || player.serverClient == nil)
                    if let result { Text(result).foregroundStyle(.secondary) }
                }
                Section {
                    Button {
                        rescanLibrary()
                    } label: {
                        if isRescanning { ProgressView() } else { Text("Rescan Library") }
                    }
                    .disabled(isRescanning || player.serverClient == nil)
                    if let rescanResult { Text(rescanResult).foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("Server Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }

    private func testConnection() {
        guard let client = player.serverClient else { return }
        isTesting = true
        result = nil
        Task {
            do {
                let tracks = try await client.fetchLibrary()
                result = "Connected. Found \(tracks.count) tracks."
            } catch {
                result = error.localizedDescription
            }
            isTesting = false
        }
    }

    private func rescanLibrary() {
        guard let client = player.serverClient else { return }
        isRescanning = true
        rescanResult = nil
        Task {
            do {
                let count = try await client.rescanLibrary()
                rescanResult = "Rescanned. Found \(count) tracks."
            } catch {
                rescanResult = error.localizedDescription
            }
            isRescanning = false
        }
    }
}
