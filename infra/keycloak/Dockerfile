# The realm file is copied in, not bind-mounted, so compose also works against a remote Docker/Podman daemon.
# "start-dev --import-realm" imports it only if the realm does not exist yet.
FROM quay.io/keycloak/keycloak:26.8.1
COPY zk-spring-realm.json /opt/keycloak/data/import/
