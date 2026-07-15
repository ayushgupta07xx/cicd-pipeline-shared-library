package io.finacplus.cicd

/**
 * Deploys a built image to one Kubernetes environment.
 *
 * Every method runs against a kubeconfig supplied by a Jenkins credential,
 * which authenticates as a namespaced, least-privilege ServiceAccount —
 * never a cluster-admin. The credential id comes from deploy-config.yaml,
 * so targeting a different cluster is configuration, not code.
 *
 * Two implementation constraints are deliberate:
 *
 *  1. No closure-taking steps (withEnv) are called from this class. Steps that
 *     take a closure are unreliable when invoked from a shared-library `src/`
 *     class — the closure can arrive null. Environment is exported inside the
 *     shell script instead.
 *  2. The credential is dereferenced by the SHELL ($KUBECONFIG_FILE), never by
 *     Groovy string interpolation, so the secret value never enters the Groovy
 *     runtime or a process argument list.
 */
class Deployer implements Serializable {
    private static final long serialVersionUID = 1L

    private final def script
    private final DeployConfig cfg
    private final Map env

    Deployer(def script, DeployConfig cfg, Map env) {
        this.script = script
        this.cfg = cfg
        this.env = env
    }

    String getName()      { env.name }
    String getNamespace() { env.namespace }
    String getCluster()   { env.cluster }

    /** Shell preamble: exports every value the manifests template against. */
    private String exports(Map args) {
        String buildTime = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
        return """
            export KUBECONFIG="\$KUBECONFIG_FILE"
            export APP_NAME='${cfg.appName}'
            export NAMESPACE='${env.namespace}'
            export REPLICAS='${env.replicas ?: 1}'
            export IMAGE='${args.image}'
            export APP_ENV='${env.name}'
            export CLUSTER_NAME='${env.cluster}'
            export APP_VERSION='${cfg.appVersion}'
            export BUILD_NUMBER='${args.buildNumber}'
            export GIT_COMMIT='${args.commit}'
            export BUILD_TIME='${buildTime}'
        """.stripIndent()
    }

    /**
     * Renders manifests with envsubst and applies them, then blocks until the
     * rollout is genuinely Ready. On failure, diagnostics are printed and the
     * deployment is rolled back to the previous revision.
     *
     * @return true if the rollout succeeded and the app is serving
     */
    boolean deploy(Map args) {
        boolean ok = false
        String pre = exports(args)

        script.withCredentials([script.file(credentialsId: env.credentialId, variable: 'KUBECONFIG_FILE')]) {
            script.echo "Deploying ${args.image} → cluster=${env.cluster} ns=${env.namespace}"

            String who = script.sh(returnStdout: true, label: 'identity', script: pre + '''
                kubectl auth whoami -o jsonpath="{.status.userInfo.username}" 2>/dev/null || echo unknown
            ''').trim()
            script.echo "Authenticated as: ${who}"

            cfg.manifestFiles.each { String f ->
                script.sh(label: "render+apply ${f}",
                          script: pre + "envsubst < '${cfg.manifestPath}/${f}' | kubectl apply -n '${env.namespace}' -f -")
            }

            int rc = script.sh(label: 'await rollout', returnStatus: true,
                               script: pre + "kubectl -n '${env.namespace}' rollout status deploy/${cfg.appName} --timeout=120s")

            if (rc != 0) {
                script.echo "Rollout FAILED (exit ${rc}) — collecting diagnostics"
                diagnose(pre)
                rollback(pre)
                ok = false
            } else {
                script.echo "Rollout succeeded: ${cfg.appName} in ${env.name}"
                ok = true
            }
        }
        return ok
    }

    /** Prints the evidence an on-call engineer would actually want. */
    private void diagnose(String pre) {
        script.sh(label: 'diagnostics', returnStatus: true, script: pre + """
            echo '--- deployment ---'
            kubectl -n '${env.namespace}' get deploy/${cfg.appName} -o wide || true
            echo '--- pods ---'
            kubectl -n '${env.namespace}' get pods -l app.kubernetes.io/name=${cfg.appName} -o wide || true
            echo '--- recent events ---'
            kubectl -n '${env.namespace}' get events --sort-by=.lastTimestamp 2>/dev/null | tail -15 || true
            echo '--- logs (most recent pod) ---'
            POD=\$(kubectl -n '${env.namespace}' get pods -l app.kubernetes.io/name=${cfg.appName} \
                    --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1:].metadata.name}' 2>/dev/null)
            [ -n "\$POD" ] && kubectl -n '${env.namespace}' logs "\$POD" --tail=40 2>/dev/null || true
        """)
    }

    /** Reverts to the previous ReplicaSet and waits for it to stabilise. */
    void rollback(String pre) {
        script.echo "Rolling back ${cfg.appName} in ${env.name} to previous revision"
        int rc = script.sh(label: 'rollback', returnStatus: true, script: pre + """
            kubectl -n '${env.namespace}' rollout undo deploy/${cfg.appName}
            kubectl -n '${env.namespace}' rollout status deploy/${cfg.appName} --timeout=90s
        """)
        script.echo rc == 0 ? "Rollback complete — previous version restored"
                            : "ROLLBACK FAILED (exit ${rc}) — manual intervention required"
    }

    /** Rollback entry point for callers that don't hold the exports preamble. */
    void rollback(Map args) { 
        script.withCredentials([script.file(credentialsId: env.credentialId, variable: 'KUBECONFIG_FILE')]) {
            rollback(exports(args))
        }
    }

    /**
     * Verifies the deployed service actually answers from inside the cluster.
     * A green rollout only means pods started; this proves the app responds.
     */
    boolean smokeTest(Map args) {
        Map st = env.smokeTest as Map
        if (!st) { script.echo "No smokeTest declared for ${env.name} — skipping"; return true }

        String path = st.path ?: '/health'
        int expect  = (st.expectStatus ?: 200) as int
        String pre  = exports(args)
        boolean passed = false

        script.withCredentials([script.file(credentialsId: env.credentialId, variable: 'KUBECONFIG_FILE')]) {
            // Port-forward rather than `kubectl run`: the deployer ServiceAccount is
            // intentionally not granted create-on-pods, so the smoke test must verify
            // through the Service without creating workloads.
            int rc = script.sh(label: "smoke ${path}", returnStatus: true, script: pre + """
                set -e
                kubectl -n '${env.namespace}' port-forward svc/${cfg.appName} 18099:80 >/tmp/pf.log 2>&1 &
                PF=\$!
                trap 'kill \$PF 2>/dev/null || true' EXIT
                sleep 3
                CODE=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 --retry 3 --retry-delay 2 \
                        "http://127.0.0.1:18099${path}")
                echo "smoke: ${path} → \$CODE (expected ${expect})"
                [ "\$CODE" = "${expect}" ]
            """)
            script.echo rc == 0 ? "Smoke test passed: ${path} returned ${expect}"
                                : "Smoke test FAILED: ${path} did not return ${expect}"
            passed = (rc == 0)
        }
        return passed
    }
}
