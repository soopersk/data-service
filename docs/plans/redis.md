I'll research the current recommended approach and library versions before writing this up, since Azure's passwordless Redis authentication libraries have been evolving.Good context. Let me verify the Workload Identity integration specifics and the token-refresh mechanism in Lettuce.Let me confirm the AKS Workload Identity setup specifics and the dedicated Entra ID auth library for Redis.This is a topic where the recommended libraries shifted recently, so the most important thing to get right up front: as of 2026, Microsoft and Redis have **moved Entra ID authentication for Redis out of the Azure SDK** and into a dedicated Redis library. Microsoft entered into a partnership with Redis, Inc., and as part of it, Microsoft Entra ID authentication support was moved from the Azure SDK to Redis Entra ID extensions; the new redis-authx-entraid library is the recommended approach for Microsoft Entra ID authentication. That changes what the "best practice" answer looks like compared to older guides.

Below is the full guide built around that current approach, with the simpler Spring Cloud Azure alternative noted at the end so you can choose deliberately.

## Architecture Decision: Which Library Stack

You have two viable, fully passwordless paths. Both work with Workload Identity because both ultimately resolve `DefaultAzureCredential`, and DefaultAzureCredential selects WorkloadIdentityCredential when the environment variable configuration is set by the Azure workload identity webhook.

- **Path A — `redis-authx-entraid` + `azure-identity` (recommended here).** You own the `LettuceConnectionFactory` and wire a token credentials provider that the Lettuce driver re-authenticates automatically. This is the explicit control you asked for, and it is the path Microsoft/Redis now steer people toward.
- **Path B — Spring Cloud Azure starter.** Lower code: `spring-cloud-azure-starter-data-redis-lettuce` with `passwordless-enabled=true` does all of this for you. Good if you want zero custom config; covered briefly at the end.

The rest of the guide implements **Path A**.

One critical correction to a common assumption: do **not** reach for the old `azure-spring-data-redis`/Azure-SDK token-credential wiring as the primary mechanism anymore — token acquisition and refresh now belongs to `redis-authx-entraid`, with `azure-identity` supplying only the credential. You still use `spring-boot-starter-data-redis` (Lettuce) as the base.

## 1. Infrastructure & Azure Configuration

Three things must be true in Azure.

**a) Enable Entra ID auth on the cache.** On the Azure Cache for Redis (Premium) or Azure Managed Redis (Enterprise) instance, enable Microsoft Entra Authentication. To use Microsoft Entra authentication you enable it and select the identity to assign a Data Owner access policy.

**b) Create a user-assigned Managed Identity and assign a Redis data access policy.** Use a **user-assigned** MI rather than system-assigned — the token/identity-resolution behavior is more reliable for this scenario, and a known pitfall is that acquiring a token via DefaultAzureCredential works for a user-assigned managed identity but can fail for a system-assigned one.

```bash
# 1. User-assigned managed identity
az identity create -g <rg> -n redis-app-identity
# capture: clientId, principalId (objectId), id

# 2. Assign a built-in Redis data access policy to the MI's OBJECT ID.
#    Built-in policies: "Data Owner", "Data Contributor", "Data Reader".
#    Use least privilege — most apps need Data Contributor, not Owner.
az redis access-policy-assignment create \
  -g <rg> --cache-name <redis-name> \
  --access-policy-name "Data Contributor" \
  --object-id <MI-principal-objectId> \
  --object-id-alias "redis-app-identity" \
  --policy-assignment-name "redis-app-assignment"
```

The MI's **object (principal) ID** becomes the Redis ACL username. With the `redis-authx-entraid` provider you do **not** hardcode it — the library extracts the username from the token's `oid` claim, which is one of the reasons to prefer it.

**c) Federate the MI to the Kubernetes ServiceAccount.** This is what makes Workload Identity work — it tells Entra ID to trust tokens minted by your AKS cluster's OIDC issuer for that specific SA.

```bash
export AKS_OIDC_ISSUER=$(az aks show -g <rg> -n <aks> \
  --query "oidcIssuerProfile.issuerUrl" -o tsv)

az identity federated-credential create \
  --name redis-fic \
  --identity-name redis-app-identity \
  --resource-group <rg> \
  --issuer "$AKS_OIDC_ISSUER" \
  --subject "system:serviceaccount:<namespace>:redis-app-sa" \
  --audience api://AzureADTokenExchange
```

(Ensure the AKS cluster has `--enable-oidc-issuer` and `--enable-workload-identity`.)

## 2. Kubernetes Manifests

The webhook keys off one annotation on the ServiceAccount and one label on the pod template. The annotation’s value is the MI’s **client ID** (not object ID).

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: redis-app-sa
  namespace: my-namespace
  annotations:
    # Client ID of the user-assigned managed identity.
    azure.workload.identity/client-id: "<MI-CLIENT-ID>"
    # Optional, recommended for multi-tenant clarity:
    azure.workload.identity/tenant-id: "<TENANT-ID>"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis-app
  namespace: my-namespace
spec:
  replicas: 2
  selector:
    matchLabels:
      app: redis-app
  template:
    metadata:
      labels:
        app: redis-app
        # REQUIRED — without this label the webhook injects nothing.
        azure.workload.identity/use: "true"
    spec:
      serviceAccountName: redis-app-sa      # binds the pod to the federated SA
      containers:
        - name: redis-app
          image: <your-registry>/redis-app:latest
          ports:
            - containerPort: 8080
```

When the webhook sees the label, it projects a federated token file into the pod and injects `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE`, and `AZURE_AUTHORITY_HOST`. `DefaultAzureCredential` reads these automatically — no app config needed for the credential itself.

## 3. Dependencies

Use `spring-boot-starter-data-redis` (Lettuce) as the base, `azure-identity` for the credential, and `redis-authx-entraid` for token acquisition + automatic re-authentication. The streaming re-auth mechanism is important: Microsoft Entra ID tokens have a limited lifetime, and Lettuce 6.6.0 extends RedisCredentialsProvider to support streaming credentials, so a connection is re-authenticated automatically when new credentials are emitted and ReauthenticateBehavior is set to ON_NEW_CREDENTIALS.

Make sure your Spring Boot version (3.3+/3.4+) resolves **Lettuce ≥ 6.6.0**; if not, pin it explicitly.

```xml
<dependencies>
  <!-- Base Redis (pulls in Lettuce) -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
  </dependency>

  <!-- Supplies the credential; on AKS resolves WorkloadIdentityCredential -->
  <dependency>
    <groupId>com.azure</groupId>
    <artifactId>azure-identity</artifactId>
    <version>1.15.0</version>
  </dependency>

  <!-- Token acquisition + automatic refresh/re-auth for Jedis & Lettuce -->
  <dependency>
    <groupId>redis.clients.authentication</groupId>
    <artifactId>redis-authx-entraid</artifactId>
    <version>0.1.1-beta1</version>
  </dependency>
</dependencies>

<!-- Pin Lettuce if your Boot BOM is older than 6.6.0 -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.lettuce</groupId>
      <artifactId>lettuce-core</artifactId>
      <version>6.6.0.RELEASE</version>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Gradle equivalent:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
implementation 'com.azure:azure-identity:1.15.0'
implementation 'redis.clients.authentication:redis-authx-entraid:0.1.1-beta1'
// if needed:
implementation 'io.lettuce:lettuce-core:6.6.0.RELEASE'
```

Note `redis-authx-entraid` is still beta (`0.1.1-beta1`), so pin the version and verify the exact builder method names against the README of the version you pin — beta signatures can shift.

## 4. Spring Configuration & Java Code

### `application.yml`

You only configure host/port/TLS. No username, no password, no connection string — the credential and ACL username come from the token.

```yaml
spring:
  data:
    redis:
      host: ${AZURE_CACHE_REDIS_HOST}   # <name>.redis.cache.windows.net
      port: 6380                        # TLS port for Azure Cache for Redis
      ssl:
        enabled: true
      timeout: 5s
```

### Java configuration

The key idea, in comments: the `TokenBasedRedisCredentialsProvider` *streams* fresh tokens on a schedule derived from the token lifetime; setting `ReauthenticateBehavior.ON_NEW_CREDENTIALS` makes Lettuce silently re-`AUTH` the existing live connection each time a new token arrives — no reconnect, no request fails at the expiry boundary.

```java
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    // Azure resource scope for Redis data-plane tokens.
    private static final String REDIS_SCOPE = "https://redis.azure.com/.default";

    /**
     * Token provider backed by DefaultAzureCredential.
     * On AKS, DefaultAzureCredential walks its chain and resolves
     * WorkloadIdentityCredential from the env vars injected by the
     * workload-identity webhook (AZURE_CLIENT_ID / AZURE_TENANT_ID /
     * AZURE_FEDERATED_TOKEN_FILE). No secret material lives anywhere.
     *
     * The provider acquires the first token eagerly and then refreshes
     * PROACTIVELY before expiry — it does not wait for a token to die.
     */
    @Bean(destroyMethod = "close") // release the provider's renewal scheduler on shutdown
    public TokenBasedRedisCredentialsProvider redisCredentialsProvider() {

        TokenAuthConfig tokenAuthConfig = AzureTokenAuthConfigBuilder.builder()
                .defaultAzureCredential(new DefaultAzureCredentialBuilder().build())
                .scopes(Set.of(REDIS_SCOPE))
                // Refresh once 75% of the token lifetime has elapsed...
                .expirationRefreshRatio(0.75F)
                // ...and never operate within this safety margin of expiry.
                .lowerRefreshBoundMillis(2 * 60 * 1000) // 2 minutes
                .build();

        // The library extracts the ACL username from the token's `oid` claim,
        // so you never hardcode the managed identity's object ID.
        return TokenBasedRedisCredentialsProvider.create(tokenAuthConfig);
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            TokenBasedRedisCredentialsProvider credentialsProvider) {

        RedisStandaloneConfiguration serverConfig =
                new RedisStandaloneConfiguration(host, port);

        ClientOptions clientOptions = ClientOptions.builder()
                // THE line that makes long-lived connections survive token rotation:
                // when the provider streams a new token, Lettuce re-AUTHs in place.
                .reauthenticateBehavior(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS)
                .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .useSsl().and()                       // required: Azure Redis on 6380
                .clientOptions(clientOptions)
                // Hand Spring Data the streaming provider; it bridges into Lettuce.
                .redisCredentialsProviderFactory(uri -> credentialsProvider)
                .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
```

Required imports come from `com.azure.identity.*`, `redis.clients.authentication.core.*`, `redis.clients.authentication.entraid.*`, `io.lettuce.core.ClientOptions`, and the Spring Data Redis `org.springframework.data.redis.connection.lettuce.*` package.

## How Token Refresh Is Actually Handled (the part that matters in prod)

There are two distinct refresh layers, and getting both is what makes this safe:

The **provider layer** (`redis-authx-entraid`) runs a background scheduler that re-requests a token from `DefaultAzureCredential` once `expirationRefreshRatio` of the lifetime has passed, well before expiry. The **driver layer** (Lettuce streaming credentials + `ON_NEW_CREDENTIALS`) consumes each emitted token and re-authenticates open connections in place. Without the driver-layer behavior, a new token would only apply to *new* connections, and a long-lived pooled connection would eventually fail mid-request when its original token expired. Together they mean a connection opened at startup keeps working indefinitely across many token rotations.

## Production Caveats

Prefer **user-assigned** MI (per the token-resolution pitfall above). Keep the data access policy at **least privilege** — `Data Contributor` over `Data Owner` for typical app workloads.

Watch one known issue when combining the beta provider with Workload Identity: there has been a reported case where using the Azure identity provider with WorkloadIdentityCredential caused high CPU usage and excessive object allocation in a token-refresh busy-wait, due to a discrepancy between MSAL's built-in caching and the provider's renewal scheduler. Pin a current `redis-authx-entraid` version that includes the fix, set a sane `expirationRefreshRatio` (don't set it pathologically high), and load-test before rollout. If you hit it and can't resolve it, that's a strong reason to fall back to Path B.

Lock down the network too: TLS on 6380 (shown above), plus Private Endpoint / firewall rules so the cache isn't publicly reachable. Workload Identity protects authentication; it doesn't substitute for network isolation.

## Alternative: Spring Cloud Azure Starter (less code)

If you'd rather not own the connection factory, the starter does the token wiring for you and also works with Workload Identity (it builds a credential from your auth config and supports passwordless). You add spring-boot-starter-data-redis together with com.azure.spring:spring-cloud-azure-starter-data-redis-lettuce (managed by the spring-cloud-azure-dependencies BOM); passwordless connections have been supported since version 5.13.0. Then in config:

```yaml
spring:
  data:
    redis:
      host: ${AZURE_CACHE_REDIS_HOST}
      port: 6380
      username: <MI-object-id>   # the Entra username for the cache
      ssl:
        enabled: true
      azure:
        passwordless-enabled: true
```

The trade-off: less control over refresh timing and provider internals, and you're relying on the starter's chosen credential resolution. For the "explicit, I-own-the-refresh-logic" requirement you stated, Path A is the better fit; Path B is the pragmatic low-code option.

A couple of things I'd confirm against your exact pinned versions before shipping, since `redis-authx-entraid` is pre-1.0: the precise builder method names on `AzureTokenAuthConfigBuilder` (e.g. whether refresh tuning is on the builder vs a `TokenManagerConfig`), and that your resolved Lettuce version exposes `ClientOptions.ReauthenticateBehavior`. Both are stable in concept but beta APIs rename things.
---

Explain this option: Alternative: Spring Cloud Azure Starter. What is username: <MI-object-id> # the Entra username for the cache ?
---
Good question to nail down — this field is a classic source of confusion because Azure hands you several different IDs and only one works here. Let me verify the current specifics before explaining.Short version: `username` is the **Redis ACL user**, and for Entra-authenticated Redis that user is the **Object ID (a.k.a. principal ID) of your managed identity** — not a human-readable name, not a connection string. The token is the password; the username tells the Redis server *which* identity that token belongs to.

Here's the mechanic. Azure Cache for Redis implements Entra auth on top of standard Redis ACLs. When the client connects it issues a Redis `AUTH <username> <password>` command, and Azure expects User = the Object ID of your managed identity or service principal, and Password = the Microsoft Entra token acquired via MSAL. The server looks up the ACL user matching that Object ID, validates the token, and checks the access policy you assigned (Data Reader/Contributor/Owner) to decide what commands you're allowed to run. So the username isn't decorative — it's the key the server uses to find your access policy.

**The trap to avoid:** Azure gives you several IDs for a managed identity, and they are easy to mix up:

- **Client ID** — goes in the Kubernetes ServiceAccount annotation (`azure.workload.identity/client-id`). Used for *acquiring* the token.
- **Object ID / Principal ID** — goes in `spring.data.redis.username`. Used for *identifying* you to Redis.

These are different GUIDs for the same identity. If you put the client ID in the username field, the connection fails with `WRONGPASS invalid username-password pair` even though the token itself is perfectly valid — a real and common failure people hit. The token was minted fine; Redis just can't find an ACL user with that ID.

Where to get the Object ID:

```bash
az identity show -g <rg> -n redis-app-identity --query principalId -o tsv
```

(That `principalId` is the same value the portal shows as "Object (principal) ID" on the identity's overview, and the same value you used when assigning the Redis data access policy.)

**Why Path A didn't need this field at all.** This is the meaningful difference between the two approaches. In Path B (the Spring Cloud Azure starter), *you* supply the username explicitly because the starter just plugs your configured value into the AUTH command. In Path A, the `redis-authx-entraid` library decodes the token it just acquired and pulls the Object ID out of the token's `oid` claim automatically, then uses that as the username — so there's nothing to configure and nothing to get wrong. One less GUID to copy-paste correctly, which is a small but real reliability win for Path A.

One caveat worth flagging: hardcoding a raw Object ID in `application.yml` is fine functionally, but it bakes an environment-specific identifier into config. If you go with Path B, source it from an environment variable (e.g. `username: ${AZURE_REDIS_USERNAME}`) so the same artifact works across dev/staging/prod with different identities.
