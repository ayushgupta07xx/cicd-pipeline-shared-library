# cicd-pipeline-shared-library

A reusable Jenkins delivery pipeline, written in Groovy, that builds a
containerised service and promotes one immutable image across Kubernetes
clusters.

Consuming it takes three lines:

```groovy
@Library('finacplus-cicd@main') _

deliveryPipeline()
```

Everything else — which clusters, which branches deploy where, which runtime the
tests need, whether production requires approval — comes from the consuming
repository's `deploy-config.yaml`. No pipeline logic is duplicated per project.

## Layout
vars/deliveryPipeline.groovy          Pipeline entrypoint and stage orchestration
src/io/finacplus/cicd/DeployConfig    Parses and validates deploy-config.yaml
src/io/finacplus/cicd/Deployer        Renders manifests, applies, verifies, rolls back
`src/` holds plain Groovy classes rather than pipeline script. They receive the
`CpsScript` object by constructor so they can invoke steps, but the logic itself
is ordinary, readable Groovy — which is what "flexibility and extensibility"
means in practice.

## Stages

| Stage | Behaviour |
|---|---|
| Checkout | Resolves commit SHA and the real branch name behind Jenkins' detached-HEAD checkout |
| Load config | Parses and validates `deploy-config.yaml`; resolves which environments this branch may reach |
| Test | Runs the repository's test commands in the repository's declared image |
| Build image | Bakes build number, commit, branch and version into the artifact |
| Scan image | Trivy at the configured severity; blocks or reports per policy |
| Push image | Publishes the immutable tag; skipped when the branch targets no environment |
| Deploy | Per environment, in declared order: optional approval, apply, await rollout, smoke test |

On rollout failure: diagnostics are printed (deployment, pods, events, logs) and
`rollout undo` reverts to the previous revision automatically.

## Implementation notes worth knowing

Three constraints of the Jenkins CPS runtime shaped this code, each discovered
by a failure rather than anticipated:

**`return` inside `withCredentials`/`withEnv` closures does not return from the
enclosing method.** It returns from the closure. A method that appears to return
`false` on failure can silently return something else — a failed deploy reported
as success. Results are captured in a variable declared outside the closure.

**Closure-taking steps are unreliable when called from a `src/` class.** The
closure can arrive `null`, producing
`NullPointerException: Cannot invoke "groovy.lang.Closure.clone()"`. `Deployer`
calls no closure-taking steps; environment is exported inside the shell script
instead.

**Groovy string interpolation of a credential is an exfiltration path.** Jenkins
warns about it explicitly. The kubeconfig is dereferenced by the shell
(`$KUBECONFIG_FILE`), never by Groovy.

## Testing

```bash
./test/run-tests.sh      # unit tests for DeployConfig — no Jenkins required
```

`DeployConfig` receives the script object by constructor, so a stub is enough to
test parsing, validation, branch filtering and tag generation in isolation.

Beyond that, the library is verified end to end by two deliberately dissimilar services —
[sample-app](https://github.com/ayushgupta07xx/cicd-pipeline-sample-app)
(Python/Flask, port 8080, two replicas) and
[orders-api](https://github.com/ayushgupta07xx/cicd-pipeline-orders-api)
(Node/Express, port 3000, one replica). A change that only works for one
language is caught immediately.

That pairing has already earned its place: the Test stage originally hardcoded a
Python image and `pip install`. Onboarding a Node service proved the library was
not actually language-agnostic. The test runtime now lives in
`deploy-config.yaml`.

## Documentation

Setup, onboarding, validation procedures, monitoring and security:
[cicd-pipeline-platform/docs](https://github.com/ayushgupta07xx/cicd-pipeline-platform/tree/main/docs)
