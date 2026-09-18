# k3s

Ubuntu 22.04 or newer on every node, kernel 5.15 or newer for Cilium. Open these ports between
nodes: 6443/tcp (API), 4240/tcp (Cilium health), 8472/udp (VXLAN), and 80/tcp plus 443/tcp
from the internet on nodes that run the gateway.

## Server

```sh
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC='server --flannel-backend=none --disable-network-policy --disable-kube-proxy --disable=traefik --disable=servicelb' sh -
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
curl -sfL https://get.k3s.io | K3S_URL=https://SERVER_IP:6443 K3S_TOKEN=NODE_TOKEN sh -
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
RuntimeClass `gvisor` applied by `install.sh` points at it. Set `runtimeClass=gvisor` on the
chart to sandbox tenant pods.

## Upgrades

k3s upgrades in place with the same install command. Reapply the containerd template after
any k3s upgrade that changes the containerd major version.
