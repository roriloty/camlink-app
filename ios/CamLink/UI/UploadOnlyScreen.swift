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
            GeometryReader { geo in
                if geo.size.width > geo.size.height {
                    // Landscape: status and buttons in a left column, the gallery fills the full height on the right.
                    HStack(alignment: .top, spacing: 12) {
                        ScrollView { column.padding(.vertical, 12) }.frame(width: 300)
                        UploadGalleryView(tileSize: 96).frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    .padding(.horizontal, 12)
                } else {
                    VStack(spacing: 8) {
                        ScrollView { column.padding(.top, 12) }.frame(maxHeight: geo.size.height * 0.55)
                        UploadGalleryView(tileSize: 96).frame(maxHeight: .infinity)
                    }
                    .padding(.horizontal, 16)
                    .frame(maxWidth: 700)
                    .frame(maxWidth: .infinity)
                }
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

    private var column: some View {
        VStack(spacing: 14) {
            header
            if model.phase == .connected { connectedCard } else {
                ConnectionPanel(model: model, connector: model.connector)
            }
            uploadCard
            Text("Keep CamLink open while shooting: iOS pauses USB and uploads when the app is in the background or the device is locked. Use Dark screen to save battery.")
                .font(.footnote).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
        }
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
            PillButton(icon: "arrow.left.arrow.right", title: "Change mode", highlighted: true) { model.disconnect(); onSwitchMode() }
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

    private var uploadText: String {
        let st = queue.state
        if !store.settings.anyDestination { return "Upload is off (no FTP or Google Drive set up), so photos are only saved to the phone" }
        if st.uploading > 0 || st.editingName != nil || st.pending > 0 { return st.describe() }
        return "Upload ready" + (store.settings.autoEdit ? " · auto-edit on" : "") + " · up to \(UploadQueue.MAX_PARALLEL) at once"
    }

    private var uploadCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(uploadText).font(.subheadline).foregroundStyle(.white)
            Text("\(queue.state.uploaded) uploaded" + (queue.state.failed > 0 ? " · \(queue.state.failed) failed" : ""))
                .font(.footnote).foregroundStyle(Theme.dim)
            if queue.state.failed > 0, let e = queue.state.lastError { Text(e).font(.footnote).foregroundStyle(Theme.bad).lineLimit(2) }
            HStack {
                PillButton(icon: "gearshape", title: "Upload settings") { showSettings = true }
                PillButton(icon: "square.grid.2x2", title: "Uploads") { showGallery = true }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Theme.card.opacity(0.6), in: RoundedRectangle(cornerRadius: 16))
    }
}
