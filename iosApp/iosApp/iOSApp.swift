import SwiftUI
import Shared

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // campusmarket://orders/{id} and campusmarket://payouts (Stripe Checkout / onboarding return URLs).
                // Registered under CFBundleURLTypes in Info.plist; routed into navigation by the shared code.
                .onOpenURL { url in
                    _ = MainViewControllerKt.handleDeepLink(url: url.absoluteString)
                }
        }
    }
}
