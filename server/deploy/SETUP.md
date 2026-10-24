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
