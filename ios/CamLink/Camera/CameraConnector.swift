import Foundation
import ImageCaptureCore
import CamLinkCore

/// Finds the camera on the USB port (ImageCaptureCore's device browser), asks iOS for permission to control it,
/// opens ImageCaptureCore's session and hands out a `PtpTransport` once the camera is ready.
final class CameraConnector: NSObject, ObservableObject, ICDeviceBrowserDelegate, ICCameraDeviceDelegate {
    enum State: Equatable { case idle, needsPermission, denied, searching, found(String), opening(String), ready(String), failed(String) }

    @Published private(set) var state: State = .idle
    private let browser = ICDeviceBrowser()
    private(set) var camera: ICCameraDevice?
    private(set) var transport: PtpTransport?
    private var readyWaiters: [(Result<PtpTransport, Error>) -> Void] = []
    private var readyTimer: Timer?
    /// Called on the main thread when the camera is unplugged or the session closes.
    var onDisconnect: (() -> Void)?
    /// Called on the main thread when a camera appears (to connect automatically).
    var onCameraFound: (() -> Void)?

    override init() {
        super.init()
        browser.delegate = self
    }

    // ------------------------------------------------------------------ browsing + permission

    func start() {
        DebugLog.d("Device browser: control \(browser.controlAuthorizationStatus.rawValue), contents \(browser.contentsAuthorizationStatus.rawValue)")
        switch browser.controlAuthorizationStatus {
        case .authorized: beginBrowsing()
        case .denied, .restricted: state = .denied
        default:
            state = .needsPermission
            requestPermission()
        }
    }

    func requestPermission() {
        browser.requestControlAuthorization { [weak self] status in
            DispatchQueue.main.async {
                DebugLog.d("Camera control permission: \(status.rawValue)")
                guard let self else { return }
                if status == .authorized { self.beginBrowsing() } else if status == .notDetermined {
                    // iOS 26.1 beta never prompted; browsing anyway lets a later iOS update or Settings toggle work.
                    self.state = .needsPermission
                    self.beginBrowsing()
                } else { self.state = .denied }
            }
        }
    }

    private func beginBrowsing() {
        if case .ready = state { return }
        if !browser.isBrowsing { browser.start() }
        if camera == nil { state = .searching }
    }

    func stop() {
        disconnect()
        if browser.isBrowsing { browser.stop() }
        state = .idle
    }

    func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
        DebugLog.d("Device found: \(device.name ?? "?") (\(device.transportType ?? "?"))")
        guard let cam = device as? ICCameraDevice, camera == nil else { return }
        camera = cam
        cam.delegate = self
        state = .found(cam.name ?? "Camera")
        onCameraFound?()
    }

    func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
        DebugLog.d("Device removed: \(device.name ?? "?")")
        if device === camera { lost() }
    }

    // ------------------------------------------------------------------ session

    /// Opens the session; `done` is called on the main thread once PTP commands can be sent.
    func open(_ done: @escaping (Result<PtpTransport, Error>) -> Void) {
        guard let cam = camera else {
            done(.failure(PtpError(Ptp.RC_GENERAL_ERROR, "No camera connected"))); return
        }
        if let t = transport, case .ready = state { done(.success(t)); return }
        readyWaiters.append(done)
        if case .opening = state { return }
        state = .opening(cam.name ?? "Camera")
        DebugLog.d("Opening session with \(cam.name ?? "camera")")
        if cam.hasOpenSession { becomeReady(cam) } else { cam.requestOpenSession() }
    }

    func disconnect() {
        readyTimer?.invalidate(); readyTimer = nil
        if let cam = camera, cam.hasOpenSession { cam.requestCloseSession() }
        transport = nil
        if let cam = camera { state = .found(cam.name ?? "Camera") }
    }

    private func lost() {
        readyTimer?.invalidate(); readyTimer = nil
        camera = nil; transport = nil
        let waiters = readyWaiters; readyWaiters = []
        waiters.forEach { $0(.failure(PtpError(Ptp.RC_GENERAL_ERROR, "Camera disconnected"))) }
        state = browser.isBrowsing ? .searching : .idle
        onDisconnect?()
    }

    private func becomeReady(_ cam: ICCameraDevice) {
        readyTimer?.invalidate(); readyTimer = nil
        guard transport == nil || readyWaiters.isEmpty == false else { return }
        let t = transport ?? PtpTransport(camera: cam)
        transport = t
        state = .ready(cam.name ?? "Camera")
        DebugLog.d("Camera ready (capabilities: \(cam.capabilities.joined(separator: ", ")))")
        let waiters = readyWaiters; readyWaiters = []
        waiters.forEach { $0(.success(t)) }
    }

    private func fail(_ message: String) {
        readyTimer?.invalidate(); readyTimer = nil
        state = .failed(message)
        let waiters = readyWaiters; readyWaiters = []
        waiters.forEach { $0(.failure(PtpError(Ptp.RC_GENERAL_ERROR, message))) }
    }

    // ICDeviceDelegate
    func didRemove(_ device: ICDevice) { if device === camera { lost() } }

    func device(_ device: ICDevice, didOpenSessionWithError error: Error?) {
        if let error {
            DebugLog.d("Open session failed: \(error.localizedDescription) (\((error as NSError).code))")
            fail("Could not open the camera: \(error.localizedDescription)")
            return
        }
        DebugLog.d("Session open; waiting for the camera to become ready")
        // iPadOS only accepts PTP commands after "device ready". In PC Remote mode the body has no card catalog to
        // send, so don't wait forever for it.
        readyTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in
            guard let self, let cam = self.camera else { return }
            DebugLog.d("No ready callback after 4 s, continuing")
            self.becomeReady(cam)
        }
    }

    func device(_ device: ICDevice, didCloseSessionWithError error: Error?) {
        DebugLog.d("Session closed\(error.map { ": \($0.localizedDescription)" } ?? "")")
        if transport != nil { transport = nil; onDisconnect?() }
        if let cam = camera { state = .found(cam.name ?? "Camera") }
    }

    func deviceDidBecomeReady(_ device: ICDevice) {
        DebugLog.d("deviceDidBecomeReady")
        if let cam = device as? ICCameraDevice, case .opening = state { becomeReady(cam) }
    }

    func device(_ device: ICDevice, didEncounterError error: Error?) {
        DebugLog.d("Device error: \(error?.localizedDescription ?? "?")")
    }

    // ICCameraDeviceDelegate
    func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) {
        DebugLog.d("Camera catalog ready (\(device.mediaFiles?.count ?? 0) files)")
        if case .opening = state { becomeReady(device) }
    }

    func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent eventData: Data) {
        transport?.handleEvent(eventData)
    }

    func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {
        // Normal PTP "object added": this ImageCaptureCore catalog isn't used (Sony's 0xC201 event drives downloads).
        DebugLog.d("Catalog: \(items.count) item(s) added")
    }
    func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {}
    func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?, for item: ICCameraItem, error: Error?) {}
    func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?, for item: ICCameraItem, error: Error?) {}
    func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}
    func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {}
    func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) { DebugLog.d("Camera access restriction removed") }
    func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) { DebugLog.d("Camera access restricted (locked?)") }
}
