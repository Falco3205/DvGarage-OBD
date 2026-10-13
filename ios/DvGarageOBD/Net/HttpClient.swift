import Foundation

struct ApiError: LocalizedError {
    let status: Int
    let message: String

    var errorDescription: String? { message }
}

struct HttpClient {
    let baseURL: String

    func request(
        _ method: String,
        _ path: String,
        bearer: String?,
        body: [String: Any]? = nil,
        timeout: TimeInterval = 15
    ) async throws -> [String: Any]? {
        guard let url = URL(string: baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/ ")) + path) else {
            throw ApiError(status: 0, message: "Indirizzo server non valido")
        }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let bearer { request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw ApiError(status: 0, message: "Risposta non valida dal server")
        }
        if http.statusCode == 204 { return nil }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard (200..<300).contains(http.statusCode) else {
            throw ApiError(status: http.statusCode, message: json?["error"] as? String ?? "Errore HTTP \(http.statusCode)")
        }
        return json
    }
}
