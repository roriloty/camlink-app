import SwiftUI

/// Upload settings in the Android layout (SettingsScreen.kt): FTP server, Google Drive, Editing and upload,
/// Subfolders, On the phone, with a pinned Save button and a "To FTP + Google Drive · auto-edit" summary.
struct SettingsScreen: View {
    let onSwitchMode: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var s = UploadSettingsStore.shared.settings
    @State private var testing = false
    @State private var testResult: (ok: Bool, text: String)?
    @State private var signingIn = false
    @State private var driveError: String?
    @State private var pickFolder = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(Self.summary(s)).font(.subheadline).foregroundStyle(Theme.dim)
                }

                Section {
                    Toggle(isOn: $s.enabled) { title("FTP server", "Send photos to your own server") }
                    if s.enabled {
                        TextField("Server (host name or IP)", text: $s.host).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                        HStack {
                            TextField("Port", value: $s.port, format: .number.grouping(.never)).keyboardType(.numberPad).frame(width: 80)
                            TextField("Folder, e.g. /photos", text: $s.directory).textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                        TextField("Username (blank = anonymous)", text: $s.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField("Password", text: $s.password)
                        Toggle(isOn: $s.tls) { title("Use FTPS (TLS)", "Encrypted login and transfer, if your server supports it") }
                        Button {
                            testing = true; testResult = nil
                            let copy = s
                            Task {
                                do { let r = try await blocking { try FTPClient.test(copy) }; testResult = (true, r) }
                                catch { testResult = (false, error.localizedDescription) }
                                testing = false
                            }
                        } label: { HStack { Text("Test connection"); if testing { Spacer(); ProgressView() } } }
                        .disabled(testing || s.host.isEmpty)
                        if let r = testResult { Text(r.text).font(.footnote).foregroundStyle(r.ok ? Theme.good : Theme.bad) }
                    }
                } footer: { if s.enabled { Text("Passive mode (iOS can't use active mode).") } }

                Section {
                    Toggle(isOn: $s.driveEnabled) { title("Google Drive", "Into a CamLink folder in your Drive (move it into a shared drive if you like)") }
                    if s.driveEnabled {
                        if !DriveAuth.configured {
                            Text("Google Drive isn't available in this build (no iOS OAuth client ID).").font(.footnote).foregroundStyle(Theme.bad)
                        } else if s.driveAccount.isEmpty {
                            Button { signIn() } label: { HStack { Text("Sign in with Google"); if signingIn { Spacer(); ProgressView() } } }.disabled(signingIn)
                        } else {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(s.driveAccount).lineLimit(1)
                                    Text("Signed in").font(.caption).foregroundStyle(Theme.good)
                                }
                                Spacer()
                                Button("Switch") { signIn() }.buttonStyle(.borderless).disabled(signingIn)
                                Button("Sign out") {
                                    Task { await DriveAuth.signOut() }
                                    s.driveAccount = ""; s.driveFolderId = ""; s.driveFolderPath = ""
                                }
                                .buttonStyle(.borderless).foregroundStyle(Theme.dim).padding(.leading, 8)
                            }
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Upload folder").font(.caption).foregroundStyle(Theme.dim)
                                    Text(s.driveFolderPath.isEmpty ? "Not chosen yet: tap Choose to create one" : s.driveFolderPath)
                                        .foregroundStyle(s.driveFolderId.isEmpty ? Theme.accent : .white).lineLimit(2)
                                }
                                Spacer()
                                Button(s.driveFolderId.isEmpty ? "Choose" : "Change") { pickFolder = true }.buttonStyle(.bordered)
                            }
                        }
                        if let e = driveError { Text(e).font(.footnote).foregroundStyle(Theme.bad) }
                    }
                }

                Section("Editing and upload") {
                    Picker("Upload", selection: $s.filter) {
                        Text("All files").tag(UploadFilter.all)
                        Text("JPEG only").tag(UploadFilter.jpegOnly)
                        Text("RAW only").tag(UploadFilter.rawOnly)
                    }
                    .pickerStyle(.segmented)
                    Toggle(isOn: $s.autoEdit) {
                        title("Auto-edit before upload", "Your Lightroom look, learned from your newest edits. Photos on the phone stay original; RAW is sent as is.")
                    }
                    Toggle(isOn: $s.saveEditedLocally) {
                        title("Save edited copy to phone", "Every JPEG is also edited and saved to the CamLink album in Photos, even without uploading. The original stays too.")
                    }
                    Toggle(isOn: $s.uploadOriginalToo) {
                        title("Also upload the unedited original", s.autoEdit ? "Send both versions of every JPEG" : "Only applies when auto-edit is on")
                    }
                    .disabled(!s.autoEdit)
                }

                Section {
                    Toggle(isOn: $s.originalSubfolder) { title("Originals in a subfolder", Self.where(s, s.originalSub())) }
                    if s.originalSubfolder { TextField("Originals subfolder name", text: $s.originalFolderName) }
                    Toggle(isOn: $s.editedSubfolder) { title("Edited in a subfolder", Self.where(s, s.editedSub())) }.disabled(!s.autoEdit)
                    if s.editedSubfolder && s.autoEdit { TextField("Edited subfolder name", text: $s.editedFolderName) }
                } header: { Text("Subfolders") } footer: {
                    Text("Inside the FTP folder and the Drive folder. If both versions end up together, the edited one is named …_edited.JPG.")
                }

                Section("On the phone") {
                    Toggle(isOn: $s.saveToPhotos) {
                        title("Add photos to the Photos library", "Originals are always kept in Files › On My iPhone/iPad › CamLink; this also puts them in the CamLink album in Photos.")
                    }
                    Toggle(isOn: $s.deleteAfterUpload) {
                        title("Delete after upload", "Removes the photo from CamLink's folder once every version reached every destination. Can't be undone; the camera's card keeps its copy if Save Dest. is PC+Camera. Copies in Photos stay.")
                    }
                }

                Section {
                    Button("Change mode") { save(); onSwitchMode() }
                    NavigationLink("Debug log") { DebugLogView() }
                    Link("Privacy policy", destination: URL(string: "https://roriloty.github.io/camlink-app/privacy.html")!)
                    Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")").foregroundStyle(Theme.dim)
                } footer: {
                    Text("The FTP password and Google sign-in are stored in the iOS Keychain.")
                }
            }
            .navigationTitle("Upload")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .safeAreaInset(edge: .bottom) {
                Button { save(); dismiss() } label: { Text("Save").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6) }
                    .buttonStyle(.borderedProminent)
                    .padding(.horizontal, 16).padding(.vertical, 8)
                    .background(.bar)
            }
            .sheet(isPresented: $pickFolder) {
                DriveFolderPicker(account: s.driveAccount) { id, path in
                    s.driveFolderId = id; s.driveFolderPath = path; pickFolder = false
                }
            }
        }
    }

    private func title(_ t: String, _ sub: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(t)
            Text(sub).font(.caption).foregroundStyle(Theme.dim)
        }
    }

    /// "FTP + Drive · auto-edit" style one-liner for the header.
    static func summary(_ s: UploadSettings) -> String {
        let dest = [s.enabled ? "FTP" : nil, s.driveEnabled ? "Google Drive" : nil].compactMap { $0 }
        if dest.isEmpty { return "Off — photos stay on the phone only" }
        return "To " + dest.joined(separator: " + ") + (s.autoEdit ? " · auto-edit" : "")
    }

    /// Where a version lands, e.g. "FTP /photos/Edited · Drive Shoot / Edited".
    static func `where`(_ s: UploadSettings, _ sub: String) -> String {
        var parts: [String] = []
        if s.enabled {
            let dir = s.directory.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            parts.append("FTP /" + [dir, sub].filter { !$0.isEmpty }.joined(separator: "/"))
        }
        if s.driveEnabled {
            let base = s.driveFolderPath.isEmpty ? "(folder)" : s.driveFolderPath
            parts.append("Drive " + (sub.isEmpty ? base : "\(base) / \(sub)"))
        }
        return parts.isEmpty ? "Turn on FTP or Google Drive above" : parts.joined(separator: " · ")
    }

    private func save() {
        UploadSettingsStore.shared.save(s)
        UploadQueue.shared.poke()
    }

    private func signIn() {
        signingIn = true; driveError = nil
        Task { @MainActor in
            do {
                let email = try await DriveAuth.signIn()
                if email != s.driveAccount { s.driveFolderId = ""; s.driveFolderPath = "" }
                s.driveAccount = email
            } catch { driveError = error.localizedDescription }
            signingIn = false
        }
    }
}

/// Picks the Drive folder to upload into. With drive.file CamLink only sees folders it created itself.
struct DriveFolderPicker: View {
    let account: String
    let onPick: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var folders: [DriveAPI.Folder]?
    @State private var error: String?
    @State private var reload = 0
    @State private var newName = "CamLink"
    @State private var askName = false
    @State private var creating = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("CamLink can only see folders it created. To upload into a shared drive or share with others, move or share the folder in Google Drive. CamLink keeps access to it.")
                        .font(.footnote).foregroundStyle(Theme.dim)
                }
                if let error {
                    Section { Text(error).foregroundStyle(Theme.bad); Button("Try again") { reload += 1 } }
                } else if let folders {
                    if folders.isEmpty { Text("No CamLink folders yet. Create one below.").foregroundStyle(Theme.dim) }
                    ForEach(folders) { f in
                        Button { onPick(f.id, f.name) } label: {
                            HStack { Image(systemName: "folder.fill").foregroundStyle(Theme.accent); Text(f.name).foregroundStyle(.white); Spacer(); Text("Use").foregroundStyle(Theme.accent) }
                        }
                    }
                } else {
                    HStack { Spacer(); ProgressView(); Spacer() }
                }
                Section {
                    Button { askName = true } label: { HStack { Text("+ New folder in My Drive"); if creating { Spacer(); ProgressView() } } }.disabled(creating)
                }
            }
            .navigationTitle("Upload folder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .task(id: reload) {
                folders = nil; error = nil
                do { folders = try await DriveAPI.call { try await DriveAPI.appFolders($0) } } catch { self.error = error.localizedDescription }
            }
            .alert("New folder in My Drive", isPresented: $askName) {
                TextField("Folder name", text: $newName)
                Button("Create") {
                    let n = newName.trimmingCharacters(in: .whitespaces)
                    guard !n.isEmpty else { return }
                    creating = true
                    Task {
                        do { let id = try await DriveAPI.call { try await DriveAPI.createFolder($0, parent: "root", name: n) }; onPick(id, n) }
                        catch { self.error = error.localizedDescription }
                        creating = false
                    }
                }
                Button("Cancel", role: .cancel) {}
            }
        }
    }
}

struct DebugLogView: View {
    @State private var text = DebugLog.text
    var body: some View {
        ScrollView {
            Text(text.isEmpty ? "(empty)" : text).font(.system(size: 11, design: .monospaced)).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading).padding()
        }
        .navigationTitle("Debug log")
        .toolbar {
            ToolbarItemGroup {
                Button("Clear") { DebugLog.clear(); text = "" }
                ShareLink(item: DebugLog.text) { Image(systemName: "square.and.arrow.up") }
            }
        }
        .refreshable { text = DebugLog.text }
    }
}
