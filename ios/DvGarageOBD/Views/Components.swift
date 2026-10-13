import OBDCore
import SwiftUI

struct AdapterPickerView: View {
    @EnvironmentObject private var ble: BleAdapter
    @EnvironmentObject private var model: AppModel
    let enabled: Bool

    var body: some View {
        Section("Adattatore OBD (Bluetooth LE)") {
            if let selected = model.adapter {
                Label(selected.name, systemImage: "checkmark.circle.fill").foregroundColor(.green)
            }
            Button(ble.scanning ? "Ricerca in corso…" : "Cerca adattatori") { ble.startScan() }
                .disabled(!enabled || ble.scanning)
            ForEach(ble.found) { choice in
                Button {
                    model.adapter = choice
                } label: {
                    HStack {
                        Text(choice.name)
                        Spacer()
                        if choice == model.adapter { Image(systemName: "checkmark") }
                    }
                }
                .disabled(!enabled)
            }
            Text(ble.stateText).font(.footnote).foregroundColor(.secondary)
            Text("L'iPhone supporta solo adattatori Bluetooth LE (4.0). Gli ELM327 \"Bluetooth 3.0 / SPP\" non compaiono qui.")
                .font(.footnote).foregroundColor(.secondary)
        }
    }
}

struct SnapshotView: View {
    let snapshot: Snapshot

    var body: some View {
        if let vin = snapshot.vin { LabeledContent("VIN", value: vin) }
        LabeledContent("Spia motore", value: snapshot.milOn ? "accesa" : "spenta")
        if snapshot.dtcs.isEmpty {
            Text("Nessun errore presente")
        } else {
            ForEach(snapshot.dtcs, id: \.code) { dtc in
                LabeledContent(dtc.code, value: label(dtc.kind))
            }
        }
    }

    private func label(_ kind: DtcKind) -> String {
        switch kind {
        case .stored: return "memorizzato"
        case .pending: return "in attesa"
        case .permanent: return "permanente"
        }
    }
}
