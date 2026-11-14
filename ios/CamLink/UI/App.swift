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

/// One app, two modes chosen at startup (port of MainActivity.kt / ModeScreens.kt): asks every launch unless
/// "Always start in the mode I pick" is ticked; "Change mode" releases the camera and asks again.
struct RootView: View {
    @AppStorage("lastMode") private var lastMode = ""
    @AppStorage("rememberMode") private var rememberMode = false
    @State private var mode: AppMode?
    @State private var decided = false

    var body: some View {
        Group {
            switch mode {
            // The camera connects once a mode is picked (also if it was plugged in before).
            case .control: ControlScreen(onSwitchMode: { mode = nil })
            case .upload: UploadOnlyScreen(onSwitchMode: { mode = nil })
            case nil:
                ModePicker(last: AppMode(rawValue: lastMode), always: rememberMode) { m, always in
                    lastMode = m.rawValue; rememberMode = always; mode = m
                }
            }
        }
        .onAppear {
            _ = UploadQueue.shared
            if !decided {
                decided = true
                // Earlier iOS builds stored the mode under "mode"; treat that as the last choice.
                if lastMode.isEmpty, let old = UserDefaults.standard.string(forKey: "mode") { lastMode = old }
                if rememberMode { mode = AppMode(rawValue: lastMode) }
            }
        }
    }
}

struct ModePicker: View {
    let last: AppMode?
    @State var always: Bool
    let pick: (AppMode, Bool) -> Void

    init(last: AppMode?, always: Bool, pick: @escaping (AppMode, Bool) -> Void) {
        self.last = last; _always = State(initialValue: always); self.pick = pick
    }

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("CamLink").font(.largeTitle.bold())
                    Text("How do you want to use the camera?").foregroundStyle(Theme.dim)
                    card("camera.aperture", "Camera control",
                         "Live view, tap to focus, aperture, shutter, ISO, white balance and a remote shutter. Photos are saved and uploaded too. Keeps the screen on, so it uses more battery.",
                         last == .control) { pick(.control, always) }
                    card("arrow.up", "Upload only",
                         "No live view. Shoot with the camera's own shutter; every photo is saved, auto-edited and uploaded. Saves power on long shoots. On iPhone and iPad CamLink must stay open (use Dark screen).",
                         last == .upload) { pick(.upload, always) }
                    Button { always.toggle() } label: {
                        HStack(spacing: 12) {
                            Image(systemName: always ? "checkmark.square.fill" : "square").font(.title3).foregroundStyle(always ? Theme.accent : Theme.dim)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Always start in the mode I pick").foregroundStyle(.white)
                                Text("You can change it later with “Change mode”.").font(.caption).foregroundStyle(Theme.dim)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    SetupHint()
                }
                .padding(20)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private func card(_ icon: String, _ title: String, _ text: String, _ lastUsed: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 16) {
                Image(systemName: icon).font(.system(size: 22, weight: .bold)).foregroundStyle(.black)
                    .frame(width: 48, height: 48).background(Theme.accent, in: Circle())
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(title).font(.title3.bold()).foregroundStyle(.white)
                        if lastUsed { Text("last used").font(.caption).foregroundStyle(Theme.accent) }
                    }
                    Text(text).font(.subheadline).foregroundStyle(Theme.dim).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
            }
            .padding(18)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(lastUsed ? Theme.accent.opacity(0.6) : Color(white: 0.16), lineWidth: 1))
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
