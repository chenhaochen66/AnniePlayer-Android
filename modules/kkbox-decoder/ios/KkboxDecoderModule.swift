import ExpoModulesCore

public class KkboxDecoderModule: Module {
  public func definition() -> ModuleDefinition {
    Name("KkboxDecoder")

    AsyncFunction("decryptKkboxAudio") { (sourceUrl: String, licContentKey: String, outputPath: String) -> String in
      try self.decryptKkboxAudio(sourceUrl: sourceUrl, licContentKey: licContentKey, outputPath: outputPath)
      return outputPath
    }
  }

  private func decryptKkboxAudio(sourceUrl: String, licContentKey: String, outputPath: String) throws {
    let fm = FileManager.default
    let outUrl = URL(fileURLWithPath: outputPath)
    try fm.createDirectory(at: outUrl.deletingLastPathComponent(), withIntermediateDirectories: true)

    guard let url = URL(string: sourceUrl) else { throw NSError(domain: "KKBOX", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"]) }
    var request = URLRequest(url: url)
    request.setValue("okhttp/3.14.9", forHTTPHeaderField: "User-Agent")
    request.setValue("bytes=1024-", forHTTPHeaderField: "Range")

    let semaphore = DispatchSemaphore(value: 0)
    var downloadError: Error?

    let task = URLSession.shared.dataTask(with: request) { data, response, error in
      if let error = error {
        downloadError = error
        semaphore.signal()
        return
      }
      guard let data = data else {
        downloadError = NSError(domain: "KKBOX", code: 2, userInfo: [NSLocalizedDescriptionKey: "No data"])
        semaphore.signal()
        return
      }
      do
      {
        let rc4 = Rc4(key: licContentKey, drop: 512)
        var offset = 0
        let chunkSize = 131072
        var outputData = Data(capacity: data.count)
        while offset < data.count {
          let end = min(offset + chunkSize, data.count)
          let chunk = data[offset..<end]
          let decrypted = rc4.process(Data(chunk))
          outputData.append(decrypted)
          offset = end
        }
        try outputData.write(to: outUrl)
      } catch {
        downloadError = error
      }
      semaphore.signal()
    }
    task.resume()
    semaphore.wait()

    if let error = downloadError { throw error }
  }
}

fileprivate class Rc4 {
  private var s: [Int]
  private var i = 0
  private var j = 0

  init(key: String, drop: Int) {
    s = Array(0...255)
    let keyBytes = key.map { Int($0.asciiValue ?? 0) }
    var j = 0
    for k in 0..<256 {
      j = (j + s[k] + keyBytes[k % keyBytes.count]) & 0xFF
      s.swapAt(k, j)
    }
    for _ in 0..<drop {
      self.i = (self.i + 1) & 0xFF
      self.j = (self.j + s[self.i]) & 0xFF
      s.swapAt(self.i, self.j)
    }
  }

  func process(_ data: Data) -> Data {
    var out = Data(count: data.count)
    for k in 0..<data.count {
      i = (i + 1) & 0xFF
      j = (j + s[i]) & 0xFF
      s.swapAt(i, j)
      out[k] = data[k] ^ UInt8(s[(s[i] + s[j]) & 0xFF])
    }
    return out
  }
}
