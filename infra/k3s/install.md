# k3s

Ubuntu 22.04 or newer on every node, kernel 5.15 or newer for Cilium. Open these ports between
nodes: 6443/tcp (API), 4240/tcp (Cilium health), 8472/udp (VXLAN), and 80/tcp plus 443/tcp
from the internet on nodes that run the gateway.

Every node runs the same pinned k3s release, `v1.34.8+k3s1`, the one Liftgate Cloud runs.

## Kubelet limits

On every node, before installing k3s:

```sh
sudo mkdir -p /etc/rancher/k3s
sudo tee /etc/rancher/k3s/config.yaml > /dev/null <<'EOF'
kubelet-arg:
  - pod-max-pids=4096
  - system-reserved=cpu=500m,memory=1Gi
  - kube-reserved=cpu=500m,memory=1Gi
  - eviction-hard=memory.available<500Mi,nodefs.available<10%,imagefs.available<15%,nodefs.inodesFree<5%,imagefs.inodesFree<5%
EOF
```

`pod-max-pids` caps the processes in each pod's cgroup. The reservations keep CPU and memory
for the OS, k3s and containerd out of what pods can request. Setting any `eviction-hard` signal
drops the kubelet's defaults for the others, so all of them are listed. On a node that already
runs k3s, restart `k3s` or `k3s-agent` after writing the file.

## Server

```sh
curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION=v1.34.8+k3s1 INSTALL_K3S_EXEC='server --flannel-backend=none --disable-network-policy --disable-kube-proxy --disable=traefik --disable=servicelb' sh -
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
kubectl get nodes
```

The node reports `NotReady` until Cilium is installed by `infra/install.sh`; that is expected.

Flannel, the network policy controller and kube-proxy are replaced by Cilium. Traefik is
replaced by the Cilium Gateway. ServiceLB is unnecessary because the gateway listens on the
host network.

## Agents

On the server:

```sh
cat /var/lib/rancher/k3s/server/node-token
```

On each agent:

```sh
curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION=v1.34.8+k3s1 K3S_URL=https://SERVER_IP:6443 K3S_TOKEN=NODE_TOKEN sh -
```

## gVisor on every node

```sh
sudo apt-get update && sudo apt-get install -y apt-transport-https ca-certificates curl gnupg
curl -fsSL https://gvisor.dev/archive.key | sudo gpg --dearmor -o /usr/share/keyrings/gvisor-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/gvisor-archive-keyring.gpg] https://storage.googleapis.com/gvisor/releases release main" | sudo tee /etc/apt/sources.list.d/gvisor.list > /dev/null
sudo apt-get update && sudo apt-get install -y runsc
sudo install -m 0644 infra/gvisor/config-v3.toml.tmpl /var/lib/rancher/k3s/agent/etc/containerd/config-v3.toml.tmpl
sudo systemctl restart k3s
```

Use `systemctl restart k3s-agent` on agents. k3s does not detect `runsc` on its own, so the
template adds the `runsc` handler on top of the generated containerd configuration; the
RuntimeClass `gvisor` applied by `install.sh` points at it. The chart runs tenant pods under
`runtimeClass: gvisor` by default and refuses to render with an empty `runtimeClass` unless
`allowUnsandboxedTenants=true`.

## Upgrades

k3s upgrades in place with the same install command and a newer `INSTALL_K3S_VERSION`, one
minor version at a time, server first. Reapply the containerd template after any k3s upgrade
that changes the containerd major version.

## Adding a Firecracker node

A dedicated build node keeps untrusted build steps off the machine that holds Postgres, the
control plane and `secrets.masterKey`. These steps join a second Firecracker microVM to a k3s
server that already runs in one. The addresses are examples: the server VM is `10.200.0.2`
behind tap `lgfc0` (host side `10.200.0.1`), and the registry listens on the host at
`10.200.0.1:5050`.

1. Start the VM under the jailer with its own `--id`, rootfs, MAC and tap device, and give the
   tap its own /30 on the host:

   ```sh
   ip tuntap add dev lgfc1 mode tap user firecracker group firecracker
   ip addr replace 10.200.0.5/30 dev lgfc1
   ip link set lgfc1 up
   ```

   Inside the VM, use `10.200.0.6/30` with gateway `10.200.0.5`.
2. On the host firewall, let the VMs reach each other only on the k3s ports: 6443/tcp from the
   new node to the server, and 10250/tcp, 8472/udp (Cilium VXLAN), 4240/tcp and ICMP echo
   (Cilium health) both ways. Give the new VM the same registry port and NATed internet egress
   as the first, and keep dropping everything else it sends to host addresses, public or
   private.
3. In the VM, write the kubelet limits above and the registry mirror, then join as an agent
   with the build pool label and taint:

   ```sh
   sudo tee /etc/rancher/k3s/registries.yaml > /dev/null <<'EOF'
   mirrors:
     "10.200.0.1:5050":
       endpoint:
         - "http://10.200.0.1:5050"
   EOF
   curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION=v1.34.8+k3s1 K3S_URL=https://10.200.0.2:6443 K3S_TOKEN=NODE_TOKEN \
     INSTALL_K3S_EXEC='agent --node-label=liftgate.dev/pool=build --node-taint=liftgate.dev/pool=build:NoSchedule' sh -
   ```
4. Install gVisor on it as described above, restarting `k3s-agent`.
5. `kubectl get nodes -L liftgate.dev/pool` lists the node in the `build` pool. The taint keeps
   tenant pods, the control plane, Postgres and NATS off it; DaemonSets that tolerate every
   taint, such as Cilium, still run there.
