# FRPS Server

Deploy these files on the public server.

## Start

```bash
cd /path/to/.docker
docker compose -f docker-compose.frps.yml up -d
docker logs -f frps
```

## Required Public Ports

- `7000/tcp`: frpc connects to frps.
- `8888/tcp`: public Sonic Agent tunnel, mapped by frpc `remotePort`.
- `7500/tcp`: frps dashboard. Restrict this port to your own IP if possible.

## Matching frpc Example

Use this on the internal network machine that can reach Sonic Agent.

```toml
serverAddr = "YOUR_PUBLIC_SERVER_IP"
serverPort = 7000

auth.method = "token"
auth.token = "CHANGE_ME_STRONG_FRP_TOKEN"

[[proxies]]
name = "sonic-agent"
type = "tcp"
localIP = "192.168.10.30"
localPort = 8888
remotePort = 8888
```

Then set Sonic Agent to report the public address:

```yaml
sonic:
  agent:
    host: YOUR_PUBLIC_SERVER_IP
    port: 8888
```
