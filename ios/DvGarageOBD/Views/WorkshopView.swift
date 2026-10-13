import OBDCore
import SwiftUI

struct WorkshopView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var workshop: WorkshopService
    @EnvironmentObject private var ble: BleAdapter
    @State private var label = "Lettura officina"
    @State private var customerId: Int?
    @State private var saveMessage: String?
    @State private var saving = false
    @State private var confirmClear = false

    private var locked: Bool { workshop.busy || model.monitor.isRunning }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Lettura diretta dall'adattatore, senza bisogno del cliente.")
                    if model.monitor.isRunning {
                        Text("Ferma il monitoraggio del veicolo per usare l'officina.").foregroundColor(.red)
                    }
                }

                AdapterPickerView(enabled: !locked)

                Section {
                    Button(workshop.busy ? "Attendere…" : "Leggi errori") {
                        if let adapter = model.adapter { workshop.read(ble: ble, adapter: adapter) }
                        else { saveMessage = "Scegli l'adattatore OBD" }
                    }
                    .disabled(locked)
                    if !workshop.message.isEmpty {
                        Text(workshop.message).foregroundColor(workshop.isError ? .red : .primary)
                    }
                }

                if let snapshot = workshop.snapshot {
                    Section("Risultato") {
                        SnapshotView(snapshot: snapshot)
                        Button("Cancella errori", role: .destructive) { confirmClear = true }
                            .disabled(locked || snapshot.dtcs.isEmpty)
                    }

                    Section("Salva sul server") {
                        TextField("Descrizione", text: $label)
                        Picker("Cliente", selection: $customerId) {
                            Text("Senza cliente").tag(Int?.none)
                            ForEach(model.customers) { Text("\($0.displayName) (\($0.username))").tag(Int?.some($0.id)) }
                        }
                        Button("Salva lettura") { save(snapshot) }
                            .disabled(locked || saving || label.trimmingCharacters(in: .whitespaces).isEmpty)
                        Button("Nuova lettura") { workshop.reset(); saveMessage = nil }
                            .disabled(locked)
                        if let saveMessage { Text(saveMessage) }
                    }
                }
            }
            .navigationTitle("Officina")
            .alert("Cancellare gli errori dalla centralina?", isPresented: $confirmClear) {
                Button("Annulla", role: .cancel) {}
                Button("Cancella", role: .destructive) {
                    if let adapter = model.adapter { workshop.clear(ble: ble, adapter: adapter) }
                }
            } message: {
                Text("Verranno azzerati anche i monitor di prontezza e i dati freeze frame.")
            }
        }
    }

    private func save(_ snapshot: Snapshot) {
        saving = true
        let text = label.trimmingCharacters(in: .whitespaces)
        Task {
            saveMessage = await model.saveReport(label: text, customerId: customerId, snapshot: snapshot, cleared: workshop.cleared)
            saving = false
        }
    }
}
