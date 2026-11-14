import SwiftUI

/// Upload settings (FTP / FTPS, Google Drive, what goes where), plus mode switch and the debug log.
struct SettingsScreen: View {
    let onSwitchMode: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var s = UploadSettingsStore.shared.settings
    @State private var testing = false
    @State private var testResult: (ok: Bool, text: String)?
    @State private var signingIn = false
    @State private var driveError: String?
    @State private var pickFolder = false
    @AppStorage("mode") private var mode = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Text("Mode")
                        Spacer()
                        Text(mode == AppMode.upload.rawValue ? "Upload only" : "Camera control").foregroundStyle(Theme.dim)
                    }
                    Button("Switch mode…") { save(); onSwitchMode() }
                    Toggle("Also add photos to the Photos library", isOn: $s.saveToPhotos)
                } footer: {
                    Text("Originals are always kept in Files › On My iPhone/iPad › CamLink.")
                }

                Section("FTP / FTPS") {
                    Toggle("Upload to an FTP server", isOn: $s.enabled)
                    if s.enabled {
                        TextField("Server (host name or IP)", text: $s.host).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                        HStack {
                            TextField("Port", value: $s.port, format: .number.grouping(.never)).keyboardType(.numberPad).frame(width: 80)
                            TextField("Folder, e.g. /photos", text: $s.directory).textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                        TextField("Username (blank = anonymous)", text: $s.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField("Password", text: $s.password)
                        Toggle("FTPS (explicit TLS)", isOn: $s.tls)
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
                }

                Section {
                    Toggle("Upload to Google Drive", isOn: $s.driveEnabled)
                    if s.driveEnabled {
                        if !DriveAuth.configured {
                            Text("Google Drive isn't available in this build yet (it needs an iOS OAuth client ID).").font(.footnote).foregroundStyle(Theme.bad)
                        } else if s.driveAccount.isEmpty {
                            Button { signIn() } label: { HStack { Text("Sign in with Google"); if signingIn { Spacer(); ProgressView() } } }.disabled(signingIn)
                        } else {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(s.driveAccount)
                                    Text("Signed in").font(.caption).foregroundStyle(Theme.good)
                                }
                                Spacer()
                                Button("Switch") { signIn() }.buttonStyle(.borderless).disabled(signingIn)
                            }
                            Button { pickFolder = true } label: {
                                HStack {
                                    Text("Upload folder")
                                    Spacer()
                                    Text(s.driveFolderPath.isEmpty ? "Choose…" : s.driveFolderPath).foregroundStyle(s.driveFolderPath.isEmpty ? Theme.accent : Theme.dim)
                                }
                            }
                            Button("Sign out", role: .destructive) {
                                Task { await DriveAuth.signOut() }
                                s.driveAccount = ""; s.driveFolderId = ""; s.driveFolderPath = ""
                            }
                        }
                        if let e = driveError { Text(e).font(.footnote).foregroundStyle(Theme.bad) }
                    }
                } header: { Text("Google Drive") } footer: {
                    Text("CamLink only gets access to the folders and files it creates in your Drive (drive.file). You can move the folder into a shared drive or share it; CamLink keeps access.")
                }

                Section("What to upload") {
                    Picker("Files", selection: $s.filter) {
                        Text("JPEG and RAW").tag(UploadFilter.all)
                        Text("JPEG only").tag(UploadFilter.jpegOnly)
                        Text("RAW only").tag(UploadFilter.rawOnly)
                    }
                    Toggle("Auto-edit JPEGs (Lightroom style)", isOn: $s.autoEdit)
                    if s.autoEdit { Toggle("Also upload the original", isOn: $s.uploadOriginalToo) }
                    Toggle("Originals in a subfolder", isOn: $s.originalSubfolder)
                    if s.originalSubfolder { TextField("Originals subfolder name", text: $s.originalFolderName) }
                    if s.autoEdit {
                        Toggle("Edited copies in a subfolder", isOn: $s.editedSubfolder)
                        if s.editedSubfolder { TextField("Edited subfolder name", text: $s.editedFolderName) }
                    }
                    Toggle("Delete from this device after upload", isOn: $s.deleteAfterUpload)
                }

                Section("Help") {
                    NavigationLink("Debug log") { DebugLogView() }
                    Link("Privacy policy", destination: URL(string: "https://roriloty.github.io/camlink-app/privacy.html")!)
                    Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")").foregroundStyle(Theme.dim)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { save(); dismiss() }.bold() }
            }
            .sheet(isPresented: $pickFolder) {
                DriveFolderPicker(account: s.driveAccount) { id, path in
                    s.driveFolderId = id; s.driveFolderPath = path; pickFolder = false
                }
            }
        }
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
