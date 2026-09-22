# The Inkstone reader, as a container.
#
#   docker build -f tools/web/Dockerfile -t inkstone-reader .
#
# Build from the REPO ROOT, not from this directory: the image needs `tools/web` and `tools/m12`
# (the reader imports the M12 WebDAV client rather than speaking WebDAV a second time), and a
# Dockerfile cannot reach above its build context.
#
# ---------------------------------------------------------------------------
# Why python:3.14-slim-trixie and not alpine or distroless
# ---------------------------------------------------------------------------
#
# This app has **no pip dependencies at all**, so the usual argument for slim over alpine — musl
# has no manylinux wheels, so everything compiles — does not apply. The argument that does:
#
# ⚠️ **musl's DNS resolver can mis-demultiplex parallel A/AAAA queries**, returning spurious
# NXDOMAIN or SERVFAIL under load, and TCP fallback only arrived in musl 1.2.4. This process makes
# outbound HTTPS calls to a Nextcloud for everything it does. A name resolution that fails
# occasionally and unreproducibly is the worst possible bug to hand a tool whose job is to be
# trusted about whether your lessons are there.
#
# Distroless is genuinely smaller in attack surface (~10 packages against ~100) and was the other
# real candidate. It is not used because it has no shell: `docker exec` cannot get in to look at
# the cache volume or read a traceback, which for a personal tool that will be debugged at most
# twice a year is the wrong trade. Revisit if this ever becomes something other people run.
FROM python:3.14-slim-trixie

# ⚠️ A user declared with no primary group runs with the **root group**, which quietly undoes
# most of the point. Both are named here.
RUN groupadd --system --gid 10001 inkstone \
 && useradd --system --uid 10001 --gid inkstone --home-dir /app --no-create-home inkstone

WORKDIR /app

# Only what the server needs. `tools/m12` is not optional — `source.py` imports its WebDAV client
# and `timeline.py` imports its timeline parser, deliberately, so that neither exists twice.
COPY --chown=inkstone:inkstone tools/web/ /app/tools/web/
COPY --chown=inkstone:inkstone tools/m12/nextcloud.py tools/m12/align.py /app/tools/m12/

# ⚠️ Created and chowned **at build time**, and NOT declared as a `VOLUME`.
#
# Mounting an empty named volume over a populated image directory copies that directory's
# contents *and its ownership* into the volume — which is the only clean way to end up with a
# volume the non-root user can write to. Docker has no supported way to set a volume's initial
# ownership (the feature request has been open and untriaged since 2023), so this is the idiom
# rather than a trick, and it is why there is no chown-on-start in an entrypoint.
#
# `VOLUME` is deliberately absent: any change a later build step makes below a declared VOLUME
# path is discarded, and declaring it buys nothing that `compose.yaml` does not say more clearly.
RUN mkdir -p /app/cache && chown -R inkstone:inkstone /app/cache

USER inkstone:inkstone

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    INKSTONE_CACHE=/app/cache

EXPOSE 8765

# The image carries no curl and no wget — but it does carry a Python interpreter, which is enough.
# Exit 0 healthy, 1 unhealthy; **never 2**, which Docker reserves.
#
# `/healthz` is deliberately the probe rather than `/`: it is the route that says whether the
# *source* is reachable, so a container whose Nextcloud credential has died reports unhealthy
# rather than merely serving a page that cannot list anything.
# ⚠️ One line. A Dockerfile continuation (`\` + newline) inside a JSON string works — the
# continuation is stripped before the JSON is parsed — but it is unreadable, and a probe that
# fails to parse takes the build with it. Not worth the cleverness for eighty characters.
HEALTHCHECK --interval=60s --timeout=5s --start-period=10s --retries=3 CMD ["python", "/app/tools/web/healthcheck.py"]

# ⚠️ Exec form, and `server.py` installs its own SIGTERM handler — see `_install_sigterm`.
# As PID 1 a process gets no default signal behaviour, and Python installs no SIGTERM handler of
# its own, so without that `docker stop` would wait the full ten seconds and then SIGKILL.
# `--init`/tini is the usual advice and solves a different problem (forwarding signals into a
# multi-process container); this is one process and handles its own.
STOPSIGNAL SIGTERM

CMD ["python", "/app/tools/web/server.py", "--host", "0.0.0.0", "--port", "8765", "--cache", "/app/cache"]
