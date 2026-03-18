$ErrorActionPreference = "Stop"

# --- 0. Kill Existing Processes ---
Write-Host ">>> Stopping any existing Java processes..." -ForegroundColor Cyan
Get-CimInstance Win32_Process -Filter "Name like 'java%'" | Where-Object { $_.CommandLine -like "*master.MasterServer*" -or $_.CommandLine -like "*chunkserver.ChunkServer*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Start-Sleep -Seconds 2

# --- 1. Cleanup ---
Write-Host ">>> Cleaning up previous build and data..." -ForegroundColor Cyan
if (Test-Path out) { Remove-Item -Recurse -Force out }
if (Test-Path data) { Remove-Item -Recurse -Force data }
Remove-Item -Force *.log -ErrorAction SilentlyContinue
Remove-Item -Force metadata.ser -ErrorAction SilentlyContinue

# --- 2. Compile ---
Write-Host ">>> Compiling Java sources..." -ForegroundColor Cyan
New-Item -ItemType Directory -Force out | Out-Null
$sources = Get-ChildItem -Path src -Filter *.java -Recurse
if ($null -eq $sources) { Write-Error "No Java files found in src!" }
$javacArgs = @("-d", "out") + $sources.FullName
& javac $javacArgs
if ($LASTEXITCODE -ne 0) { Write-Error "Compilation failed." }
Write-Host "Build Successful." -ForegroundColor Green

# --- 3. Prepare Data Directories ---
New-Item -ItemType Directory -Force data/s1 | Out-Null
New-Item -ItemType Directory -Force data/s2 | Out-Null

# --- 4. Start Servers ---
Write-Host ">>> Starting Master Server (Port 6000)..." -ForegroundColor Cyan
$master = Start-Process java -ArgumentList "-cp out master.MasterServer 6000" -PassThru -RedirectStandardOutput "master.out.log" -RedirectStandardError "master.err.log" -NoNewWindow
Start-Sleep -Seconds 3

Write-Host ">>> Starting ChunkServer 1 (Port 6001)..." -ForegroundColor Cyan
$cs1 = Start-Process java -ArgumentList "-cp out chunkserver.ChunkServer 6001 data/s1 localhost 6000" -PassThru -RedirectStandardOutput "s1.out.log" -RedirectStandardError "s1.err.log" -NoNewWindow

Write-Host ">>> Starting ChunkServer 2 (Port 6002)..." -ForegroundColor Cyan
$cs2 = Start-Process java -ArgumentList "-cp out chunkserver.ChunkServer 6002 data/s2 localhost 6000" -PassThru -RedirectStandardOutput "s2.out.log" -RedirectStandardError "s2.err.log" -NoNewWindow

Start-Sleep -Seconds 4

# --- 5. Run Demo Client ---
Write-Host "`n============================================" -ForegroundColor Yellow
Write-Host "  Running Client Tests" -ForegroundColor Yellow
Write-Host "============================================" -ForegroundColor Yellow

# Status
Write-Host "`n>>> Checking cluster status..." -ForegroundColor Yellow
java -cp out client.DemoClient status

# Create a test file
"Hello Distributed World! This is a test file from MiniGFS." | Set-Content -Path "test_input.txt" -NoNewline

# Upload
Write-Host "`n>>> Uploading file..." -ForegroundColor Yellow
java -cp out client.DemoClient upload test_input.txt /test_file.txt
Start-Sleep -Seconds 2

# List
Write-Host "`n>>> Listing files..." -ForegroundColor Yellow
java -cp out client.DemoClient list

# Download
Write-Host "`n>>> Downloading file..." -ForegroundColor Yellow
java -cp out client.DemoClient download /test_file.txt test_restored.txt

# --- 6. Verify ---
Write-Host "`n============================================" -ForegroundColor Yellow
if (Test-Path test_restored.txt) {
    $original = Get-Content test_input.txt -Raw
    $restored = Get-Content test_restored.txt -Raw
    if ($original -eq $restored) {
        Write-Host "  VERIFICATION: PASSED" -ForegroundColor Green
        Write-Host "  Content matches perfectly!" -ForegroundColor Green
    } else {
        Write-Host "  VERIFICATION: FAILED (Content mismatch)" -ForegroundColor Red
    }
} else {
    Write-Host "  VERIFICATION: FAILED (File not found)" -ForegroundColor Red
}
Write-Host "============================================" -ForegroundColor Yellow

# Delete
Write-Host "`n>>> Deleting test file..." -ForegroundColor Yellow
java -cp out client.DemoClient delete /test_file.txt

Write-Host "`n>>> Listing files after delete..." -ForegroundColor Yellow
java -cp out client.DemoClient list

# --- 7. Cleanup ---
Write-Host "`n>>> Press ENTER to stop servers and exit..." -ForegroundColor Cyan
Read-Host

Stop-Process -Id $master.Id -ErrorAction SilentlyContinue
Stop-Process -Id $cs1.Id -ErrorAction SilentlyContinue
Stop-Process -Id $cs2.Id -ErrorAction SilentlyContinue

Write-Host "Servers stopped. Logs: master.out.log, s1.out.log, s2.out.log" -ForegroundColor Gray
