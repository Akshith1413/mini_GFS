$ErrorActionPreference = "Stop"

Write-Host ">>> Compiling MiniGFS..." -ForegroundColor Cyan

# Create output directory
if (!(Test-Path out)) { New-Item -ItemType Directory -Force out | Out-Null }

# Find all Java files
$sources = Get-ChildItem -Path src -Filter *.java -Recurse

if ($null -eq $sources) {
    Write-Error "No Java files found in src!"
}

# Compile
& javac -d out $sources.FullName

if ($LASTEXITCODE -eq 0) {
    Write-Host "Build Successful! You are ready to run." -ForegroundColor Green
}
else {
    Write-Error "Build Failed."
}
