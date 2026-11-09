#!/usr/bin/env bash
# Switches the research id on in a realm: the README's "Configure the realm"
# steps 1, 2, 3 and 5. Safe to re-run: each step checks before it changes.
#
#   scripts/configure-realm.sh
#
# Runs kcadm.sh inside the Keycloak container, so nothing is installed locally.
# Asks for an admin login of the master realm (not stored; passed on stdin).
#
# Settings (environment):
#   REALM     realm to configure                    (default research-services)
#   CLIENTS   clients that get the research-id scope (default research-portal)
#   KC_EXEC   how to run a command in the container
#             (default: kubectl -n keycloak exec -i deploy/keycloak -c keycloak --)
#             For a local container: KC_EXEC="docker exec -i <name>"
#   KC_ADMIN_USER, KC_ADMIN_PASSWORD   skip the prompts (for tests)
#
# Not done here (README step 4): if people sign in without an identity
# provider (password forms), add the "Research id" step to that flow by hand.
set -euo pipefail

REALM="${REALM:-research-services}"
CLIENTS="${CLIENTS:-research-portal}"
FLOW="research id"
FLOW_PATH="research%20id"
SCOPE="research-id"
ATTRIBUTE="research_id"
read -r -a EXEC <<<"${KC_EXEC:-kubectl -n keycloak exec -i deploy/keycloak -c keycloak --}"

command -v jq >/dev/null || { echo "error: needs jq" >&2; exit 1; }

USER_NAME="${KC_ADMIN_USER:-}"
PASSWORD="${KC_ADMIN_PASSWORD:-}"
if { [ -z "$USER_NAME" ] || [ -z "$PASSWORD" ]; } && [ ! -t 0 ]; then
  echo "error: no terminal to ask for the admin login; run this in a terminal window" >&2
  exit 1
fi
[ -n "$USER_NAME" ] || read -r -p "Keycloak admin user (master realm): " USER_NAME
[ -n "$PASSWORD" ] || { read -r -s -p "Password: " PASSWORD; echo; }

# A kcadm config file inside the container, removed on exit.
CONFIG="/tmp/kcadm-research-id-$$.config"
kc() { "${EXEC[@]}" /opt/keycloak/bin/kcadm.sh "$@" --config "$CONFIG"; }
cleanup() { "${EXEC[@]}" rm -f "$CONFIG" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# The password travels on stdin, so it never appears on this machine's command
# lines. kcadm only reads it from a terminal or an argument, so a shell in the
# container reads stdin and passes it on.
printf '%s\n%s\n' "$USER_NAME" "$PASSWORD" | "${EXEC[@]}" bash -c \
  'IFS= read -r user; IFS= read -r password
   exec /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 \
     --realm master --user "$user" --password "$password" --config "$1"' _ "$CONFIG" >/dev/null
unset PASSWORD
kc get "realms/$REALM" --fields realm >/dev/null \
  || { echo "error: realm $REALM not found" >&2; exit 1; }
echo "Signed in; configuring realm $REALM"

# 1. Declare the attribute: viewable by the user, editable by admins only.
profile="$(kc get users/profile -r "$REALM")"
if jq -e --arg a "$ATTRIBUTE" '.attributes[] | select(.name == $a)' <<<"$profile" >/dev/null; then
  echo "1. attribute $ATTRIBUTE: already declared"
else
  jq --arg a "$ATTRIBUTE" '.attributes += [{
        name: $a, displayName: "Research id", multivalued: false,
        permissions: {view: ["admin", "user"], edit: ["admin"]}
      }]' <<<"$profile" | kc update users/profile -r "$REALM" -f - >/dev/null
  echo "1. attribute $ATTRIBUTE: declared"
fi

# 2. The flow, with the Research id step set to Required.
if kc get authentication/flows -r "$REALM" --fields alias | jq -e --arg f "$FLOW" '.[] | select(.alias == $f)' >/dev/null; then
  echo "2. flow '$FLOW': exists"
else
  kc create authentication/flows -r "$REALM" -s alias="$FLOW" -s providerId=basic-flow \
    -s topLevel=true -s builtIn=false \
    -s description="Gives each person a research id (keycloak-research-id-provider)" >/dev/null
  echo "2. flow '$FLOW': created"
fi
executions="$(kc get "authentication/flows/$FLOW_PATH/executions" -r "$REALM")"
if ! jq -e '.[] | select(.providerId == "research-id-ensure")' <<<"$executions" >/dev/null; then
  kc create "authentication/flows/$FLOW_PATH/executions/execution" -r "$REALM" \
    -s provider=research-id-ensure >/dev/null
  executions="$(kc get "authentication/flows/$FLOW_PATH/executions" -r "$REALM")"
  echo "   step 'Research id': added"
fi
step="$(jq -c '.[] | select(.providerId == "research-id-ensure")' <<<"$executions")"
if [ "$(jq -r .requirement <<<"$step")" != REQUIRED ]; then
  jq -c '.requirement = "REQUIRED"' <<<"$step" \
    | kc update "authentication/flows/$FLOW_PATH/executions" -r "$REALM" -f - >/dev/null
  echo "   step 'Research id': set to Required"
else
  echo "   step 'Research id': Required"
fi

# 3. Run the flow after every brokered sign-in, for every identity provider.
idps="$(kc get identity-provider/instances -r "$REALM" --fields alias,postBrokerLoginFlowAlias)"
if [ "$(jq length <<<"$idps")" -eq 0 ]; then
  echo "3. identity providers: none in $REALM (re-run after adding one)"
fi
for alias in $(jq -r '.[].alias' <<<"$idps"); do
  current="$(jq -r --arg a "$alias" '.[] | select(.alias == $a) | .postBrokerLoginFlowAlias // ""' <<<"$idps")"
  if [ "$current" = "$FLOW" ]; then
    echo "3. provider $alias: post login flow already '$FLOW'"
  elif [ -n "$current" ]; then
    echo "3. provider $alias: has post login flow '$current'; left alone, add the Research id step to it by hand" >&2
  else
    kc update "identity-provider/instances/$alias" -r "$REALM" -s postBrokerLoginFlowAlias="$FLOW" >/dev/null
    echo "3. provider $alias: post login flow set to '$FLOW'"
  fi
done

# 5. The research-id client scope and its token mapper, default for new clients
#    and for each listed client.
scope_id="$(kc get client-scopes -r "$REALM" --fields id,name | jq -r --arg s "$SCOPE" '.[] | select(.name == $s) | .id')"
if [ -z "$scope_id" ]; then
  kc create client-scopes -r "$REALM" -s name="$SCOPE" -s protocol=openid-connect \
    -s description="The person's research id (claim research_id)" \
    -s 'attributes."include.in.token.scope"=true' \
    -s 'attributes."display.on.consent.screen"=false' >/dev/null
  scope_id="$(kc get client-scopes -r "$REALM" --fields id,name | jq -r --arg s "$SCOPE" '.[] | select(.name == $s) | .id')"
  echo "5. client scope $SCOPE: created"
else
  echo "5. client scope $SCOPE: exists"
fi
if kc get "client-scopes/$scope_id/protocol-mappers/models" -r "$REALM" | jq -e --arg a "$ATTRIBUTE" '.[] | select(.name == $a)' >/dev/null; then
  echo "   mapper $ATTRIBUTE: exists"
else
  kc create "client-scopes/$scope_id/protocol-mappers/models" -r "$REALM" \
    -s name="$ATTRIBUTE" -s protocol=openid-connect -s protocolMapper=oidc-usermodel-attribute-mapper \
    -s "config.\"user.attribute\"=$ATTRIBUTE" -s "config.\"claim.name\"=$ATTRIBUTE" \
    -s 'config."jsonType.label"=String' \
    -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true' \
    -s 'config."userinfo.token.claim"=true' -s 'config."introspection.token.claim"=true' >/dev/null
  echo "   mapper $ATTRIBUTE: created"
fi
if kc get "realms/$REALM/default-default-client-scopes" | jq -e --arg s "$SCOPE" '.[] | select(.name == $s)' >/dev/null; then
  echo "   realm default scope: already"
else
  kc update "realms/$REALM/default-default-client-scopes/$scope_id" >/dev/null
  echo "   realm default scope: added (new clients get it)"
fi
for client in $CLIENTS; do
  cid="$(kc get clients -r "$REALM" -q clientId="$client" --fields id,clientId | jq -r --arg c "$client" '.[] | select(.clientId == $c) | .id')"
  if [ -z "$cid" ]; then
    echo "   client $client: not found in $REALM, skipped" >&2
    continue
  fi
  if kc get "clients/$cid/default-client-scopes" -r "$REALM" | jq -e --arg s "$SCOPE" '.[] | select(.name == $s)' >/dev/null; then
    echo "   client $client: has $SCOPE"
  else
    kc update "clients/$cid/default-client-scopes/$scope_id" -r "$REALM" >/dev/null
    echo "   client $client: $SCOPE added as a default scope"
  fi
done

echo "Done. Sign in through an identity provider and the token carries $ATTRIBUTE."
