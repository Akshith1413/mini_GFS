#!/bin/bash
# Build script for MiniGFS (Mac/Linux)
set -e

echo ">>> Compiling MiniGFS..."

# Create output directory
mkdir -p out

# Find and compile all Java files
find src -name "*.java" > /tmp/minigfs_sources.txt
javac -d out @/tmp/minigfs_sources.txt
rm -f /tmp/minigfs_sources.txt

echo "Build Successful! You are ready to run."
echo ""
echo "Quick start (single machine test):"
echo "  ./run.sh"
echo ""
echo "Or run individual components:"
echo "  java -cp out master.MasterServer 6000"
echo "  java -cp out chunkserver.ChunkServer 6001 data/s1"
echo "  java -cp out chunkserver.ChunkServer 6002 data/s2"
echo "  java -cp out client.DemoClient list"
