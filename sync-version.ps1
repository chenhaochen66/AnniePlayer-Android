$json = Get-Content 'app.json' -Raw
$m = [regex]::Match($json, '"version"\s*:\s*"([^"]+)"')
$version = $m.Groups[1].Value
Write-Host "Version: $version"

$gradle = 'android\app\build.gradle'
$c = [IO.File]::ReadAllText($gradle)
$c = $c -replace 'versionName ".*"', "versionName `"$version`""
$c = $c -replace 'versionCode \d+', 'versionCode 1'
[IO.File]::WriteAllText($gradle, $c, [Text.UTF8Encoding]$false)

$strings = 'android\app\src\main\res\values\strings.xml'
$x = [IO.File]::ReadAllText($strings)
$x = $x -replace '(expo_runtime_version">)[^<]+', "`${1}$version"
[IO.File]::WriteAllText($strings, $x, [Text.UTF8Encoding]$false)

$pkg = 'package.json'
$p = [IO.File]::ReadAllText($pkg)
$p = $p -replace '"version": ".*"', "`"version`": `"$version`""
[IO.File]::WriteAllText($pkg, $p, [Text.UTF8Encoding]$false)

Write-Host "Synced: $version"
