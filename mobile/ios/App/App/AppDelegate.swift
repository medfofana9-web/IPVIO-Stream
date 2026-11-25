import UIKit
import AVFoundation
import WebKit
import Capacitor

// Fof TV for iPhone: the shared Fof TV screens (desktop/renderer) in a Capacitor web view, with native video
// (inline, full screen, AirPlay, picture in picture) and a small native plugin for the sideload expiry reminder.

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Sound even with the silent switch on, and keep playing over AirPlay / picture in picture
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [])
        try? AVAudioSession.sharedInstance().setActive(true)
        return true
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
    }
}

// Main screen: Capacitor's view controller with video playback allowed inline, without a tap, and over AirPlay
class FofViewController: CAPBridgeViewController {
    override open func webViewConfiguration(for instanceConfiguration: InstanceConfiguration) -> WKWebViewConfiguration {
        let c = super.webViewConfiguration(for: instanceConfiguration)
        c.allowsInlineMediaPlayback = true
        c.mediaTypesRequiringUserActionForPlayback = []
        c.allowsAirPlayForMediaPlayback = true
        c.allowsPictureInPictureMediaPlayback = true
        return c
    }

    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(FofNativePlugin())
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }
}

// window.Capacitor.Plugins.FofNative
@objc(FofNativePlugin)
public class FofNativePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "FofNativePlugin"
    public let jsName = "FofNative"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "info", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openSideStore", returnType: CAPPluginReturnPromise)
    ]

    // App version and the date the signing profile runs out (free Apple IDs: 7 days after the last refresh)
    @objc func info(_ call: CAPPluginCall) {
        var out: [String: Any] = [
            "version": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "",
            "build": Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        ]
        if let d = FofNativePlugin.profileExpiry() { out["expires"] = ISO8601DateFormatter().string(from: d) }
        call.resolve(out)
    }

    @objc func openSideStore(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            for s in ["sidestore://", "altstore://"] {
                if let u = URL(string: s), UIApplication.shared.canOpenURL(u) { UIApplication.shared.open(u); break }
            }
            call.resolve()
        }
    }

    static func profileExpiry() -> Date? {
        guard let path = Bundle.main.path(forResource: "embedded", ofType: "mobileprovision"),
              let data = FileManager.default.contents(atPath: path),
              let text = String(data: data, encoding: .isoLatin1),
              let start = text.range(of: "<?xml"), let end = text.range(of: "</plist>") else { return nil }
        let xml = String(text[start.lowerBound..<end.upperBound])
        guard let plist = try? PropertyListSerialization.propertyList(from: Data(xml.utf8), options: [], format: nil) as? [String: Any] else { return nil }
        return plist["ExpirationDate"] as? Date
    }
}
