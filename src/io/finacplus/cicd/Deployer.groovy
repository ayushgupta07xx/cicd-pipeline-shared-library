package io.finacplus.cicd

/**
 * Deploys a built image to one Kubernetes environment.
 *
 * Every method runs against a kubeconfig supplied by a Jenkins credential,
 * which authenticates as a namespaced, least-privilege ServiceAccount —
 * never a cluster-admin. The credential id comes from deploy-config.yaml,
 * so targeting a different cluster is configuration, not code.
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

    /**
     * Renders manifests with envsubst and applies them, then blocks until the
     * rollout is genuinely Ready. On failure, diagnostics are printed and the
     * deployment is rolled back to the previous revision.
     *
     * @return true if the rollout succeeded
     */
    boolean deploy(Map args) {
        String image  = args.image
        String commit = args.commit
        String buildNumber = args.buildNumber
        // CPS note: `return` inside withCredentials/withEnv closures returns from the
        // closure, not from this method. Result is captured in an outer variable.
        boolean ok = false

        script.withCredentials([script.file(credentialsId: env.credentialId, variable: 'KUBECONFIG_FILE')]) {
            script.withEnv([
                "KUBECONFIG=${script.env.KUBECONFIG_FILE}",
                "APP_NAME=${cfg.appName}",
                "NAMESPACE=${env.namespace}",
                "REPLICAS=${env.replicas ?: 1}",
                "IMAGE=${image}",
                "APP_ENV=${env.name}",
                "CLUSTER_NAME=${env.cluster}",
                "APP_VERSION=${cfg.appVersion}",
                "BUILD_NUMBER=${buildNumber}",
                "GIT_COMMIT=${commit}",
                "BUILD_TIME=${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))}"
            ]) {
                script.echo "Deploying ${image} → cluster=${env.cluster} ns=${env.namespace} as ${identity()}"

                cfg.manifestFiles.each { String f ->
                    String path = "${cfg.manifestPath}/${f}"
                    script.sh(label: "render+apply ${f}",
                              script: "envsubst < '${path}' | kubectl apply -n '${env.namespace}' -f -")
                }

                int rc = script.sh(
                    label: "await rollout",
                    returnStatus: true,
                    script: "kubectl -n '${env.namespace}' rollout status deploy/${cfg.appName} --timeout=120s"
                )

                if (rc != 0) {
                    script.echo "Rollout FAILED (exit ${rc}) — collecting diagnostics"
                    diagnose()
                    rollback()
                    ok = false
                } else {
                    script.echo "Rollout succeeded: ${cfg.appName} in ${env.name}"
                    ok = true
                }
            }
        }
        return ok
    }

    /** Who the pipeline is authenticated as — proves least privilege at run time. */
    private String identity() {
        return script.sh(
            returnStdout: true,
            script: 'kubectl auth whoami -o jsonpath="{.status.userInfo.username}" 2>/dev/null || echo unknown'
        ).trim()
    }

    /** Prints the evidence an on-call engineer would actually want. */
    private void diagnose() {
        script.sh(label: 'diagnostics', returnStatus: true, script: """
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
    void rollback() {
        script.echo "Rolling back ${cfg.appName} in ${env.name} to previous revision"
        int rc = script.sh(label: 'rollback', returnStatus: true, script: """
            kubectl -n '${env.namespace}' rollout undo deploy/${cfg.appName}
            kubectl -n '${env.namespace}' rollout status deploy/${cfg.appName} --timeout=90s
        """)
        script.echo rc == 0 ? "Rollback complete — previous version restored"
                            : "ROLLBACK FAILED (exit ${rc}) — manual intervention required"
    }

    /**
     * Verifies the deployed service actually answers, from inside the cluster.
     * A green rollout only means pods started; this proves the app responds.
     */
    boolean smokeTest() {
        Map st = env.smokeTest as Map
        if (!st) { script.echo "No smokeTest declared for ${env.name} — skipping"; return true }

        String path   = st.path ?: '/health'
        int expect    = (st.expectStatus ?: 200) as int
        String podName = "smoke-${cfg.appName}-${System.currentTimeMillis() % 100000}"

        boolean passed = false
        script.withCredentials([script.file(credentialsId: env.credentialId, variable: 'KUBECONFIG_FILE')]) {
            script.withEnv(["KUBECONFIG=${script.env.KUBECONFIG_FILE}"]) {
                int rc = script.sh(label: "smoke ${path}", returnStatus: true, script: """
                    kubectl -n '${env.namespace}' run ${podName} \
                      --image=curlimages/curl:8.11.1 --restart=Never --rm -i --quiet \
                      --command -- curl -s -o /dev/null -w '%{http_code}' \
                      --max-time 10 --retry 3 --retry-delay 2 \
                      'http://${cfg.appName}.${env.namespace}.svc.cluster.local:80${path}' \
                      | grep -q '^${expect}\$'
                """)
                script.echo rc == 0 ? "Smoke test passed: ${path} returned ${expect}"
                                    : "Smoke test FAILED: ${path} did not return ${expect}"
                passed = (rc == 0)
            }
        }
        return passed
    }
}
