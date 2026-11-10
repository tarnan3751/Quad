import XCTest

/**
 * Hands-on driver for manual QA on the simulator. Skipped unless `QUAD_DRIVER_DIR` is set, e.g.
 *
 *     TEST_RUNNER_QUAD_DRIVER_DIR=/tmp/quad-driver xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
 *         -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:iosAppUITests/QuadDriver
 *
 * then send commands with `scripts/ios-drive.sh <command>` (same directory in `QUAD_DRIVER_DIR`). The driver polls
 * the directory for `cmd.<n>` files, runs each and writes the result to `out.<n>`. Commands:
 *
 *     launch [arg…]        (re)launch the app, optional launch arguments    activate | terminate
 *     tree                 compact accessibility tree (type, label, id, value, frame)
 *     tap <label>          on-screen element whose label or identifier is <label>, buttons first; "<label>#2" for
 *                          the 2nd match of any type
 *     tapc <text>          same, label containing <text>              tapxy <x> <y>    tap at points
 *     longpress <label>    exists <label>      wait <label> [seconds]      gone <label> [seconds]
 *     type <text>          type into the focused element ("\n" types Return)   del [n]   delete n characters
 *     keys <text>          type by tapping on-screen keyboard keys (letters, space; other characters are typed)
 *     key <name>           tap a keyboard key ("Next", "Done", "return")
 *     swipe up|down|left|right [label]     drag <x1> <y1> <x2> <y2> [seconds]     back   (edge swipe back)
 *     pinch <scale> [label]   orient portrait|left|right   home   sleep <seconds>   quit
 *
 * Prefix a command with `@safari`, `@springboard` or `@<bundle id>` to send it to another app (Safari pages, the
 * "Open in Quad?" prompt, system alerts).
 */
final class QuadDriver: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = true
    }

    func testDrive() throws {
        guard let dir = ProcessInfo.processInfo.environment["QUAD_DRIVER_DIR"], !dir.isEmpty else {
            throw XCTSkip("Interactive driver: set TEST_RUNNER_QUAD_DRIVER_DIR to use it")
        }
        let fm = FileManager.default
        try? fm.createDirectory(atPath: dir, withIntermediateDirectories: true)
        write("ready", to: "\(dir)/ready")
        var next = 1
        while true {
            let cmdPath = "\(dir)/cmd.\(next)"
            guard let data = fm.contents(atPath: cmdPath), let line = String(data: data, encoding: .utf8) else {
                Thread.sleep(forTimeInterval: 0.1)
                continue
            }
            let command = line.trimmingCharacters(in: .whitespacesAndNewlines)
            var result: String
            if command == "quit" {
                write("bye", to: "\(dir)/out.\(next)")
                return
            }
            result = run(command)
            write(result, to: "\(dir)/out.\(next)")
            next += 1
        }
    }

    // MARK: - commands

    private func run(_ command: String) -> String {
        var target = app
        var line = command
        if line.hasPrefix("@") {
            let space = line.firstIndex(of: " ") ?? line.endIndex
            let name = String(line[line.index(after: line.startIndex)..<space])
            target = XCUIApplication(bundleIdentifier: bundleId(for: name))
            line = String(line[space...]).trimmingCharacters(in: .whitespaces)
        }
        let verb = line.split(separator: " ", maxSplits: 1).first.map(String.init) ?? ""
        let arg = line.count > verb.count ? String(line.dropFirst(verb.count + 1)) : ""
        let args = arg.split(separator: " ").map(String.init)

        switch verb {
        case "launch":
            app.launchArguments = args
            app.launch()
            return "launched"
        case "activate":
            target.activate()
            return "activated"
        case "terminate":
            target.terminate()
            return "terminated"
        case "tree":
            return tree(of: target)
        case "tap", "tapc", "longpress":
            guard let hit = locate(arg, in: target, contains: verb == "tapc") else { return "ERR not found: \(arg)" }
            let center = point(target, hit.frame.midX, hit.frame.midY)
            if verb == "longpress" { center.press(forDuration: 1.0) } else { center.tap() }
            return "ok \(hit.text)"
        case "tapxy":
            guard args.count == 2, let x = Double(args[0]), let y = Double(args[1]) else { return "ERR tapxy x y" }
            point(target, x, y).tap()
            return "ok"
        case "exists":
            return locate(arg, in: target, contains: false).map { "yes \($0.text)" } ?? "no"
        case "wait":
            let (label, timeout) = labelAndTimeout(args)
            let deadline = Date().addingTimeInterval(timeout)
            while Date() < deadline {
                if let hit = locate(label, in: target, contains: false) { return "ok \(hit.text)" }
                Thread.sleep(forTimeInterval: 0.25)
            }
            return "ERR timeout waiting for \(label)"
        case "gone":
            let (label, timeout) = labelAndTimeout(args)
            let deadline = Date().addingTimeInterval(timeout)
            while Date() < deadline {
                if locate(label, in: target, contains: false) == nil { return "ok" }
                Thread.sleep(forTimeInterval: 0.25)
            }
            return "ERR still there: \(label)"
        case "type":
            guard hasKeyboardFocus(target) else { return "ERR nothing has keyboard focus" }
            target.typeText(arg.replacingOccurrences(of: "\\n", with: "\n"))
            return "ok"
        case "keys":
            guard target.keyboards.count > 0 else { return "ERR keyboard hidden" }
            for ch in arg {
                let lower = String(ch).lowercased()
                let name = ch == " " ? "space" : lower
                let key = target.keyboards.keys[name]
                if ch.isLetter, ch.isUppercase, target.keyboards.buttons["shift"].exists {
                    target.keyboards.buttons["shift"].tap()
                }
                if key.exists { key.tap() } else { target.typeText(String(ch)) }
            }
            return "ok"
        case "del":
            let n = Int(arg) ?? 1
            guard hasKeyboardFocus(target) else { return "ERR nothing has keyboard focus" }
            target.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: n))
            return "ok"
        case "key":
            let key = target.keyboards.buttons[arg]
            guard key.exists else {
                let keys = target.keyboards.buttons.allElementsBoundByIndex.map(\.label)
                return "ERR no key \(arg); keyboard: \(keys.isEmpty ? "hidden" : keys.suffix(8).joined(separator: ","))"
            }
            key.tap()
            return "ok"
        case "swipe":
            let parts = arg.split(separator: " ", maxSplits: 1).map(String.init)
            var area = target.frame
            if parts.count > 1 {
                guard let hit = locate(parts[1], in: target, contains: false) else { return "ERR not found: \(parts[1])" }
                area = hit.frame
            }
            let (cx, cy) = (Double(area.midX), Double(area.midY))
            let (dx, dy) = (Double(area.width) * 0.35, Double(area.height) * 0.3)
            let from: (Double, Double), to: (Double, Double)
            switch parts.first {
            case "up": (from, to) = ((cx, cy + dy), (cx, cy - dy))
            case "down": (from, to) = ((cx, cy - dy), (cx, cy + dy))
            case "left": (from, to) = ((cx + dx, cy), (cx - dx, cy))
            case "right": (from, to) = ((cx - dx, cy), (cx + dx, cy))
            default: return "ERR swipe up|down|left|right [label]"
            }
            point(target, from.0, from.1).press(forDuration: 0.01, thenDragTo: point(target, to.0, to.1), withVelocity: .fast, thenHoldForDuration: 0)
            return "ok"
        case "drag":
            let n = args.compactMap(Double.init)
            guard n.count >= 4 else { return "ERR drag x1 y1 x2 y2 [seconds]" }
            point(target, n[0], n[1]).press(forDuration: n.count > 4 ? n[4] : 0.05, thenDragTo: point(target, n[2], n[3]))
            return "ok"
        case "back":
            let frame = target.frame
            point(target, 1, frame.height * 0.45).press(forDuration: 0.05, thenDragTo: point(target, frame.width * 0.75, frame.height * 0.45))
            return "ok"
        case "pinch":
            let scale = Double(args.first ?? "") ?? 2
            target.pinch(withScale: scale, velocity: scale > 1 ? 2 : -2)
            return "ok"
        case "orient":
            switch arg {
            case "left": XCUIDevice.shared.orientation = .landscapeLeft
            case "right": XCUIDevice.shared.orientation = .landscapeRight
            default: XCUIDevice.shared.orientation = .portrait
            }
            return "ok"
        case "home":
            XCUIDevice.shared.press(.home)
            return "ok"
        case "sleep":
            Thread.sleep(forTimeInterval: Double(arg) ?? 1)
            return "ok"
        default:
            return "ERR unknown command: \(command)"
        }
    }

    // MARK: - helpers

    private func bundleId(for name: String) -> String {
        switch name {
        case "safari": return "com.apple.mobilesafari"
        case "springboard": return "com.apple.springboard"
        case "photos": return "com.apple.mobileslideshow"
        default: return name
        }
    }

    private func labelAndTimeout(_ args: [String]) -> (String, TimeInterval) {
        if args.count > 1, let t = Double(args.last!) { return (args.dropLast().joined(separator: " "), t) }
        return (args.joined(separator: " "), 10)
    }

    private func point(_ target: XCUIApplication, _ x: Double, _ y: Double) -> XCUICoordinate {
        target.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: x, dy: y))
    }

    private struct Hit {
        let frame: CGRect
        let text: String
        let isButton: Bool
    }

    /**
     * Finds an element in one accessibility snapshot (so nothing is re-resolved after the screen changes) by label or
     * identifier, on screen. `label#n` picks the n-th (1-based) match.
     */
    private func locate(_ spec: String, in target: XCUIApplication, contains: Bool) -> Hit? {
        var label = spec
        var index = 1
        var hasIndex = false
        if let hash = spec.lastIndex(of: "#"), let n = Int(spec[spec.index(after: hash)...]) {
            label = String(spec[..<hash])
            index = n
            hasIndex = true
        }
        guard let root = try? target.snapshot() else { return nil }
        let screen = root.frame
        var hits: [Hit] = []
        func walk(_ node: XCUIElementSnapshot) {
            let matches = contains
                ? node.label.localizedCaseInsensitiveContains(label) || node.identifier.localizedCaseInsensitiveContains(label)
                : node.label == label || node.identifier == label
            let f = node.frame
            if matches, f.width > 0, f.height > 0, screen.contains(CGPoint(x: f.midX, y: f.midY)) {
                hits.append(Hit(frame: f, text: "\(typeName(node.elementType)) '\(node.label)' \(rect(f))", isButton: node.elementType == .button))
            }
            node.children.forEach(walk)
        }
        walk(root)
        if !hasIndex, let button = hits.first(where: { $0.isButton }) { return button }
        return hits.count >= index ? hits[index - 1] : nil
    }

    private func hasKeyboardFocus(_ target: XCUIApplication) -> Bool {
        guard let root = try? target.snapshot() else { return false }
        func focused(_ node: XCUIElementSnapshot) -> Bool { node.hasFocus || node.children.contains(where: focused) }
        return focused(root) || target.keyboards.count > 0
    }

    private func tree(of target: XCUIApplication) -> String {
        guard let snapshot = try? target.snapshot() else { return "ERR no snapshot (is the app running?)" }
        var lines: [String] = []
        func walk(_ node: XCUIElementSnapshot, depth: Int) {
            let label = node.label
            let id = node.identifier
            let value = (node.value as? String) ?? (node.value.map { "\($0)" } ?? "")
            let interesting = !label.isEmpty || !id.isEmpty || !value.isEmpty || [.button, .textField, .secureTextField, .switch, .image].contains(node.elementType)
            if interesting {
                var line = String(repeating: " ", count: min(depth, 12)) + typeName(node.elementType)
                if !label.isEmpty { line += " '\(label)'" }
                if !id.isEmpty && id != label { line += " id=\(id)" }
                if !value.isEmpty { line += " value='\(value)'" }
                if !node.isEnabled { line += " disabled" }
                if node.isSelected { line += " selected" }
                if node.hasFocus { line += " focused" }
                line += " " + rect(node.frame)
                lines.append(line)
            }
            for child in node.children { walk(child, depth: interesting ? depth + 1 : depth) }
        }
        walk(snapshot, depth: 0)
        return lines.joined(separator: "\n")
    }

    private func rect(_ r: CGRect) -> String {
        "[\(Int(r.minX)),\(Int(r.minY)) \(Int(r.width))x\(Int(r.height))]"
    }

    private func typeName(_ t: XCUIElement.ElementType) -> String {
        switch t {
        case .application: return "App"
        case .window: return "Window"
        case .button: return "Button"
        case .staticText: return "Text"
        case .textField: return "TextField"
        case .secureTextField: return "SecureField"
        case .textView: return "TextView"
        case .image: return "Image"
        case .switch: return "Switch"
        case .link: return "Link"
        case .cell: return "Cell"
        case .scrollView: return "Scroll"
        case .collectionView: return "Collection"
        case .table: return "Table"
        case .tabBar: return "TabBar"
        case .navigationBar: return "NavBar"
        case .alert: return "Alert"
        case .sheet: return "Sheet"
        case .keyboard: return "Keyboard"
        case .key: return "Key"
        case .slider: return "Slider"
        case .other: return "Other"
        case .webView: return "WebView"
        case .tab: return "Tab"
        case .radioButton: return "Radio"
        case .checkBox: return "Check"
        case .progressIndicator: return "Progress"
        case .activityIndicator: return "Activity"
        case .pageIndicator: return "Pages"
        case .menu: return "Menu"
        case .menuItem: return "MenuItem"
        default: return "E\(t.rawValue)"
        }
    }

    private func write(_ text: String, to path: String) {
        let tmp = path + ".tmp"
        FileManager.default.createFile(atPath: tmp, contents: text.data(using: .utf8))
        _ = try? FileManager.default.removeItem(atPath: path)
        try? FileManager.default.moveItem(atPath: tmp, toPath: path)
    }
}
