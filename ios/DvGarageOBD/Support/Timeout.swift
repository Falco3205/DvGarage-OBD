import Foundation

func withThrowingTimeout<T>(
    seconds: TimeInterval,
    onTimeout: Error,
    operation: @escaping () async throws -> T,
    onTimeoutCleanup: @escaping () -> Void
) async throws -> T {
    let timer = Task { @MainActor in
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        if !Task.isCancelled { onTimeoutCleanup() }
    }
    defer { timer.cancel() }
    return try await operation()
}
