import Foundation
import ImageCaptureCore
import CamLinkCore

/// PTP over USB through Apple's ImageCaptureCore (iOS has no raw USB access). ImageCaptureCore owns the USB
/// pipes and the PTP session; we pass our own operations through `requestSendPTPCommand`, the iOS equivalent of
/// Android's bulk transfers in PtpUsb.kt.
///
/// All transactions are blocking and must be called from one background thread at a time (never the main thread:
/// ImageCaptureCore delivers its completions there).
final class PtpTransport {
    let camera: ICCameraDevice
    private var transactionId: UInt32 = 0
    /// Called for every PTP event: (event code, params). Called on the main thread.
    var onEvent: ((Int, [UInt32]) -> Void)?
    private var loggedFirstResponse = false

    init(camera: ICCameraDevice) { self.camera = camera }

    /// Runs one PTP transaction.
    /// - Parameter sendData: payload for an outgoing data phase, or nil.
    func transact(_ op: Int, _ params: [UInt32] = [], sendData: Data? = nil, timeout: TimeInterval = 5) throws -> PtpResponse {
        precondition(!Thread.isMainThread, "PTP transactions block; run them off the main thread")
        transactionId &+= 1
        let cmd = PtpCodec.command(op: op, transactionId: transactionId, params: params)
        let sem = DispatchSemaphore(value: 0)
        var inData = Data(), respData = Data(), err: Error?
        camera.requestSendPTPCommand(cmd, outData: sendData) { data, response, error in
            inData = data; respData = response; err = error
            sem.signal()
        }
        if sem.wait(timeout: .now() + timeout) == .timedOut {
            DebugLog.d(String(format: "PTP 0x%04X timed out after %.0f s", op, timeout))
            throw PtpError(Ptp.RC_GENERAL_ERROR, String(format: "Camera did not answer (op 0x%04X)", op))
        }
        if let err {
            let ns = err as NSError
            DebugLog.d(String(format: "PTP 0x%04X failed: %@ (%d)", op, ns.localizedDescription, ns.code))
            if ns.code == -21249 {
                throw PtpError(Ptp.RC_ACCESS_DENIED, "iOS didn't allow CamLink to control the camera. Allow camera control in Settings › Privacy & Security › Cameras (or reinstall and tap Allow).")
            }
            throw PtpError(Ptp.RC_GENERAL_ERROR, "USB error: \(ns.localizedDescription) (\(ns.code))")
        }
        if !loggedFirstResponse {
            loggedFirstResponse = true
            DebugLog.d("First PTP reply: data \(inData.count) bytes [\(PtpCodec.hex(inData, max: 16))], response [\(PtpCodec.hex(respData))]")
        }
        guard let r = PtpCodec.parseResponse(respData) else {
            DebugLog.d(String(format: "PTP 0x%04X: unexpected response [%@]", op, PtpCodec.hex(respData)))
            throw PtpError(Ptp.RC_GENERAL_ERROR, "Unexpected PTP response")
        }
        return PtpResponse(code: r.code, params: r.params, data: inData.isEmpty ? nil : PtpCodec.payload(inData))
    }

    /// Like `transact` but throws if the response is not OK.
    @discardableResult
    func call(_ op: Int, _ params: [UInt32] = [], sendData: Data? = nil, timeout: TimeInterval = 5) throws -> PtpResponse {
        let r = try transact(op, params, sendData: sendData, timeout: timeout)
        if !r.ok { throw PtpError(r.code, String(format: "PTP op 0x%04X failed: 0x%04X", op, r.code)) }
        return r
    }

    /// The Android app clears halted USB endpoints here. ImageCaptureCore does its own pipe recovery,
    /// so all that's left is to give the body a moment (it stalls while writing a picture).
    func recover() {
        DebugLog.d("Recover: waiting for the camera")
        Thread.sleep(forTimeInterval: 0.3)
    }

    func handleEvent(_ data: Data) {
        guard let e = PtpCodec.parseEvent(data) else {
            DebugLog.d("Event data: [\(PtpCodec.hex(data))]")
            return
        }
        DebugLog.d(String(format: "Event 0x%04X %@", e.code, e.params.map { String(format: "0x%X", $0) }.joined(separator: ", ")))
        onEvent?(e.code, e.params)
    }
}
