import sys

# Fix assetProxy.ts
with open('services/assetProxy.ts', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    "FileSystem.createDownloadResumable(url, filepath, {}, {\n      headers: { 'User-Agent': CHROME_UA },\n    })",
    "FileSystem.createDownloadResumable(url, filepath, {}, (e) => {})"
)

content = content.replace(
    "FileSystem.createDownloadResumable(url, filepath, {}, {\n    headers: { 'User-Agent': CHROME_UA },\n  })",
    "FileSystem.createDownloadResumable(url, filepath, {}, (e) => {})"
)

with open('services/assetProxy.ts', 'w', encoding='utf-8') as f:
    f.write(content)
print('Fixed assetProxy.ts')

# Fix downloadService.ts
with open('services/downloadService.ts', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    "FileSystem.EncodingType",
    "FileSystem.EncodingType"
)

content = content.replace(
    "    const download = FileSystem.createDownloadResumable(\n      downloadUrl,\n      tempPath,\n      {},\n      {\n        headers: { 'User-Agent': CHROME_UA },\n        onProgress: (event) => {\n          if (event.totalBytesExpectedToWrite > 0) {\n            onProgress(event.totalBytesWritten / event.totalBytesExpectedToWrite);\n          }\n        },\n      },\n    )",
    "    const download = FileSystem.createDownloadResumable(\n      downloadUrl,\n      tempPath,\n      {},\n      (e) => {}\n    )"
)

with open('services/downloadService.ts', 'w', encoding='utf-8') as f:
    f.write(content)
print('Fixed downloadService.ts')
