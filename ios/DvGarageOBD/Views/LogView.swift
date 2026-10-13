import SwiftUI

struct LogView: View {
    @ObservedObject private var log = TraceLog.shared

    var body: some View {
        NavigationStack {
            List(log.lines.reversed(), id: \.self) { line in
                Text(line).font(.system(size: 11, design: .monospaced))
            }
            .overlay { if log.lines.isEmpty { Text("Nessuna comunicazione registrata").foregroundColor(.secondary) } }
            .navigationTitle("Log")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Svuota") { log.clear() }.disabled(log.lines.isEmpty)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    ShareLink(item: log.text) { Image(systemName: "square.and.arrow.up") }
                        .disabled(log.lines.isEmpty)
                }
            }
        }
    }
}
