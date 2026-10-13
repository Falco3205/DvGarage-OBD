import SwiftUI

struct VehicleView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var monitor: MonitorService
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Veicolo") {
                    Text(model.vehicle.map { "Collegato a: \($0.name)" } ?? "Nessun veicolo collegato a questo telefono")
                    if !monitor.isRunning {
                        ForEach(model.vehicles.filter { $0.id != model.vehicle?.id }) { vehicle in
                            Button("Usa \(vehicle.name)") { Task { await model.linkVehicle(vehicle) } }
                                .disabled(model.busy)
                        }
                        HStack {
                            TextField("Nuovo veicolo", text: $newName)
                            Button("Aggiungi") {
                                let name = newName.trimmingCharacters(in: .whitespaces)
                                newName = ""
                                Task { await model.createVehicle(name: name) }
                            }
                            .disabled(model.busy || newName.trimmingCharacters(in: .whitespaces).isEmpty)
                        }
                    }
                }

                AdapterPickerView(enabled: !monitor.isRunning)

                Section {
                    if monitor.isRunning {
                        Button("Ferma monitoraggio", role: .destructive) { model.stopMonitoring() }
                    } else {
                        Button("Avvia monitoraggio") { model.startMonitoring() }
                            .disabled(model.busy)
                    }
                    if let message = model.vehicleMessage {
                        Text(message).foregroundColor(.red)
                    }
                    Text("Tieni l'app aperta e lo schermo acceso: iOS sospende l'app in background e i comandi remoti non arrivano.")
                        .font(.footnote).foregroundColor(.secondary)
                }

                Section("Stato") {
                    LabeledContent("Stato", value: phaseText)
                    if !monitor.message.isEmpty {
                        Text(monitor.message).foregroundColor(monitor.phase == .error ? .red : .primary)
                    }
                    if let last = monitor.lastSync {
                        LabeledContent("Ultimo invio", value: last.formatted(date: .abbreviated, time: .standard))
                    }
                    if let snapshot = monitor.snapshot { SnapshotView(snapshot: snapshot) }
                }

                Section {
                    Button("Esci", role: .destructive) { Task { await model.logout() } }
                } footer: {
                    Text(model.account?.displayName ?? "")
                }
            }
            .navigationTitle("Veicolo")
        }
    }

    private var phaseText: String {
        switch monitor.phase {
        case .stopped: return "Fermo"
        case .connecting: return "Connessione"
        case .online: return "Online"
        case .error: return "Errore"
        }
    }
}
