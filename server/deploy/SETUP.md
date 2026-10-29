# Setting up the upload server

For a Debian 12 VPS with nginx and Docker already installed. The service runs in a container that only the VPS itself can reach; nginx in front of it handles HTTPS for `upload.velowind.de`. Run everything as root or with `sudo`.

## 1. DNS and certificate

- An `A` record for `upload.velowind.de` pointing to the VPS (plus `AAAA` if it has IPv6).
- A Let's Encrypt certificate for that name: `certbot certonly --nginx -d upload.velowind.de`. If certbot already saved one and only failed to install it ("could not find a matching server block"), that is fine: the site file in step 4 uses it directly, and `certbot install` is not needed.
- Make renewals reload nginx so it picks up the new certificate:

```
cat > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh <<'EOF'
#!/bin/sh
systemctl reload nginx
EOF
chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
```

## 2. Code and data folder

```
git clone https://github.com/biofe/smartphone_sensor_logger.git /opt/velowind
# A name for the container's user (uid 10001); it can't log in
groupadd --system --gid 10001 velowind
useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin velowind
mkdir -p /srv/velowind/data
# The service writes, your own group can read (for rsync), other accounts on the VPS see nothing
chown velowind:<your group> /srv/velowind/data
chmod 750 /srv/velowind/data
```

Use your own account with `sudo` for all of this, not the website's deploy user. Don't add other users to the `docker` group: access to Docker is the same as root.

The repository is private, so the clone needs a GitHub login or a deploy key. Copying the `server/` folder over with `scp` works too.

## 3. Start the service

```
cd /opt/velowind/server
docker compose -f deploy/docker-compose.yml up -d --build
curl http://127.0.0.1:8737/v1/health
```

The answer should be `{"status":"ok","min_app_version":"0.8.0"}`. The container restarts by itself after a reboot. If port 8737 is taken, change it in `docker-compose.yml` and in the nginx file.

## 4. nginx site

```
cp deploy/nginx-upload.conf /etc/nginx/sites-available/upload.velowind.de
ln -s /etc/nginx/sites-available/upload.velowind.de /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
curl https://upload.velowind.de/v1/health
```

`nginx -t` must say the test is successful before the reload. The site has no access log, so IP addresses aren't kept there.

## 5. Daily use

```
cd /opt/velowind/server
docker compose -f deploy/docker-compose.yml exec upload python -m velowind_upload.admin report
docker compose -f deploy/docker-compose.yml exec upload python -m velowind_upload.admin promote --all
docker compose -f deploy/docker-compose.yml logs --tail 50
```

A shorter alias helps: `alias velowind='docker compose -f /opt/velowind/server/deploy/docker-compose.yml exec upload python -m velowind_upload.admin'`, then `velowind report`. All commands are listed in the [server README](../README.md).

Pull the dataset to your laptop:

```
rsync -av <you>@<vps>:/srv/velowind/data/dataset/ ./data/server/dataset/
rsync -av <you>@<vps>:/srv/velowind/data/map_annotations/ ./data/server/map_annotations/
```

## 6. Updates and backups

Update after a change in the repository:

```
cd /opt/velowind && git pull
cd server && docker compose -f deploy/docker-compose.yml up -d --build
```

Back up `/srv/velowind/data/` (the `dataset/` folder and `index.sqlite3` are what matter). `quarantine/` and `rejected/` can be rebuilt from new uploads.

## 7. App updates: apk.velowind.de

The app looks for new versions at `https://apk.velowind.de/latest.json`. nginx serves that file and the APKs from `/srv/velowind/publish/apk`; GitHub Actions copies them there over SFTP when a version is tagged ([docs/RELEASES.md](../../docs/RELEASES.md)). The account it uses can only put files into that one folder: no shell, no other files.

DNS and certificate, as in step 1:

```
certbot certonly --nginx -d apk.velowind.de
```

Upload account and folder. SFTP's chroot needs a root-owned folder above the writable one:

```
useradd --system --create-home --home-dir /home/velowind-publish --shell /usr/sbin/nologin velowind-publish
mkdir -p /srv/velowind/publish/apk
chown root:root /srv/velowind/publish
chmod 755 /srv/velowind/publish
chown velowind-publish:velowind-publish /srv/velowind/publish/apk
chmod 755 /srv/velowind/publish/apk
```

Add this at the **end** of `/etc/ssh/sshd_config` (a `Match` block applies to everything below it, so it must come last):

```
Match User velowind-publish
    ForceCommand internal-sftp
    ChrootDirectory /srv/velowind/publish
    PasswordAuthentication no
    AllowTcpForwarding no
    X11Forwarding no
    PermitTTY no
```

If `sshd_config` has an `AllowUsers` or `AllowGroups` line, add `velowind-publish` to it. Then check and reload, keeping your current SSH session open until a new login works:

```
sshd -t && systemctl reload ssh
```

(Debian's default `UsePAM yes` lets this account log in with a key although it has no password. If you set `UsePAM no`, also run `usermod -p '*' velowind-publish`.)

A key pair for GitHub Actions, made on the VPS. The private half goes into the `VPS_SSH_KEY` secret, then is deleted here:

```
ssh-keygen -t ed25519 -N "" -C "github-actions publish" -f /root/velowind-publish
install -d -m 700 -o velowind-publish -g velowind-publish /home/velowind-publish/.ssh
install -m 600 -o velowind-publish -g velowind-publish /root/velowind-publish.pub /home/velowind-publish/.ssh/authorized_keys
cat /root/velowind-publish     # paste into the VPS_SSH_KEY secret
rm /root/velowind-publish /root/velowind-publish.pub
```

For `VPS_KNOWN_HOSTS`, run `ssh-keyscan -t ed25519 <host>` on your own computer (add `-p <port>` if SSH isn't on 22), with `<host>` exactly as in `VPS_HOST`. The site:

```
cp deploy/nginx-apk.conf /etc/nginx/sites-available/apk.velowind.de
ln -s /etc/nginx/sites-available/apk.velowind.de /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
```

`https://apk.velowind.de/latest.json` answers 404 until the first release is copied. `https://apk.velowind.de/` always leads to the newest APK, so that is the link for new riders.
