import SwiftUI
import CamLinkCore

@main
struct CamLinkApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
    }
}

enum Theme {
    static let accent = Color(red: 1.0, green: 0.62, blue: 0.16)
    static let bg = Color(red: 0.043, green: 0.043, blue: 0.059)
    static let card = Color(red: 0.11, green: 0.11, blue: 0.13)
    static let dim = Color(white: 0.6)
    static let good = Color(red: 0.4, green: 0.8, blue: 0.45)
    static let bad = Color(red: 0.9, green: 0.45, blue: 0.45)
}

/// Asks at startup which mode to run, like the Android app: camera control or upload only.
struct RootView: View {
    @AppStorage("mode") private var mode = ""

    var body: some View {
        Group {
            switch AppMode(rawValue: mode) {
            case .control: ControlScreen(onSwitchMode: { mode = "" })
            case .upload: UploadOnlyScreen(onSwitchMode: { mode = "" })
            case nil: ModePicker { mode = $0.rawValue }
            }
        }
        .onAppear { _ = UploadQueue.shared }
    }
}

struct ModePicker: View {
    let pick: (AppMode) -> Void

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("CamLink").font(.largeTitle.bold())
                    Text("How do you want to use the camera?").foregroundStyle(Theme.dim)
                    card("camera.aperture", "Camera control",
                         "Live view, tap to focus, aperture, shutter, ISO, EV, white balance, focus mode and AF area, and the shutter button.") { pick(.control) }
                    card("arrow.up.circle", "Upload only",
                         "No live view. Receives every photo you take with the camera's own shutter, auto-edits and uploads it. Uses less battery.") { pick(.upload) }
                    Text("You can switch later in Settings.").font(.footnote).foregroundStyle(Theme.dim)
                    SetupHint()
                }
                .padding(20)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private func card(_ icon: String, _ title: String, _ text: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 16) {
                Image(systemName: icon).font(.system(size: 30)).foregroundStyle(Theme.accent).frame(width: 40)
                VStack(alignment: .leading, spacing: 6) {
                    Text(title).font(.title3.bold()).foregroundStyle(.white)
                    Text(text).font(.subheadline).foregroundStyle(Theme.dim).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundStyle(Theme.dim)
            }
            .padding(18)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }
}

/// Camera setup instructions (same as the Android app's).
struct SetupHint: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Camera setup").font(.headline)
            Text("On the camera: MENU › Setup › USB Connection › **PC Remote**, and PC Remote Settings › Still Img. Save Dest. › **PC+Camera**. Then connect it to the iPhone or iPad with a USB-C cable (Lightning iPhones need Apple's Lightning to USB 3 Camera Adapter).")
                .font(.footnote).foregroundStyle(Theme.dim)
        }
        .padding(14)
        .background(Theme.card.opacity(0.6), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Connection state shown while no camera is connected.
struct ConnectionPanel: View {
    @ObservedObject var model: CameraModel
    @ObservedObject var connector: CameraConnector

    var body: some View {
        VStack(spacing: 14) {
            switch connector.state {
            case .denied:
                Image(systemName: "lock.fill").font(.largeTitle).foregroundStyle(Theme.bad)
                Text("CamLink isn't allowed to control cameras").font(.headline)
                Text("Open Settings › Privacy & Security › Cameras (or Settings › CamLink) and allow it.").font(.footnote).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
                Button("Open Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
                    .buttonStyle(.borderedProminent)
            case .needsPermission:
                Image(systemName: "cable.connector").font(.largeTitle).foregroundStyle(Theme.accent)
                Text("Allow camera control").font(.headline)
                Text("iOS asks once whether CamLink may control connected cameras. Tap Allow.").font(.footnote).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
                Button("Ask again") { connector.requestPermission() }.buttonStyle(.bordered)
            default:
                if model.phase == .connecting {
                    ProgressView().controlSize(.large)
                } else {
                    Image(systemName: "cable.connector").font(.largeTitle).foregroundStyle(Theme.accent)
                }
                Text(model.message).font(.headline).multilineTextAlignment(.center)
                if case .found(let name) = connector.state, model.phase == .disconnected {
                    Button("Connect to \(name)") { model.connect() }.buttonStyle(.borderedProminent)
                }
                if case .failed(let m) = connector.state { Text(m).font(.footnote).foregroundStyle(Theme.bad) }
            }
            SetupHint().frame(maxWidth: 520)
        }
        .padding(24)
    }
}

/// Upload summary line (port of the Android status bar).
struct UploadStatusLine: View {
    @ObservedObject var queue = UploadQueue.shared
    @ObservedObject var store = UploadSettingsStore.shared

    var body: some View {
        let s = queue.state
        if store.settings.anyDestination || s.pending > 0 {
            HStack(spacing: 8) {
                if let cur = s.current {
                    ProgressView(value: s.editing ? nil : s.progress).frame(width: 60).tint(Theme.accent)
                    Text(s.editing ? "Editing \(cur)…" : cur).lineLimit(1)
                } else if s.pending > 0 {
                    Image(systemName: "clock")
                    Text("\(s.pending) waiting to upload")
                } else {
                    Image(systemName: "checkmark.circle").foregroundStyle(Theme.good)
                    Text(s.uploaded > 0 ? "\(s.uploaded) uploaded" : "Upload on")
                }
                if s.failed > 0 { Text("· \(s.failed) failed").foregroundStyle(Theme.bad) }
            }
            .font(.caption)
            .foregroundStyle(.white)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(.black.opacity(0.55), in: Capsule())
        }
    }
}

/// Short message at the bottom of the screen.
struct ToastModifier: ViewModifier {
    @Binding var text: String?
    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let t = text {
                Text(t).font(.subheadline).padding(.horizontal, 14).padding(.vertical, 10)
                    .background(.black.opacity(0.8), in: Capsule()).foregroundStyle(.white)
                    .padding(.bottom, 110).padding(.horizontal, 20)
                    .transition(.opacity)
                    .task(id: t) {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        if text == t { withAnimation { text = nil } }
                    }
            }
        }
    }
}

extension View {
    func toast(_ text: Binding<String?>) -> some View { modifier(ToastModifier(text: text)) }
}
