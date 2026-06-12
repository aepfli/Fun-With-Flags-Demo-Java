# Strip the broken Yarn apt repo shipped in the base image. Its signing key is
# missing (NO_PUBKEY 62D54FD4003F6525), so `apt-get update` fails and any
# devcontainer feature that installs packages (e.g. docker-in-docker) dies with
# exit 100. Remove the repo so apt works again.
FROM mcr.microsoft.com/devcontainers/java:1-21

RUN rm -f /etc/apt/sources.list.d/*yarn* /usr/share/keyrings/yarn*.gpg 2>/dev/null; \
    find /etc/apt -type f \( -name '*.list' -o -name '*.sources' \) \
        -exec sed -i '/yarnpkg/d' {} + 2>/dev/null; \
    true
