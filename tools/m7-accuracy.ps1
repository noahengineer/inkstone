<#
.SYNOPSIS
  ROADMAP M7's headline measurement: line segmentation vs whole-page-as-one-blob.

.DESCRIPTION
  Fetches the IAMonDo corpus, builds a 20-page / 20-writer sample, pushes it to an emulator
  and runs the accuracy harness. Prints both numbers.

  ⚠️ THE CORPUS IS NOT IN THIS REPO AND MUST NOT BE. IAMonDo v1.0 is licensed for
  non-commercial research use and confers no redistribution right, so only the aggregate
  numbers are committed (docs/M7-RECOGNITION.md). This script fetches it into a scratch
  directory outside the working tree.

  Citation, as the terms request:
    E. Indermuehle, M. Liwicki, H. Bunke, "IAMonDo-database: an Online Handwritten Document
    Database with Non-uniform Contents", Proc. Int. Workshop on Document Analysis Systems
    (DAS), pp. 97-104, 2010.

  ⚠️ The mirror is PLAIN HTTP. www.iapr-tc11.org has no working TLS at all (an https request
  fails with WRONG_VERSION_NUMBER, not a bad certificate), and FKI's own IAMonDo page names
  the TC-11 site as an alternative download, which is what makes this route sanctioned rather
  than a scrape. Nothing secret travels over it and the archive's size is checked below.

.PARAMETER PrepareOnly
  Build the sample and stop. Enough for the JVM segmentation diagnostic
  (:app:testDebugUnitTest --tests '*LineSegmenterCorpusTest'), which needs no device.

.EXAMPLE
  tools/m7-accuracy.ps1
  tools/m7-accuracy.ps1 -PrepareOnly
#>
param(
    [switch] $PrepareOnly,
    [string] $Serial = "",
    [string] $WorkDir = (Join-Path $env:TEMP "inkstone-m7")
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$corpus = Join-Path $WorkDir "IAMonDo-db-1.0"
$archive = Join-Path $WorkDir "IAMonDo-db-1.0.tar.gz"
$sample = Join-Path $WorkDir "sample.json"
$expectedBytes = 30569465

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

if (-not (Test-Path $corpus)) {
    if (-not (Test-Path $archive) -or (Get-Item $archive).Length -ne $expectedBytes) {
        Write-Host "Fetching IAMonDo (29 MB) from the IAPR TC-11 mirror..."
        curl.exe -sS --retry 5 --retry-all-errors -m 900 -o $archive `
            "http://www.iapr-tc11.org/dataset/IAMonDo/IAMonDo-db-1.0.tar.gz"
    }
    $size = (Get-Item $archive).Length
    if ($size -ne $expectedBytes) {
        throw "IAMonDo archive is $size bytes, expected $expectedBytes. Refusing to use it."
    }
    Write-Host "Unpacking..."
    tar -xzf $archive -C $WorkDir
}

$scripts = Join-Path $PSScriptRoot "m7"
Write-Host "Building the 20-page sample..."
python (Join-Path $scripts "prepare_sample.py") --corpus $corpus --out $sample
if ($LASTEXITCODE -ne 0) { throw "sample preparation failed" }

Write-Host ""
Write-Host "Sample: $sample"
Write-Host "  JVM segmentation diagnostic (no device needed):"
Write-Host "    INKSTONE_M7_SAMPLE=$sample ./gradlew :app:testDebugUnitTest --tests '*LineSegmenterCorpusTest'"

if ($PrepareOnly) { return }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
$target = if ($Serial) { @("-s", $Serial) } else { @() }

Write-Host ""
Write-Host "Pushing the sample to the device..."
& $adb @target shell "mkdir -p /data/local/tmp/inkstone-m7 && chmod 777 /data/local/tmp/inkstone-m7"
& $adb @target push $sample /data/local/tmp/inkstone-m7/sample.json
& $adb @target shell "chmod 666 /data/local/tmp/inkstone-m7/sample.json"
& $adb @target logcat -c

Write-Host "Running the harness (downloads the en-US model on first run; ~8 minutes)..."
Push-Location $repo
try {
    if ($Serial) { $env:ANDROID_SERIAL = $Serial }
    & ./gradlew :android:ocr:connectedDebugAndroidTest `
        "-Pandroid.testInstrumentationRunnerArguments.class=dev.inkstone.android.ocr.SegmentationAccuracyTest"
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "==== results ===="
& $adb @target logcat -d -s System.out:I | Select-String "M7-ACCURACY"
