# Init scripts are copied in, not bind-mounted, so compose also works against a remote Docker/Podman daemon.
# They run only when the data volume is empty.
FROM docker.io/library/postgres:17-alpine
COPY init/ /docker-entrypoint-initdb.d/
