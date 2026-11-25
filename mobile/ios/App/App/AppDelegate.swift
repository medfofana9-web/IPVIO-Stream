import UIKit
import AVFoundation
import AVKit
import WebKit
import Capacitor
import MobileVLCKit

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
        CAPPluginMethod(name: "openSideStore", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "playNative", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "playVlc", returnType: CAPPluginReturnPromise)
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

    // Apple's own player: keeps playing over AirPlay and in picture in picture when Fof TV is closed or the phone is locked
    @objc func playNative(_ call: CAPPluginCall) {
        guard let s = call.getString("url"), let url = URL(string: s) else { call.reject("bad url"); return }
        let start = call.getDouble("start") ?? 0
        let wantAirPlay = call.getBool("airplay") ?? false
        DispatchQueue.main.async {
            let player = AVPlayer(url: url)
            player.allowsExternalPlayback = true
            player.usesExternalPlaybackWhileExternalScreenIsActive = true
            let vc = AVPlayerViewController()
            vc.player = player
            vc.allowsPictureInPicturePlayback = true
            vc.canStartPictureInPictureAutomaticallyFromInline = true
            vc.updatesNowPlayingInfoCenter = true
            vc.modalPresentationStyle = .fullScreen
            self.bridge?.viewController?.present(vc, animated: true) {
                if start > 1 { player.seek(to: CMTime(seconds: start, preferredTimescale: 600)) }
                player.play()
                // Open the AirPlay list right away (same as tapping AirPlay in Apple's player)
                if wantAirPlay {
                    let picker = AVRoutePickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
                    vc.view.addSubview(picker)
                    for case let b as UIButton in picker.subviews { b.sendActions(for: .touchUpInside) }
                }
            }
            call.resolve()
        }
    }

    // VLC player for .mkv / .avi (Apple's player cannot open them). Resolves when closed: position and duration in
    // seconds, ended (played to the end), failed (did not start)
    @objc func playVlc(_ call: CAPPluginCall) {
        guard let s = call.getString("url"), let url = URL(string: s) else { call.reject("bad url"); return }
        call.keepAlive = false
        DispatchQueue.main.async {
            let vc = VlcPlayerViewController(url: url, title: call.getString("title") ?? "", start: call.getDouble("start") ?? 0) { pos, dur, ended, failed in
                call.resolve(["position": pos, "duration": dur, "ended": ended, "failed": failed])
            }
            vc.modalPresentationStyle = .fullScreen
            self.bridge?.viewController?.present(vc, animated: true)
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

// Full-screen VLC player with the Fof TV look: tap to show/hide the controls; back and forward 10 s, play/pause,
// a seek bar, time, close. The picture keeps the screen on while it plays.
final class VlcPlayerViewController: UIViewController, VLCMediaPlayerDelegate {
    private let url: URL, titleText: String, start: Double
    private let done: (Double, Double, Bool, Bool) -> Void
    private let player = VLCMediaPlayer()
    private let video = UIView(), controls = UIView(), slider = UISlider(), timeLabel = UILabel(), playButton = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .large)
    private var finished = false, started = false, sliding = false, ended = false
    private var hideTimer: Timer?, tick: Timer?, startWatch: Timer?

    init(url: URL, title: String, start: Double, done: @escaping (Double, Double, Bool, Bool) -> Void) {
        self.url = url; self.titleText = title; self.start = start; self.done = done
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .allButUpsideDown }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        video.frame = view.bounds; video.autoresizingMask = [.flexibleWidth, .flexibleHeight]; video.backgroundColor = .black
        view.addSubview(video)
        spinner.color = .white; spinner.translatesAutoresizingMaskIntoConstraints = false; spinner.startAnimating()
        view.addSubview(spinner)
        NSLayoutConstraint.activate([spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor), spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor)])
        buildControls()
        view.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(toggleControls)))

        let media = VLCMedia(url: url)
        if start > 5 { media.addOption(":start-time=\(Int(start))") }
        media.addOption(":network-caching=3000")
        player.media = media
        player.drawable = video
        player.delegate = self
        player.play()
        UIApplication.shared.isIdleTimerDisabled = true
        tick = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.refresh() }
        // A source that has not shown a picture after 25 s counts as not starting (the next copy is tried)
        startWatch = Timer.scheduledTimer(withTimeInterval: 25, repeats: false) { [weak self] _ in
            guard let self = self, !self.started else { return }
            self.close(failed: true)
        }
        scheduleHide()
    }

    private func makeButton(_ symbol: String, _ size: CGFloat, _ action: Selector) -> UIButton {
        let b = UIButton(type: .system)
        b.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: .semibold)), for: .normal)
        b.tintColor = .white; b.addTarget(self, action: action, for: .touchUpInside); b.translatesAutoresizingMaskIntoConstraints = false
        return b
    }

    private func buildControls() {
        controls.frame = view.bounds; controls.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        controls.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        view.addSubview(controls)
        let close = makeButton("xmark", 22, #selector(closeTapped))
        let title = UILabel(); title.text = titleText; title.textColor = .white; title.font = .boldSystemFont(ofSize: 17); title.translatesAutoresizingMaskIntoConstraints = false
        let back = makeButton("gobackward.10", 30, #selector(back10)), fwd = makeButton("goforward.10", 30, #selector(fwd10))
        playButton.setImage(UIImage(systemName: "pause.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 40, weight: .bold)), for: .normal)
        playButton.tintColor = .white; playButton.addTarget(self, action: #selector(playPause), for: .touchUpInside); playButton.translatesAutoresizingMaskIntoConstraints = false
        slider.minimumTrackTintColor = UIColor(red: 0.9, green: 0.04, blue: 0.08, alpha: 1); slider.maximumTrackTintColor = UIColor(white: 1, alpha: 0.3)
        slider.translatesAutoresizingMaskIntoConstraints = false
        slider.addTarget(self, action: #selector(slideStart), for: .touchDown)
        slider.addTarget(self, action: #selector(slideEnd), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        timeLabel.textColor = .white; timeLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium); timeLabel.translatesAutoresizingMaskIntoConstraints = false
        [close, title, back, playButton, fwd, slider, timeLabel].forEach { controls.addSubview($0) }
        let g = controls.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            close.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 16), close.topAnchor.constraint(equalTo: g.topAnchor, constant: 12),
            title.leadingAnchor.constraint(equalTo: close.trailingAnchor, constant: 16), title.centerYAnchor.constraint(equalTo: close.centerYAnchor),
            title.trailingAnchor.constraint(lessThanOrEqualTo: g.trailingAnchor, constant: -16),
            playButton.centerXAnchor.constraint(equalTo: controls.centerXAnchor), playButton.centerYAnchor.constraint(equalTo: controls.centerYAnchor),
            back.centerYAnchor.constraint(equalTo: playButton.centerYAnchor), back.trailingAnchor.constraint(equalTo: playButton.leadingAnchor, constant: -56),
            fwd.centerYAnchor.constraint(equalTo: playButton.centerYAnchor), fwd.leadingAnchor.constraint(equalTo: playButton.trailingAnchor, constant: 56),
            slider.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 20), slider.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -16),
            timeLabel.leadingAnchor.constraint(equalTo: slider.trailingAnchor, constant: 12), timeLabel.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -20),
            timeLabel.centerYAnchor.constraint(equalTo: slider.centerYAnchor)
        ])
        timeLabel.setContentHuggingPriority(.required, for: .horizontal)
    }

    private func fmt(_ ms: Int32) -> String {
        let s = Int(max(0, ms)) / 1000, h = s / 3600, m = (s % 3600) / 60, x = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, x) : String(format: "%d:%02d", m, x)
    }

    private func refresh() {
        let t = player.time.intValue, len = player.media?.length.intValue ?? 0
        if t > 0 && !started { started = true; spinner.stopAnimating() }
        if !sliding && len > 0 { slider.value = Float(t) / Float(len) }
        timeLabel.text = fmt(t) + " / " + fmt(len)
    }

    @objc private func toggleControls() { UIView.animate(withDuration: 0.2) { self.controls.alpha = self.controls.alpha > 0.5 ? 0 : 1 }; scheduleHide() }
    private func scheduleHide() {
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in
            guard let self = self, self.player.isPlaying, !self.sliding else { return }
            UIView.animate(withDuration: 0.3) { self.controls.alpha = 0 }
        }
    }
    @objc private func playPause() {
        if player.isPlaying { player.pause() } else { player.play() }
        let name = player.isPlaying ? "play.fill" : "pause.fill"
        playButton.setImage(UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: 40, weight: .bold)), for: .normal)
        scheduleHide()
    }
    @objc private func back10() { player.jumpBackward(10); scheduleHide() }
    @objc private func fwd10() { player.jumpForward(10); scheduleHide() }
    @objc private func slideStart() { sliding = true; hideTimer?.invalidate() }
    @objc private func slideEnd() {
        sliding = false
        let len = player.media?.length.intValue ?? 0
        if len > 0 { player.time = VLCTime(int: Int32(Float(len) * slider.value)) }
        scheduleHide()
    }
    @objc private func closeTapped() { close(failed: false) }

    func mediaPlayerStateChanged(_ aNotification: Notification) {
        switch player.state {
        case .error: DispatchQueue.main.async { self.close(failed: !self.started) }
        case .ended: ended = true; DispatchQueue.main.async { self.close(failed: false) }
        default: break
        }
    }

    private func close(failed: Bool) {
        if finished { return }
        finished = true
        let pos = Double(max(0, player.time.intValue)) / 1000, dur = Double(max(0, player.media?.length.intValue ?? 0)) / 1000
        tick?.invalidate(); hideTimer?.invalidate(); startWatch?.invalidate()
        player.delegate = nil
        player.stop()
        UIApplication.shared.isIdleTimerDisabled = false
        dismiss(animated: true) { self.done(pos, dur, self.ended, failed) }
    }
}
