import SwiftUI
import CamLinkCore

/// Camera control mode: live view with tap-to-focus and the camera's AF frame, exposure controls, shutter.
struct ControlScreen: View {
    let onSwitchMode: () -> Void
    @StateObject private var model = CameraModel(mode: .control)
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    @State private var showGallery = false
    @State private var viewing: SavedShot?

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > geo.size.height
            ZStack {
                Color.black.ignoresSafeArea()
                if model.phase == .connected {
                    if wide {
                        HStack(spacing: 0) {
                            liveView.frame(maxWidth: .infinity, maxHeight: .infinity)
                            sidePanel.frame(width: min(340, geo.size.width * 0.34))
                        }
                    } else {
                        VStack(spacing: 0) {
                            topBar
                            liveView.frame(maxHeight: .infinity)
                            controls
                        }
                    }
                } else {
                    VStack(spacing: 0) {
                        topBar
                        ScrollView { ConnectionPanel(model: model, connector: model.connector).frame(maxWidth: .infinity) }
                    }
                }
            }
        }
        .toast($model.toast)
        .onAppear { model.start() }
        .onChange(of: scenePhase) { phase in
            // iOS suspends apps in the background, which ends the USB session; reconnect on return.
            if phase == .active && model.phase == .disconnected { model.connect() }
        }
        .sheet(isPresented: $showSettings) { SettingsScreen(onSwitchMode: { showSettings = false; model.disconnect(); onSwitchMode() }) }
        .sheet(isPresented: $showGallery) { GalleryScreen() }
        .fullScreenCover(item: $viewing) { shot in PhotoViewer(shots: model.shots, start: shot) }
    }

    // ------------------------------------------------------------------ pieces

    private var topBar: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.phase == .connected ? model.modelName : "CamLink").font(.headline)
                if model.phase == .connected {
                    Text("\(model.focusMode) · \(model.savedCount) saved").font(.caption).foregroundStyle(Theme.dim)
                }
            }
            Spacer()
            UploadStatusLine()
            Button("Change mode") { model.disconnect(); onSwitchMode() }
                .font(.footnote).foregroundStyle(Theme.accent).padding(.horizontal, 4)
            iconButton("photo.stack") { showGallery = true }
            iconButton("gearshape") { showSettings = true }
            if model.phase == .connected { iconButton("eject") { model.disconnect() } }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
    }

    private func iconButton(_ name: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name).font(.system(size: 17)).frame(width: 38, height: 38)
                .background(Theme.card, in: Circle()).foregroundStyle(.white)
        }
    }

    private var liveView: some View {
        GeometryReader { g in
            let img = model.frame
            let aspect = img.map { $0.size.width / max($0.size.height, 1) } ?? 1.5
            let fit = fitRect(aspect: aspect, in: g.size)
            ZStack(alignment: .topLeading) {
                if let img {
                    Image(uiImage: img).resizable().interpolation(.medium)
                        .frame(width: fit.width, height: fit.height).offset(x: fit.minX, y: fit.minY)
                } else {
                    ProgressView().frame(width: g.size.width, height: g.size.height)
                }
                AfOverlay(model: model, rect: fit)
                if let busy = model.busy {
                    Text(busy).font(.subheadline.bold()).padding(.horizontal, 12).padding(.vertical, 6)
                        .background(.black.opacity(0.6), in: Capsule()).foregroundStyle(.white)
                        .position(x: g.size.width / 2, y: 30)
                }
            }
            .frame(width: g.size.width, height: g.size.height, alignment: .topLeading)
            .contentShape(Rectangle())
            .onTapGesture { p in
                guard fit.contains(p) else { return }
                model.focus(at: CGPoint(x: (p.x - fit.minX) / fit.width, y: (p.y - fit.minY) / fit.height))
            }
        }
    }

    private func fitRect(aspect: CGFloat, in size: CGSize) -> CGRect {
        var w = size.width, h = w / aspect
        if h > size.height { h = size.height; w = h * aspect }
        return CGRect(x: (size.width - w) / 2, y: (size.height - h) / 2, width: w, height: h)
    }

    private var controls: some View {
        VStack(spacing: 10) {
            SettingsBar(model: model)
            shutterRow
        }
        .padding(.vertical, 10)
        .background(Theme.bg)
    }

    private var sidePanel: some View {
        VStack(spacing: 12) {
            topBar
            ScrollView { SettingsGrid(model: model) }
            shutterRow.padding(.bottom, 12)
        }
        .background(Theme.bg)
    }

    private var shutterRow: some View {
        HStack {
            Button { if let s = model.shots.first { viewing = s } } label: {
                Group {
                    if let t = model.shots.first?.thumb { Image(uiImage: t).resizable().scaledToFill() }
                    else { Image(systemName: "photo").foregroundStyle(Theme.dim) }
                }
                .frame(width: 54, height: 54).clipShape(RoundedRectangle(cornerRadius: 8))
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 8))
            }
            Spacer()
            Button { model.shoot() } label: {
                ZStack {
                    Circle().stroke(.white, lineWidth: 4).frame(width: 76, height: 76)
                    Circle().fill(model.busy == nil ? .white : Theme.dim).frame(width: 62, height: 62)
                }
            }
            .disabled(model.busy != nil)
            Spacer()
            Button { model.autoFocus() } label: {
                Text("AF").font(.headline).frame(width: 54, height: 54)
                    .background(Theme.card, in: Circle()).foregroundStyle(.white)
            }
        }
        .padding(.horizontal, 24)
    }
}

/// The camera's AF frame (as the body reports it) plus the tapped point.
struct AfOverlay: View {
    @ObservedObject var model: CameraModel
    let rect: CGRect

    var body: some View {
        let color: Color = {
            switch model.focusState {
            case .focused: return Theme.good
            case .failed: return Theme.bad
            case .focusing: return .white
            case .idle: return (model.camFocus?.locked ?? false) ? Theme.good : .white.opacity(0.85)
            }
        }()
        ZStack(alignment: .topLeading) {
            if let p = model.focusPoint ?? (model.camFocus?.kind == .spot ? model.camFocus?.spot : nil) {
                let size = rect.width * CGFloat(model.camFocus?.size ?? 0.085)
                Rectangle().stroke(color, lineWidth: 2)
                    .frame(width: size, height: size)
                    .position(x: rect.minX + p.x * rect.width, y: rect.minY + p.y * rect.height)
            } else if let f = model.camFocus, f.kind != .spot {
                let inset: CGFloat = f.kind == .wide ? 0.08 : (f.kind == .zone ? 0.28 : 0.42)
                Rectangle().stroke(color, style: StrokeStyle(lineWidth: 1.5, dash: [8, 6]))
                    .frame(width: rect.width * (1 - 2 * inset), height: rect.height * (1 - 2 * inset))
                    .position(x: rect.midX, y: rect.midY)
            }
        }
        .allowsHitTesting(false)
    }
}

/// Phone layout: horizontal chips, the selected one opens a ‹ value › adjuster or a list.
struct SettingsBar: View {
    @ObservedObject var model: CameraModel

    var body: some View {
        VStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(model.settings) { s in
                        Button { if !s.readOnly { model.select(s.code) } } label: {
                            VStack(spacing: 2) {
                                Text(s.label).font(.system(size: 10, weight: .semibold)).foregroundStyle(Theme.dim)
                                Text(s.value).font(.system(size: 15, weight: .bold)).foregroundStyle(s.adjustable || s.readOnly ? .white : Theme.dim)
                            }
                            .padding(.horizontal, 12).padding(.vertical, 7)
                            .background(model.selected == s.code ? Theme.accent.opacity(0.3) : Theme.card, in: RoundedRectangle(cornerRadius: 10))
                        }
                    }
                }
                .padding(.horizontal, 12)
            }
            if let code = model.selected, let s = model.settings.first(where: { $0.code == code }) {
                Adjuster(model: model, s: s).padding(.horizontal, 12)
            }
        }
    }
}

/// iPad / landscape layout: every setting with its own adjuster.
struct SettingsGrid: View {
    @ObservedObject var model: CameraModel
    var body: some View {
        VStack(spacing: 8) {
            ForEach(model.settings) { s in
                if s.readOnly {
                    HStack { Text(s.label).font(.caption).foregroundStyle(Theme.dim); Spacer(); Text(s.value).bold() }
                        .padding(10).background(Theme.card, in: RoundedRectangle(cornerRadius: 10))
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(s.label).font(.caption).foregroundStyle(Theme.dim)
                        Adjuster(model: model, s: s)
                    }
                }
            }
        }
        .padding(12)
    }
}

struct Adjuster: View {
    @ObservedObject var model: CameraModel
    let s: SettingUi

    var body: some View {
        HStack(spacing: 10) {
            step("chevron.left", -1)
            if Exposure.isList(s.code) && !s.options.isEmpty {
                Menu {
                    ForEach(s.options, id: \.self) { o in
                        Button { model.setOption(s.code, o.value) } label: {
                            if o.value == s.raw { Label(o.label, systemImage: "checkmark") } else { Text(o.label) }
                        }
                    }
                } label: { valueLabel }
                .disabled(!s.adjustable)
            } else {
                valueLabel
            }
            step("chevron.right", 1)
        }
        .opacity(s.adjustable ? 1 : 0.45)
    }

    private var valueLabel: some View {
        Text(s.value).font(.title3.bold()).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 44)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 10))
    }

    private func step(_ icon: String, _ dir: Int) -> some View {
        Button { model.adjust(s.code, dir) } label: {
            Image(systemName: icon).font(.headline).frame(width: 48, height: 44)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 10)).foregroundStyle(.white)
        }
        .disabled(!s.adjustable)
    }
}

/// Full-screen viewer for photos saved in this session (swipe between them).
struct PhotoViewer: View {
    let shots: [SavedShot]
    let start: SavedShot
    @Environment(\.dismiss) private var dismiss
    @State private var index = 0

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(Array(shots.enumerated()), id: \.element.id) { i, s in
                    FileImage(url: s.file).tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            VStack(alignment: .trailing) {
                Button { dismiss() } label: {
                    Image(systemName: "xmark").font(.headline).frame(width: 40, height: 40).background(.black.opacity(0.6), in: Circle()).foregroundStyle(.white)
                }
                Spacer()
                if shots.indices.contains(index) {
                    let s = shots[index]
                    HStack {
                        Text("\(s.name) · \(String(format: "%.1f MB", Double(s.size) / 1_048_576))").font(.caption)
                        ShareLink(item: s.file) { Image(systemName: "square.and.arrow.up") }
                    }
                    .padding(10).background(.black.opacity(0.6), in: Capsule())
                }
            }
            .padding()
        }
        .onAppear { index = shots.firstIndex(of: start) ?? 0 }
    }
}

/// Loads a downscaled, upright image from a file (JPEG or RAW preview).
struct FileImage: View {
    let url: URL
    var maxPixel = 2400
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFit() } else { ProgressView() }
        }
        .task(id: url) {
            let u = url, m = maxPixel
            image = await Task.detached { () -> UIImage? in
                guard let d = try? Data(contentsOf: u) else { return nil }
                return CameraModel.thumbnail(d, maxPixel: m)
            }.value
        }
    }
}
