import Foundation
import UIKit
import ImageIO
import CamLinkCore

enum AppMode: String { case control, upload }
enum Phase { case disconnected, connecting, connected }
enum FocusState { case idle, focusing, focused, failed }

struct SavedShot: Identifiable, Equatable {
    let id = UUID()
    let name: String
    let size: Int
    let thumb: UIImage?
    let file: URL
    var isJpeg: Bool { name.lowercased().hasSuffix(".jpg") || name.lowercased().hasSuffix(".jpeg") }
}

/// Thread-safe flags shared by the USB loop and the UI actions.
private final class Flags {
    private let l = NSLock()
    private var _objectAdded = false, _focusDirty = false, _shooting = false
    private var _tapAt = Date.distantPast, _lvPausedUntil = Date.distantPast
    func with<T>(_ f: () -> T) -> T { l.lock(); defer { l.unlock() }; return f() }
    var objectAdded: Bool { get { with { _objectAdded } } set { with { _objectAdded = newValue } } }
    func takeObjectAdded() -> Bool { with { defer { _objectAdded = false }; return _objectAdded } }
    var focusDirty: Bool { get { with { _focusDirty } } set { with { _focusDirty = newValue } } }
    func takeFocusDirty() -> Bool { with { defer { _focusDirty = false }; return _focusDirty } }
    var shooting: Bool { get { with { _shooting } } set { with { _shooting = newValue } } }
    var tapAt: Date { get { with { _tapAt } } set { with { _tapAt = newValue } } }
    var liveViewPausedUntil: Date { get { with { _lvPausedUntil } } set { with { _lvPausedUntil = newValue } } }
}

/// Port of CameraViewModel.kt (camera control mode) and TetherService.kt (upload-only mode).
/// The USB loop runs on its own thread; every camera command holds `lock` for one short transaction only.
final class CameraModel: ObservableObject {
    @Published var phase: Phase = .disconnected
    @Published var message = "Plug the camera into the iPhone or iPad with a USB cable."
    @Published var modelName = ""
    @Published var focusMode = ""
    /// Last tapped AF point as a fraction (0..1) of the live view frame (shown until the camera confirms it).
    @Published var focusPoint: CGPoint?
    /// The camera's own AF area / frame / focus state, live.
    @Published var camFocus: FocusInfo?
    @Published var focusState: FocusState = .idle
    @Published var busy: String?
    @Published var shots: [SavedShot] = []
    @Published var savedCount = 0
    @Published var settings: [SettingUi] = []
    @Published var selected: Int? = Sony.DPC_F_NUMBER
    @Published var toast: String?
    @Published var frame: UIImage?
    @Published var lastName: String?

    let connector = CameraConnector()
    let mode: AppMode
    private var camera: SonyCamera?
    private let lock = NSLock()
    private let flags = Flags()
    private var loop: Thread?
    private let wake = DispatchSemaphore(value: 0)
    private var awaitingSince: Date?
    private var downloadFailures = 0
    private var lastLogged: [Int: Int64] = [:]
    private let actions = DispatchQueue(label: "camlink.actions")

    private static let NOISY_PROPS: Set<Int> = [0xD21D, 0x5004]
    private static let FOCUS_PROPS: Set<Int> = [Sony.DPC_FOCUS_FOUND, Sony.DPC_FOCUS_AREA, Sony.DPC_FOCUS_POS_SET]

    init(mode: AppMode) {
        self.mode = mode
        connector.onCameraFound = { [weak self] in self?.connect() }
        connector.onDisconnect = { [weak self] in self?.disconnect("Camera disconnected") }
    }

    var shotsSinceStart: Int { savedCount }

    private func ui(_ f: @escaping (CameraModel) -> Void) { DispatchQueue.main.async { [weak self] in if let self { f(self) } } }

    private func withCam<T>(_ f: () throws -> T) rethrows -> T { lock.lock(); defer { lock.unlock() }; return try f() }

    // ------------------------------------------------------------------ connection

    func start() { connector.start() }

    func connect() {
        guard phase == .disconnected else { return }
        guard let device = connector.camera else { connector.start(); return }
        let name = device.name ?? "Camera"
        phase = .connecting
        message = "Connecting to \(name)…"
        modelName = name
        DebugLog.d("Connecting… (\(UIDevice.current.systemName) \(UIDevice.current.systemVersion), \(UIDevice.current.model), \(mode.rawValue) mode)")
        connector.open { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let e):
                self.phase = .disconnected
                self.message = "Could not connect: \(e.localizedDescription)"
            case .success(let transport):
                let thread = Thread { [weak self] in self?.runSession(transport) }
                thread.name = "camlink-usb"
                thread.qualityOfService = .userInitiated
                self.loop = thread
                thread.start()
            }
        }
    }

    private func runSession(_ transport: PtpTransport) {
        let cam = SonyCamera(ptp: transport)
        transport.onEvent = { [weak self] code, params in
            guard let self else { return }
            if code == Sony.EVENT_PROP_CHANGED, let p = params.first, Self.FOCUS_PROPS.contains(Int(p)) { self.flags.focusDirty = true }
            if code == Sony.EVENT_OBJECT_ADDED {
                // Also fires for shots taken with the camera's own shutter button.
                self.flags.liveViewPausedUntil = Date().addingTimeInterval(1.5)
                self.flags.objectAdded = true
                self.wake.signal()
            }
        }
        do {
            try withCam { try cam.connect() }
        } catch {
            DebugLog.d("Connect failed: \(error.localizedDescription)")
            ui { $0.phase = .disconnected; $0.message = "Could not connect: \(error.localizedDescription)"; $0.connector.disconnect() }
            return
        }
        camera = cam
        ui {
            $0.phase = .connected; $0.message = "Connected"
            $0.toast = "Connected to \($0.modelName)"
            UIApplication.shared.isIdleTimerDisabled = true
        }
        applyProps(cam)
        if mode == .control { runLoop(cam) } else { listen(cam) }
    }

    func disconnect(_ reason: String = "Disconnected") {
        let had = camera != nil || phase != .disconnected
        camera = nil
        loop?.cancel(); loop = nil
        wake.signal()
        frame = nil
        phase = .disconnected; busy = nil; focusPoint = nil
        message = reason
        UIApplication.shared.isIdleTimerDisabled = false
        if had { connector.disconnect() }
    }

    private var cancelled: Bool { Thread.current.isCancelled }

    // ------------------------------------------------------------------ live loop (camera control mode)

    private func runLoop(_ cam: SonyCamera) {
        var lastProps = Date.distantPast
        var failures = 0
        while !cancelled && camera === cam {
            do {
                let now = Date()
                let newImage = flags.takeObjectAdded()
                let focusChanged = flags.takeFocusDirty()
                if newImage || focusChanged || now.timeIntervalSince(lastProps) > 0.3 {
                    lastProps = now
                    try withCam { try cam.refreshProps() }
                    applyProps(cam)
                    if cam.pendingImages > 0 { try downloadPending(cam) }
                    else if let since = awaitingSince, now.timeIntervalSince(since) > 40 {
                        awaitingSince = nil
                        ui { $0.busy = nil; $0.toast = "No image received. Check \"Still Img. Save Dest.\" is PC+Camera." }
                    }
                }
                if Date() < flags.liveViewPausedUntil { Thread.sleep(forTimeInterval: 0.05); continue }
                if let jpeg = try withCam({ try cam.liveView() }), let img = UIImage(data: jpeg) {
                    ui { if $0.camera === cam { $0.frame = img } }
                } else {
                    Thread.sleep(forTimeInterval: 0.04)
                }
                failures = 0
            } catch {
                DebugLog.d("Live loop error: \(error.localizedDescription)")
                failures += 1
                withCam { cam.recover() }
                if failures >= 5 {
                    if camera === cam { ui { $0.disconnect("Connection lost: \(error.localizedDescription)") } }
                    return
                }
                Thread.sleep(forTimeInterval: 0.15)
            }
        }
    }

    // ------------------------------------------------------------------ upload-only loop

    /// Idles until the camera reports a new picture (or every 2 s as a fallback), then downloads it.
    private func listen(_ cam: SonyCamera) {
        var failures = 0
        ui { $0.message = "Waiting for photos" }
        while !cancelled && camera === cam {
            let event = wake.wait(timeout: .now() + 2) == .success
            if cancelled || camera !== cam { return }
            if event { Thread.sleep(forTimeInterval: 0.3) } // let the camera finish writing
            do {
                _ = flags.takeObjectAdded()
                try withCam { try cam.refreshProps() }
                try downloadPending(cam)
                failures = 0
            } catch {
                DebugLog.d("Check failed: \(error.localizedDescription)")
                withCam { cam.recover() }
                failures += 1
                if failures >= 3 {
                    if camera === cam { ui { $0.disconnect("Connection lost: \(error.localizedDescription)") } }
                    return
                }
                Thread.sleep(forTimeInterval: 0.5)
            }
        }
    }

    private func downloadPending(_ cam: SonyCamera) throws {
        var guardN = 0
        while cam.pendingImages > 0 && guardN < 10 {
            guardN += 1
            ui { $0.busy = "Downloading image…" }
            let img: SonyCamera.Image
            do {
                img = try withCam { () throws -> SonyCamera.Image in
                    let i = try cam.downloadImage()
                    try cam.refreshProps()
                    return i
                }
            } catch {
                downloadFailures += 1
                DebugLog.d("Download failed (\(downloadFailures)): \(error.localizedDescription)")
                ui { $0.busy = nil }
                if downloadFailures >= 3 {
                    downloadFailures = 0; awaitingSince = nil
                    ui { $0.toast = "Couldn't download the photo (it is still on the camera's card)" }
                }
                throw error
            }
            downloadFailures = 0
            ui { $0.busy = "Saving \(img.fileName)…" }
            do {
                let url = try PhotoStore.save(name: img.fileName, bytes: img.bytes)
                UploadQueue.shared.enqueueFromAnyThread(file: url, name: img.fileName, size: Int64(img.bytes.count))
                let thumb = Self.thumbnail(img.bytes, maxPixel: 240)
                let shot = SavedShot(name: img.fileName, size: img.bytes.count, thumb: thumb, file: url)
                ui {
                    $0.shots.insert(shot, at: 0)
                    $0.savedCount += 1
                    $0.lastName = img.fileName
                    $0.toast = String(format: "Saved %@ (%.1f MB)", img.fileName, Double(img.bytes.count) / 1_048_576.0)
                }
            } catch {
                ui { $0.toast = "Saving \(img.fileName) failed: \(error.localizedDescription)" }
            }
        }
        awaitingSince = nil
        flags.liveViewPausedUntil = .distantPast
        ui { $0.busy = nil; if $0.mode == .upload { $0.message = "Waiting for photos" } }
    }

    static func thumbnail(_ data: Data, maxPixel: Int) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                     kCGImageSourceCreateThumbnailWithTransform: true,
                                     kCGImageSourceThumbnailMaxPixelSize: maxPixel]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }

    private func applyProps(_ cam: SonyCamera) {
        let p = cam.props
        if !lastLogged.isEmpty {
            for (k, v) in p where !Self.NOISY_PROPS.contains(k) {
                if let old = lastLogged[k], old != v { DebugLog.d(String(format: "Prop %04X: 0x%llX -> 0x%llX", k, old, v)) }
            }
        }
        lastLogged = p
        let fm = p[Sony.DPC_FOCUS_MODE].flatMap { $0 != 0 ? Exposure.focusMode($0) : nil } ?? ""
        let settings = Exposure.build(cam.propInfo)
        let focus = cam.focusInfo()
        let tapAt = flags.tapAt
        ui { s in
            // Drop the tapped marker once the camera reports the new spot position (or after 2.5 s).
            var tap = s.focusPoint
            if let tp = tap {
                let confirmed = focus?.spot.map { abs($0.x - tp.x) < 0.03 && abs($0.y - tp.y) < 0.03 } ?? false
                if confirmed || Date().timeIntervalSince(tapAt) > 2.5 { tap = nil }
            }
            if s.focusMode != fm { s.focusMode = fm }
            if s.settings != settings { s.settings = settings }
            if s.camFocus != focus { s.camFocus = focus }
            if s.focusPoint != tap { s.focusPoint = tap }
        }
    }

    // ------------------------------------------------------------------ actions

    private func action(_ block: @escaping (SonyCamera) throws -> Void) {
        guard let cam = camera else { return }
        actions.async { [weak self] in
            do { try block(cam) } catch {
                DebugLog.d("Action failed: \(error.localizedDescription)")
                self?.withCam { cam.recover() }
                self?.ui { $0.toast = "Camera didn't respond, try again"; $0.busy = nil }
            }
        }
    }

    /// Polls for focus lock, holding the USB lock only for each short command so live view keeps flowing.
    private func waitForFocus(_ cam: SonyCamera, _ maxS: Double) throws -> Bool {
        let start = Date()
        while Date().timeIntervalSince(start) < maxS {
            Thread.sleep(forTimeInterval: 0.08)
            try withCam { try cam.refreshProps() }
            applyProps(cam)
            // Ignore the first ~150 ms: the previous lock state can still be reported.
            if Date().timeIntervalSince(start) > 0.15 && cam.isFocusLocked { return true }
        }
        return false
    }

    /// Tap-to-focus: `p` is a fraction (0..1) of the live view frame.
    func focus(at p: CGPoint) {
        action { [weak self] cam in
            guard let self, !self.flags.shooting else { return }
            self.flags.tapAt = Date()
            self.ui { $0.focusPoint = p; $0.focusState = .focusing }
            if cam.isManualFocus {
                self.ui { $0.focusState = .idle; $0.toast = "Camera is in MF – switch the focus mode to AF to tap-focus." }
                return
            }
            try self.withCam {
                try cam.setAfPoint(p.x, p.y)
                try cam.halfPress(true)
            }
            defer { _ = try? self.withCam { try cam.halfPress(false) } }
            let ok = try self.waitForFocus(cam, 3)
            self.showFocusResult(ok)
        }
    }

    func autoFocus() {
        action { [weak self] cam in
            guard let self, !self.flags.shooting, !cam.isManualFocus else { return }
            self.ui { $0.focusState = .focusing }
            try self.withCam { try cam.halfPress(true) }
            defer { _ = try? self.withCam { try cam.halfPress(false) } }
            let ok = try self.waitForFocus(cam, 3)
            self.showFocusResult(ok)
        }
    }

    /// The camera drops back to "idle" on release; keep the result visible for a moment.
    private func showFocusResult(_ ok: Bool) {
        let result: FocusState = ok ? .focused : .failed
        ui { s in
            s.focusState = result
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { if s.focusState == result { s.focusState = .idle } }
        }
    }

    /// Picks an exact value for a list setting (WB, focus mode, AF area) in one tap.
    func setOption(_ code: Int, _ value: Int64) {
        action { [weak self] cam in
            guard let self else { return }
            try self.withCam { try cam.setValue(code, value) }
            for _ in 0..<10 {
                Thread.sleep(forTimeInterval: 0.15)
                try self.withCam { try cam.refreshProps() }
                self.applyProps(cam)
                if cam.props[code] == value { return }
            }
        }
    }

    func select(_ code: Int) { selected = selected == code ? nil : code }

    /// ‹ / › on a setting. Dials move one notch; list settings move to the neighbouring entry.
    func adjust(_ code: Int, _ direction: Int) {
        action { [weak self] cam in
            guard let self else { return }
            if Exposure.isList(code) {
                guard let info = cam.propInfo[code], let list = info.enumValues, !list.isEmpty else { return }
                let idx = max(0, list.firstIndex(of: info.current) ?? 0)
                let next = list[((idx + direction) % list.count + list.count) % list.count]
                try self.withCam { try cam.setValue(code, next) }
            } else {
                try self.withCam { try cam.step(code, direction) }
            }
            // Refresh quickly so the new value shows without waiting for the next poll.
            for _ in 0..<(code == Sony.DPC_FOCUS_MODE ? 10 : 6) {
                Thread.sleep(forTimeInterval: 0.15)
                try self.withCam { try cam.refreshProps() }
                self.applyProps(cam)
            }
        }
    }

    /// Half-press, wait for focus (live view keeps running), full-press, release.
    func shoot() {
        guard busy == nil else { return }
        action { [weak self] cam in
            guard let self, !self.flags.shooting else { return }
            self.flags.shooting = true
            defer { self.flags.shooting = false }
            self.ui { $0.busy = "Focusing…" }
            try self.withCam { try cam.halfPress(true) }
            let focused = cam.isManualFocus ? true : try self.waitForFocus(cam, 3)
            DebugLog.d("Shoot: focus \(focused ? "locked" : "not confirmed")")
            self.ui { $0.busy = "Shooting…" }
            // The body stalls live view requests while exposing and writing; pause them briefly.
            self.flags.liveViewPausedUntil = Date().addingTimeInterval(2.5)
            try self.withCam { try cam.fullPress(true) }
            Thread.sleep(forTimeInterval: 0.3)
            try self.withCam { try cam.fullPress(false); try cam.halfPress(false) }
            self.awaitingSince = Date()
            self.ui { $0.busy = "Waiting for image…" }
        }
    }
}
