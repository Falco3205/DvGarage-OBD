import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if model.account == nil {
            LoginView()
        } else {
            HomeView()
        }
    }
}

struct LoginView: View {
    @EnvironmentObject private var model: AppModel
    @State private var serverURL = ""
    @State private var username = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("https://…", text: $serverURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                }
                Section("Account") {
                    TextField("Utente o email", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textContentType(.username)
                    SecureField("Password", text: $password)
                        .textContentType(.password)
                }
                if let error = model.loginError {
                    Text(error).foregroundColor(.red)
                }
                Button(model.busy ? "Accesso…" : "Accedi") {
                    Task { await model.login(serverURL: serverURL, username: username, password: password) }
                }
                .disabled(model.busy || serverURL.isEmpty || username.isEmpty || password.isEmpty)
            }
            .navigationTitle("DvGarage OBD")
            .onAppear { if serverURL.isEmpty { serverURL = model.savedServerURL } }
        }
    }
}

struct HomeView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            VehicleView()
                .tabItem { Label("Veicolo", systemImage: "car") }
            if model.account?.isAdmin == true {
                WorkshopView()
                    .tabItem { Label("Officina", systemImage: "wrench.and.screwdriver") }
            }
            LogView()
                .tabItem { Label("Log", systemImage: "text.alignleft") }
        }
    }
}
