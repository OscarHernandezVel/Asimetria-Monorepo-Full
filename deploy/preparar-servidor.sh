#!/bin/sh
# Prepara un VPS Ubuntu 24.04 recién creado (ejecutar UNA vez, como root):
#   curl -fsSL https://raw.githubusercontent.com/OscarHernandezVel/Asimetria-Monorepo/main/deploy/preparar-servidor.sh -o p.sh
#   less p.sh   # revísalo antes de ejecutarlo
#   sh p.sh
set -eu

echo "→ Actualizaciones de seguridad automáticas"
apt-get update -q && apt-get upgrade -yq
apt-get install -yq unattended-upgrades ufw git fail2ban
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "→ Docker (repositorio oficial)"
command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh

echo "→ Usuario 'deploy' sin contraseña (solo clave SSH), con permiso para Docker"
id deploy >/dev/null 2>&1 || adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
[ -f /root/.ssh/authorized_keys ] && install -m 600 -o deploy -g deploy /root/.ssh/authorized_keys /home/deploy/.ssh/authorized_keys

echo "→ SSH: sin contraseñas ni login de root"
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/; s/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
systemctl reload ssh || systemctl reload sshd

echo "→ Cortafuegos: solo SSH, HTTP y HTTPS (TCP + UDP para HTTP/3)"
ufw default deny incoming && ufw default allow outgoing
ufw allow OpenSSH && ufw allow 80/tcp && ufw allow 443/tcp && ufw allow 443/udp
ufw --force enable

echo "→ Código en /opt/asimetria"
install -d -o deploy -g deploy /opt/asimetria
[ -d /opt/asimetria/.git ] || sudo -u deploy git clone https://github.com/OscarHernandezVel/Asimetria-Monorepo.git /opt/asimetria

echo "✓ Listo. Siguiente paso: crear /opt/asimetria/.env (docs/DESPLIEGUE.md §5) como usuario deploy."
