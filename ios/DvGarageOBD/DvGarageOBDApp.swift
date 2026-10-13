import SwiftUI

@main
struct DvGarageOBDApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.ble)
                .environmentObject(model.monitor)
                .environmentObject(model.workshop)
        }
    }
}
