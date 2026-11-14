import SwiftUI

/// Upload-only mode (the Android "Lite" tether): no live view; receives every photo taken with the camera's own
/// shutter, saves it, auto-edits and uploads it. iOS can't run this in the background, so the screen stays on;
/// "Dark screen" blacks it out and dims the backlight to save battery.
struct UploadOnlyScreen: View {
    let onSwitchMode: () -> Void
    @StateObject private var model = CameraModel(mode: .upload)
    @ObservedObject private var queue = UploadQueue.shared
    @ObservedObject private var store = UploadSettingsStore.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    @State private var showGallery = false
    @State private var dark = false
    @State private var savedBrightness: CGFloat = 0.5

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 18) {
                    header
                    if model.phase == .connected { connectedCard } else {
                        ConnectionPanel(model: model, connector: model.connector)
                    }
                    uploadCard
                    Text("Keep CamLink open while shooting: iOS pauses USB and uploads when the app is in the background or the device is locked. Use Dark screen to save battery.")
                        .font(.footnote).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
                }
                .padding(20)
                .frame(maxWidth: 600)
                .frame(maxWidth: .infinity)
            }
            if dark {
                Color.black.ignoresSafeArea()
                    .overlay(alignment: .bottom) {
                        Text("\(model.savedCount) received · \(queue.state.pending) uploading · tap to wake")
                            .font(.caption).foregroundStyle(Color(white: 0.25)).padding(.bottom, 40)
                    }
                    .onTapGesture { setDark(false) }
            }
        }
        .toast($model.toast)
        .statusBarHidden(dark)
        .onAppear { model.start() }
        .onChange(of: scenePhase) { phase in
            if phase == .active && model.phase == .disconnected { model.connect() }
            if phase != .active && dark { setDark(false) }
        }
        .sheet(isPresented: $showSettings) { SettingsScreen(onSwitchMode: { showSettings = false; model.disconnect(); onSwitchMode() }) }
        .sheet(isPresented: $showGallery) { GalleryScreen() }
    }

    private func setDark(_ on: Bool) {
        if on { savedBrightness = UIScreen.main.brightness; UIScreen.main.brightness = 0.01 }
        else { UIScreen.main.brightness = savedBrightness }
        dark = on
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading) {
                Text("Upload only").font(.title2.bold())
                Text(model.phase == .connected ? model.modelName : "No camera").foregroundStyle(Theme.dim)
            }
            Spacer()
            Button("Change mode") { model.disconnect(); onSwitchMode() }.font(.footnote).padding(.trailing, 12)
            Button { showGallery = true } label: { Image(systemName: "photo.stack").font(.title3) }
            Button { showSettings = true } label: { Image(systemName: "gearshape").font(.title3) }.padding(.leading, 12)
        }
    }

    private var connectedCard: some View {
        VStack(spacing: 12) {
            Image(systemName: model.busy == nil ? "camera.shutter.button" : "arrow.down.circle")
                .font(.system(size: 44)).foregroundStyle(Theme.accent)
            Text(model.busy ?? model.message).font(.headline)
            Text("\(model.savedCount) photo\(model.savedCount == 1 ? "" : "s") received\(model.lastName.map { " · last \($0)" } ?? "")")
                .foregroundStyle(Theme.dim)
            HStack {
                Button { setDark(true) } label: { Label("Dark screen", systemImage: "moon.fill") }.buttonStyle(.borderedProminent)
                Button(role: .destructive) { model.disconnect() } label: { Label("Disconnect", systemImage: "eject") }.buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
    }

    private var uploadCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Upload").font(.headline)
                Spacer()
                UploadStatusLine()
            }
            if !store.settings.anyDestination {
                Text("Uploading is off. Photos are only saved on this device (Files › On My iPhone › CamLink).")
                    .font(.footnote).foregroundStyle(Theme.dim)
                Button("Set up FTP or Google Drive") { showSettings = true }
            } else {
                Text([store.settings.isComplete ? "FTP \(store.settings.host)" : nil,
                      store.settings.driveReady ? "Drive \(store.settings.driveFolderPath)" : nil,
                      store.settings.autoEdit ? "auto-edit on" : nil].compactMap { $0 }.joined(separator: " · "))
                    .font(.footnote).foregroundStyle(Theme.dim)
                if let e = queue.state.lastError { Text(e).font(.footnote).foregroundStyle(Theme.bad).lineLimit(2) }
            }
        }
        .padding(16)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
    }
}
