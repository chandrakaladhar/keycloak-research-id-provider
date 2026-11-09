#!/usr/bin/env bash
# Builds the Keycloak image with the research id provider for a local cluster,
# and puts it on the cluster's node (local images are never pulled).
#
#   scripts/build-local.sh <tag> [docker-desktop|kind|none] [context-or-cluster-name]
#
# The image is registry.local/dashr/keycloak-research:<tag>; dashr-platform-gitops
# points Keycloak at it through keycloak_image in clusters/local/cluster-vars.yaml.
# Use a new tag for every build (for example 26.8.1-<provider version>-<commit>):
# the node keeps an image it already has under the same tag.
#
# Builds the jar first (mvn package, unit tests only; run mvn verify for the
# two-replica integration test). Behind a TLS-inspecting proxy on macOS, set
# MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=KeychainStore".
set -euo pipefail

TAG="${1:?usage: build-local.sh <tag> [docker-desktop|kind|none] [context-or-cluster-name]}"
TARGET="${2:-docker-desktop}"
NAME="${3:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${IMAGE_REPO:-registry.local/dashr/keycloak-research}:${TAG}"

case "$TAG" in latest|"") echo "error: use an explicit tag, not '$TAG'" >&2; exit 1 ;; esac

(cd "$ROOT" && mvn -q -B package)
rm -f "$ROOT"/target/*-sources.jar
[ "$(ls "$ROOT"/target/keycloak-research-id-provider-*.jar | wc -l)" -eq 1 ] \
  || { echo "error: expected exactly one provider jar in target/" >&2; exit 1; }

docker build -t "$IMAGE" "$ROOT"

case "$TARGET" in
  docker-desktop)
    # Docker Desktop's node shares the host's images; a throwaway pod that
    # starts from the image proves the node can use it without pulling.
    ctx="${NAME:-docker-desktop}" pod="kc-imgload-$$"
    trap 'kubectl --context "$ctx" -n default delete pod "$pod" --ignore-not-found --wait=false >/dev/null' EXIT
    kubectl --context "$ctx" -n default run "$pod" --image="$IMAGE" \
      --image-pull-policy=Never --restart=Never --command -- /bin/true >/dev/null
    kubectl --context "$ctx" -n default wait pod "$pod" \
      --for=jsonpath='{.status.phase}'=Succeeded --timeout=120s >/dev/null \
      || { echo "error: $IMAGE did not reach the node" >&2
           kubectl --context "$ctx" -n default get events --field-selector "involvedObject.name=$pod" >&2
           exit 1; }
    echo "Loaded $IMAGE onto $ctx"
    ;;
  kind) kind load docker-image "$IMAGE" ${NAME:+--name "$NAME"} ;;
  none) echo "Built $IMAGE" ;;
  *) echo "error: unknown target '$TARGET'" >&2; exit 1 ;;
esac
