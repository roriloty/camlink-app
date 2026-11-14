// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CamLinkCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "CamLinkCore", targets: ["CamLinkCore"])],
    targets: [
        .target(name: "CamLinkCore"),
        .testTarget(name: "CamLinkCoreTests", dependencies: ["CamLinkCore"], resources: [.copy("Golden")]),
    ]
)
