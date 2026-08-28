# Deployment environments

Environment contracts in this directory are committed to both GitHub and the
internal GitLab so release inputs can be reviewed with the application code.
They must contain only non-secret routing and policy values.

## SIT

`sit.env` is selected by the `DEPLOY_ENV=SIT` Jenkins parameter. Its ITDB
variables deliberately use the `_SIT` suffix. During the release Jenkins maps
them to the unsuffixed Spring Boot variables consumed by the application.

The ITDB reviewer credential is **not** stored in this repository or on the
application host. Create one Jenkins username/password credential with this
exact ID:

```text
mateclaw-itdb-sit
```

Jenkins exposes it only inside the scoped validation and cutover steps as:

```text
MATECLAW_ITDB_SIT_USR
MATECLAW_ITDB_SIT_PSW
```

The pipeline first authenticates against the configured ITDB JWT endpoint. A
missing credential, failed login, redirect, environment mismatch, or modified
environment contract stops the release before the current container is
replaced.

Do not add usernames, passwords, access tokens, refresh tokens, API keys, or
other secrets to files in this directory.
