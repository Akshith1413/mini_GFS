# Mini Google File System (MiniGFS)

A distributed file system with **chunk-based storage**, **2-replica replication**, and **automatic failure detection & self-healing**, implemented in pure Java.

---

## Architecture

```
┌──────────────────────────────────────────────────┐
│                  MASTER SERVER                    │
│  (Computer A)                                    │
│  - Stores metadata (file→chunk→server mappings)  │
│  - Allocates chunks to servers                    │
│  - Heartbeat-based failure detection (5s cycle)   │
│  - Auto-replication on server failure             │
│  - Persists metadata to disk                      │
│  Port: 6000                                       │
└──────────┬──────────────────────┬────────────────┘
           │                      │
    ┌──────▼─────┐         ┌──────▼─────┐
    │ CHUNK      │         │ CHUNK      │
    │ SERVER 1   │         │ SERVER 2   │
    │ (Comp B)   │         │ (Comp C)   │
    │ Port: 6001 │         │ Port: 6002 │
    └──────▲─────┘         └──────▲─────┘
           │                      │
           └──────────┬───────────┘
                ┌─────▼──────┐
                │   CLIENT   │
                │ (Comp D)   │
                │ CLI / Web  │
                └────────────┘
```

**How it works:**
1. Client asks Master to allocate chunks for a file
2. Master selects 2 ChunkServers (replicas) per chunk
3. Client writes chunk data to both replicas directly
4. For reads, Client gets chunk locations from Master, reads from any replica
5. ChunkServers send heartbeats every 3 seconds; Master marks as dead after 15 seconds of silence
6. Master auto-replicates chunks from surviving replica to a new server on failure

---

## Prerequisites

### Windows Computers
1. Install **Java JDK 17+**: Download from [Oracle](https://www.oracle.com/java/technologies/downloads/) or [Adoptium](https://adoptium.net/)
2. Verify installation:
   ```powershell
   java -version
   javac -version
   ```

### Mac Computers
1. Install **Homebrew** (if not installed):
   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
   ```
2. Install **Java JDK 17+**:
   ```bash
   brew install openjdk@17
   ```
3. Add Java to PATH (add to `~/.zshrc` or `~/.bash_profile`):
   ```bash
   export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
   ```
4. Verify:
   ```bash
   java -version
   javac -version
   ```

---

## Single-Machine Quick Test

To test everything on one computer before deploying across 4 machines:

**Windows (PowerShell):**
```powershell
cd C:\DistributedCase
.\run.ps1
```

**Mac/Linux (Terminal):**
```bash
cd ~/DistributedCase
chmod +x build.sh run.sh
./run.sh
```

This will compile, start all servers locally, and run upload/download/delete tests automatically.

---

## Multi-Computer Deployment (4 Machines)

### Step 0: Preparation

#### Decide Roles
| Role | Computer | OS | Port |
|------|----------|----|------|
| **Master Server** | Computer A | Windows or Mac | 6000 |
| **ChunkServer 1** | Computer B | Windows or Mac | 6001 |
| **ChunkServer 2** | Computer C | Windows or Mac | 6002 |
| **Client** | Computer D | Windows or Mac | (none) |

#### Copy the Code
Copy the entire `DistributedCase` folder to all 4 computers.

**Options:**
- USB drive
- AirDrop (Mac to Mac)
- Email / Google Drive / Shared folder
- `scp` command:
  ```bash
  scp -r DistributedCase/ user@192.168.x.x:~/DistributedCase
  ```

#### Find the Master's IP Address

On the **Master computer (Computer A)**, find its IP:

**Windows:**
```powershell
ipconfig
```
Look for **IPv4 Address** under your active adapter (Wi-Fi or Ethernet), e.g., `192.168.1.50`

**Mac:**
```bash
ipconfig getifaddr en0
```
(Use `en0` for Wi-Fi, `en1` for Ethernet)

> **Write down this IP!** All other computers need it. Example: `192.168.1.50`

#### Verify Network Connectivity

From every other computer, ping the Master:
```bash
ping 192.168.1.50
```
You should see replies. If not, check the firewall section below.

---

### Step 1: Compile on All Computers

**On each computer**, navigate to the `DistributedCase` folder and compile:

**Windows (PowerShell):**
```powershell
cd C:\DistributedCase
.\build.ps1
```

**Mac (Terminal):**
```bash
cd ~/DistributedCase
chmod +x build.sh
./build.sh
```

You should see: `Build Successful!`

---

### Step 2: Start Master Server (Computer A)

**Windows:**
```powershell
cd C:\DistributedCase
java -cp out master.MasterServer 6000
```

**Mac:**
```bash
cd ~/DistributedCase
java -cp out master.MasterServer 6000
```

You should see:
```
[...] [Master] MasterServer starting on port 6000...
[...] [Master] MasterServer is READY and listening on port 6000
```

> **Leave this terminal open. The Master must keep running.**

---

### Step 3: Start ChunkServer 1 (Computer B)

Replace `192.168.1.50` with your Master's actual IP.

**Windows:**
```powershell
cd C:\DistributedCase
java -cp out chunkserver.ChunkServer 6001 data/s1 192.168.1.50 6000
```

**Mac:**
```bash
cd ~/DistributedCase
java -cp out chunkserver.ChunkServer 6001 data/s1 192.168.1.50 6000
```

You should see:
```
[...] [CS:6001] ChunkServer starting on port 6001...
[...] [CS:6001] Registration successful: Registered <YOUR_IP>:6001
[...] [CS:6001] ChunkServer is READY and listening on port 6001
```

> **Leave this terminal open.**

---

### Step 4: Start ChunkServer 2 (Computer C)

Replace `192.168.1.50` with your Master's actual IP.

**Windows:**
```powershell
cd C:\DistributedCase
java -cp out chunkserver.ChunkServer 6002 data/s2 192.168.1.50 6000
```

**Mac:**
```bash
cd ~/DistributedCase
java -cp out chunkserver.ChunkServer 6002 data/s2 192.168.1.50 6000
```

You should see:
```
[...] [CS:6002] ChunkServer starting on port 6002...
[...] [CS:6002] Registration successful: Registered <YOUR_IP>:6002
[...] [CS:6002] ChunkServer is READY and listening on port 6002
```

> **Leave this terminal open.**

---

### Step 5: Run Client Operations (Computer D)

Replace `192.168.1.50` with your Master's actual IP in all commands below.

#### Check Cluster Status
```bash
# Windows
java -Dminigfs.master.host=192.168.1.50 -cp out client.DemoClient status

# Mac
java -Dminigfs.master.host=192.168.1.50 -cp out client.DemoClient status
```

Expected output:
```
========================================
  CLUSTER STATUS
  Master: 192.168.1.50:6000
========================================
  Master Port   : 6000
  Active Servers : 2
  Files Stored   : 0
  Total Chunks   : 0
  Server List    : [192.168.1.51:6001, 192.168.1.52:6002]
========================================
```

#### Upload a File
```bash
# Create a test file first
echo "Hello from the distributed file system!" > mytest.txt

# Upload
java -Dminigfs.master.host=192.168.1.50 -cp out client.DemoClient upload mytest.txt /docs/mytest.txt
```

#### List All Files
```bash
java -Dminigfs.master.host=192.168.1.50 -cp out client.DemoClient list
```

#### Download a File
```bash
java -Dminigfs.master.host=192.168.1.50 -cp out client.DemoClient download /docs/mytest.txt downloaded.txt
```

#### Delete a File
```bash
java -Dminigfs.master.host=192.168.1.50 -cp out client.DemoClient delete /docs/mytest.txt
```

---

### Step 6: (Optional) Web Dashboard

Run on the **Master computer** (or any computer that can reach the Master):

**Windows:**
```powershell
java -Dminigfs.master.host=192.168.1.50 -cp out master.WebServer 8080
```

**Mac:**
```bash
java -Dminigfs.master.host=192.168.1.50 -cp out master.WebServer 8080
```

Then open a browser and go to: `http://localhost:8080`

---

## Testing Failure Detection & Self-Healing

1. Upload a file while both ChunkServers are running
2. **Kill one ChunkServer** (close its terminal window or press Ctrl+C)
3. Wait ~15 seconds — the Master will detect the failure:
   ```
   [Master] ALERT: Server 192.168.1.51:6001 is DEAD!
   [Master] Self-Healing: Replicating chunk_0 from 192.168.1.52:6002 to ...
   ```
4. **Download the file** — it still works! Client reads from the surviving replica
5. **Restart the ChunkServer** — it re-registers and starts receiving heartbeats again

---

## Firewall Configuration

### Windows Firewall
If connections are refused, allow Java through the firewall:

1. Open **Windows Defender Firewall** → **Advanced Settings**
2. Click **Inbound Rules** → **New Rule**
3. Select **Port** → **TCP** → Enter: `6000,6001,6002,8080`
4. Allow the connection → Apply to all profiles → Name it "MiniGFS"

**Or use PowerShell (Run as Administrator):**
```powershell
New-NetFirewallRule -DisplayName "MiniGFS" -Direction Inbound -Protocol TCP -LocalPort 6000,6001,6002,8080 -Action Allow
```

### Mac Firewall
1. **System Settings** → **Network** → **Firewall**
2. Turn off the firewall OR add Java as an allowed application
3. **Or** temporarily disable for testing:
   ```bash
   # Disable (requires password)
   sudo /usr/libexec/ApplicationFirewall/socketfilterfw --setglobalstate off

   # Re-enable after testing
   sudo /usr/libexec/ApplicationFirewall/socketfilterfw --setglobalstate on
   ```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Connection refused` | Check Master IP, ensure Master is running, check firewall |
| `Registration failed` | Master not reachable — verify IP and port, check ping |
| ChunkServer shows `localhost` instead of LAN IP | The auto-detection picked loopback. Check network interfaces are up |
| `Compilation failed` | Ensure `javac` is in PATH and version is 17+ |
| `ClassNotFoundException` | Make sure you compiled (`build.ps1` / `build.sh`) before running |
| Upload works but download fails | ChunkServer may have died. Check `status` command and server terminals |
| `Address already in use` | Another process is using that port. Kill it or choose a different port |
| Mac: `java: command not found` | Add Java to PATH in `~/.zshrc` (see Prerequisites) |
| Slow heartbeat detection | Master checks every 5s with 15s timeout. Wait up to 20s for detection |

---

## Client Command Reference

```
Usage: java -Dminigfs.master.host=<IP> -cp out client.DemoClient <command> [args]

Commands:
  upload   <localFile> <gfsName>    Upload a local file to the cluster
  download <gfsName>  <localDest>   Download a file from the cluster
  list                              List all files stored in the cluster
  delete   <gfsName>                Delete a file from the cluster
  status                            Show cluster health and statistics

System Properties:
  -Dminigfs.master.host=<host>   Master hostname/IP (default: localhost)
  -Dminigfs.master.port=<port>   Master port (default: 6000)
```
