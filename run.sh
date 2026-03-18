#!/bin/bash
# Single-machine test script for MiniGFS (Mac/Linux)
# Starts Master + 2 ChunkServers + runs client tests
set -e

echo ">>> Cleaning up previous data..."
rm -rf out data *.log metadata.ser

echo ">>> Building..."
mkdir -p out
find src -name "*.java" > /tmp/minigfs_sources.txt
javac -d out @/tmp/minigfs_sources.txt
rm -f /tmp/minigfs_sources.txt
echo "Build Successful."

# Create storage directories
mkdir -p data/s1 data/s2

# Start Master Server
echo ">>> Starting Master Server (Port 6000)..."
java -cp out master.MasterServer 6000 > master.out.log 2> master.err.log &
MASTER_PID=$!
echo "  Master PID: $MASTER_PID"

# Wait for master to start
sleep 3

# Start ChunkServer 1
echo ">>> Starting ChunkServer 1 (Port 6001)..."
java -cp out chunkserver.ChunkServer 6001 data/s1 localhost 6000 > s1.out.log 2> s1.err.log &
CS1_PID=$!

# Start ChunkServer 2
echo ">>> Starting ChunkServer 2 (Port 6002)..."
java -cp out chunkserver.ChunkServer 6002 data/s2 localhost 6000 > s2.out.log 2> s2.err.log &
CS2_PID=$!

# Wait for chunk servers to register
sleep 4

# Cleanup function
cleanup() {
    echo ""
    echo ">>> Stopping servers..."
    kill $MASTER_PID $CS1_PID $CS2_PID 2>/dev/null || true
    echo "Servers stopped. Logs: master.out.log, s1.out.log, s2.out.log"
}
trap cleanup EXIT

# Run Demo Client
echo ""
echo "============================================"
echo "  Running Client Tests"
echo "============================================"

# Check cluster status
echo ""
echo ">>> Checking cluster status..."
java -cp out client.DemoClient status

# Create test file
echo "Hello Distributed World! This is a test file from MiniGFS." > test_input.txt

# Upload
echo ""
echo ">>> Uploading test file..."
java -cp out client.DemoClient upload test_input.txt /test_file.txt

sleep 2

# List files
echo ""
echo ">>> Listing files..."
java -cp out client.DemoClient list

# Download
echo ""
echo ">>> Downloading test file..."
java -cp out client.DemoClient download /test_file.txt test_restored.txt

# Verify
echo ""
echo "============================================"
if [ -f test_restored.txt ]; then
    ORIGINAL=$(cat test_input.txt)
    RESTORED=$(cat test_restored.txt)
    if [ "$ORIGINAL" = "$RESTORED" ]; then
        echo "  VERIFICATION: PASSED"
        echo "  Content matches perfectly!"
    else
        echo "  VERIFICATION: FAILED"
        echo "  Content mismatch!"
        echo "  Original:  $ORIGINAL"
        echo "  Restored:  $RESTORED"
    fi
else
    echo "  VERIFICATION: FAILED"
    echo "  Downloaded file not found!"
fi
echo "============================================"

# Delete
echo ""
echo ">>> Deleting test file..."
java -cp out client.DemoClient delete /test_file.txt

# Verify deletion
echo ""
echo ">>> Listing files after delete..."
java -cp out client.DemoClient list

echo ""
echo ">>> All tests complete! Press ENTER to stop servers..."
read -r
