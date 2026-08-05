<#
    One-time bootstrap for the Gradle wrapper.

    The wrapper JAR is a binary and is not checked in, so `./gradlew` does not
    exist until you run this once (or open the project in Android Studio, which
    does the same thing).

    It also checks the JDK, because Gradle 8.14 does not run on JDK 25+ and the
    resulting error message does not say so clearly.

        powershell -ExecutionPolicy Bypass -File tools\bootstrap-wrapper.ps1
#>

$ErrorActionPreference = 'Stop'

$GradleVersion = '8.14'
$RepoRoot = Split-Path -Parent $PSScriptRoot

function Get-JavaMajor([string]$JavaExe) {
    $output = & $JavaExe -version 2>&1 | Out-String
    if ($output -match '"(\d+)') { return [int]$Matches[1] }
    return 0
}

Write-Host '== Checking JDK ==' -ForegroundColor Cyan

$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Write-Host 'No `java` on PATH.' -ForegroundColor Red
    Write-Host 'Install a JDK 21 (LTS), for example:  winget install EclipseAdoptium.Temurin.21.JDK'
    exit 1
}

$major = Get-JavaMajor $javaCmd.Source
Write-Host "  java on PATH: $($javaCmd.Source)  (major $major)"

if ($major -lt 17 -or $major -gt 24) {
    Write-Host ''
    Write-Host "Gradle $GradleVersion supports Java 17-24; this JDK is $major." -ForegroundColor Red
    Write-Host 'Android Gradle Plugin 8.13 needs 17+. Install JDK 21 and point JAVA_HOME at it:'
    Write-Host '  winget install EclipseAdoptium.Temurin.21.JDK'
    Write-Host '  $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21..."'
    Write-Host ''
    Write-Host 'Nothing was changed.' -ForegroundColor Yellow
    exit 1
}

Write-Host ''
Write-Host '== Fetching Gradle ==' -ForegroundColor Cyan

$gradleCmd = Get-Command gradle -ErrorAction SilentlyContinue
if ($gradleCmd) {
    Write-Host "  using existing gradle: $($gradleCmd.Source)"
    $gradleExe = $gradleCmd.Source
} else {
    $work = Join-Path $env:TEMP "inkstone-gradle-$GradleVersion"
    $zip = Join-Path $env:TEMP "gradle-$GradleVersion-bin.zip"
    if (-not (Test-Path (Join-Path $work "gradle-$GradleVersion"))) {
        Write-Host "  downloading gradle-$GradleVersion-bin.zip ..."
        Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $zip
        Write-Host '  extracting ...'
        Expand-Archive -Path $zip -DestinationPath $work -Force
    }
    $gradleExe = Join-Path $work "gradle-$GradleVersion\bin\gradle.bat"
}

Write-Host ''
Write-Host '== Generating the wrapper ==' -ForegroundColor Cyan
Push-Location $RepoRoot
try {
    & $gradleExe wrapper --gradle-version $GradleVersion --distribution-type bin
    if ($LASTEXITCODE -ne 0) { throw "gradle wrapper failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host 'Done. gradlew, gradlew.bat and gradle/wrapper/gradle-wrapper.jar now exist.' -ForegroundColor Green
Write-Host 'Next:  .\gradlew.bat verifyCatalogCoordinates'
